package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"html"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Version 由构建脚本用 -ldflags "-X main.Version=x.y.z" 注入（见 tools/build-fpk.sh、tools/publish-update.sh）；
// 源码里的值只是本地直接 go build 时的默认值。
var Version = "1.15.0"

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

//go:embed web/family.html
var familyHTML []byte

// handleFamily 在中继里直接打开家人端页面。
//
// 为什么要这么做：飞牛 APP 里点一下就能看老人，不用再装一个 App；
// 而且页面和手机 App 用的是同一份 index.html（构建时复制过来），
// 不会出现"网页版功能落后一截"的双维护问题。
//
// 链接形如 /f?k=<设备令牌>&n=<称呼>。带 k 时把它写进浏览器本地存储，
// 页面就当成"已添加这位老人"，地址栏随后清掉参数（刷新不会重复覆盖）。
func (s *Store) handleFamily(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")

	k := r.URL.Query().Get("k")
	origin := originOf(r) // 已经带上网关前缀，页面里的接口地址才不会打到根上

	if k == "" {
		// 首页模式：直接列出"中继里已绑定"的全部老人（含令牌，所以要过访问密码）
		if !s.basicOK(r) {
			needPassword(w)
			return
		}
		_, _ = w.Write(injectBase(injectFamily(familyHTML, s.bootDevices(origin), origin, prefixOf(r), "family"), rootOf(r)))
		return
	}

	name := r.URL.Query().Get("n")
	if name == "" {
		name = "老人"
	}
	devs := []bootDevice{{ID: "relay", Name: name, Mode: "relay", Base: origin, Token: k}}
	_, _ = w.Write(injectBase(injectFamily(familyHTML, devs, origin, prefixOf(r), "family"), rootOf(r)))
}

func main() {
	log.SetFlags(log.LstdFlags)

	dataDir := os.Getenv("TRIM_PKGVAR")
	if dataDir == "" {
		dataDir = envOr("ELDER_DATA", filepath.Join(".", "data"))
	}
	dataDir = filepath.Clean(dataDir)

	log.Printf("[relay] 版本 %s 启动，数据目录 %s", Version, dataDir)

	st, err := newStore(dataDir)
	if err != nil {
		log.Fatalf("[relay] 初始化存储失败: %v", err)
	}

	// 家人端是 WebView 里用 file:// 加载的页面，跨域请求中继时浏览器会先问 CORS。
	// 中继以前一个头都不给 → 家人端所有请求都 "Failed to fetch"（查询位置、围栏、
	// 轨迹、远程命令全废）。鉴权靠 URL 里的令牌、不靠 Cookie，放开 * 是安全的。
	cors := func(h http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
			w.Header().Set("Access-Control-Max-Age", "86400")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			h.ServeHTTP(w, r)
		})
	}

	mux := http.NewServeMux()
	// 首页 = 家人页（默认）；管理台挪到 /admin。
	// 两者顶部都有「家人页 / 中继页」切换，桌面只留一个图标。
	mux.HandleFunc("/", st.handleHome)
	mux.HandleFunc("/admin", st.guard(st.handleIndex))

	mux.HandleFunc("/api/health", st.handleHealth)
	// 家人页：给飞牛 APP / 浏览器直接用（带 k 参数即可）
	mux.HandleFunc("/f", st.handleFamily)
	mux.HandleFunc("/api/report", st.handleReport)
	mux.HandleFunc("/api/events", st.handleEvents)
	mux.HandleFunc("/api/update/check", st.handleUpdateCheck)
	mux.HandleFunc("/api/update/apply", st.handleUpdateApply)
	mux.HandleFunc("/api/update/upload", st.handleUpdateUpload)
	mux.HandleFunc("/api/version/check", st.guard(st.handleVersionCheck))
	mux.HandleFunc("/api/wait", st.handleWait)
	mux.HandleFunc("/api/cmd/clear", st.handleCmdClear)
	mux.HandleFunc("/api/status", st.handleStatus)
	mux.HandleFunc("/api/cmd", st.handleCmd)
	mux.HandleFunc("/api/track", st.handleTrack)
	mux.HandleFunc("/api/fence", st.handleFence)
	mux.HandleFunc("/api/config", st.handleConfig)
	mux.HandleFunc("/api/password", st.handlePassword)
	// 对外地址（二维码里写哪个地址）—— 这是部署配置，不是访问方式的副产品
	mux.HandleFunc("/api/base", st.handlePublicBase)

	// 下面这几个是"人看的"：能拿到全部令牌，所以套一层访问密码。
	// 老人端和家人端走 ?k= 令牌，不在这里面，加密码后不用重配。
	mux.HandleFunc("/api/qr", st.guard(st.handleQR))
	mux.HandleFunc("/api/token", st.guard(st.handleTokenReset))
	mux.HandleFunc("/api/device", st.guard(st.handleDevice))
	mux.HandleFunc("/d/", st.guard(st.handleDevicePage))

	// 每分钟检查一次失联
	go func() {
		t := time.NewTicker(time.Minute)
		defer t.Stop()
		for range t.C {
			st.checkOffline()
		}
	}()

	// 启动时清一次过期轨迹，之后每天一次
	go func() {
		st.cleanupPoints()
		t := time.NewTicker(24 * time.Hour)
		defer t.Stop()
		for range t.C {
			st.cleanupPoints()
		}
	}()

	// 启动时把网关参数打出来：飞牛里"点了图标打不开"时，一行日志就能看出
	// 是没拿到前缀、还是 Socket 没建起来
	log.Printf("[relay] 飞牛网关：前缀=%q socket=%q", gatewayPrefix, gatewaySocketPath())

	addr := st.cfg.Bind
	if addr == "" {
		addr = fmt.Sprintf(":%d", st.cfg.Port)
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		if gatewaySocketPath() == "" {
			log.Fatalf("[relay] 监听 %s 失败: %v", addr, err)
		}
		// 端口被别的进程占了也不致命：飞牛网关走 Socket，老人端那条路如果断了
		// 会在日志里明确写出来，家里人看日志就知道该改哪个
		log.Printf("[relay] 端口 %s 起不来（%v），继续只用飞牛网关 Socket", addr, err)
	} else {
		log.Printf("[relay] 监听 %s", addr)
	}

	srv := &http.Server{
		// 里到外：cors → 网关前缀剥离 → 路由。
		// 网关进来的是 /app/elderrelay/xxx，剥掉前缀以后才能匹配到 /api/xxx。
		Handler:           cors(stripGatewayPrefix(mux)),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       5 * time.Minute,
	}

	if ln != nil {
		go func() {
			if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
				log.Printf("[relay] 服务退出: %v", err)
			}
		}()
	}

	// 飞牛统一网关：同一个 Server 再挂一个 Unix Socket。
	// 两个监听共用一份 Store，谁先收到请求都算数。
	if sp := gatewaySocketPath(); sp != "" {
		if gln, err := listenGatewaySocket(sp); err == nil {
			go func() {
				if err := srv.Serve(gln); err != nil && err != http.ErrServerClosed {
					log.Printf("[relay] 网关 Socket 退出: %v", err)
				}
			}()
		} else {
			log.Printf("[relay] 网关 Socket 监听失败: %v（不影响端口访问）", err)
		}
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig

	log.Printf("[relay] 收到退出信号，正在关闭")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)

	// 退出前把状态写一次盘
	st.finalFlush()
	log.Printf("[relay] 已退出")
}

// handleIndex 是配置中心：当前访问地址、扫码用的二维码、令牌、设备与围栏状态。
//
// 提醒：这个页面等同于凭证——能打开它的人就能读位置、改围栏。别把地址发给外人。
func (s *Store) handleIndex(w http.ResponseWriter, r *http.Request) {

	type deviceRow struct {
		ID, Name, Token string
		Online          bool
		Age             int64
		FenceCount      int
	}

	s.mu.RLock()
	now := time.Now().UnixMilli()
	th := int64(s.cfg.OfflineMinutes) * 60000
	if th <= 0 {
		th = 90 * 60000
	}
	list := make([]deviceRow, 0, len(s.cfg.Devices))
	for _, d := range s.cfg.Devices {
		last := s.lastSeen[d.ID]
		age := int64(-1)
		if last > 0 {
			age = (now - last) / 60000
		}
		fc := 0
		for _, f := range s.fences {
			if f.Device == d.ID {
				fc++
			}
		}
		list = append(list, deviceRow{d.ID, d.Name, d.Token, last > 0 && now-last <= th, age, fc})
	}
	dir := s.dir
	keepDays := s.cfg.KeepDays
	accessOn := s.cfg.AccessPassword != ""
	s.mu.RUnlock()

	var b strings.Builder
	pageHead(&b, "守护中继")
	b.WriteString(pageTabs("admin", prefixOf(r)))
	b.WriteString(`<h1>守护中继</h1>`)
	fmt.Fprintf(&b, `<div class="sub">版本 %s · %d 位老人 · 轨迹保留 %d 天 · <span class="muted">%s</span></div>`,
		Version, len(list), keepDays, html.EscapeString(dir))

	// 升级：手动上传新二进制 + 在线检查新版本（只提示，不自动装）
	repo := s.updateRepo()
	verHint := `<span class="muted">连 GitHub 看有没有新版，只提示、不自动装</span>`
	if repo == "" {
		verHint = `<span class="muted">未配置更新源：在 config.json 加 <code>update_repo</code>（owner/repo），` +
			`或设环境变量 <code>ELDER_UPDATE_REPO</code></span>`
	} else {
		verHint = `<span class="muted">更新源 <code>` + html.EscapeString(repo) + `</code>，只提示、不自动装</span>`
	}
	b.WriteString(`<div class="card" id="upd"><h2>版本<span class="tag">当前 ` +
		html.EscapeString(Version) + `</span></h2>
<div class="tip" id="updBody">选一个 ` + html.EscapeString(archKey()) + ` 的新程序上传，校验通过后自动替换并重启。<br>
文件在 <code>dist/update/</code> 里（<code>tools/publish-update.sh</code> 生成）。</div>
<div style="margin-top:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
<input type="file" id="updFile" style="font-size:12px;max-width:100%">
<button class="btn" id="updBtn" onclick="updUpload()">上传并升级</button>
</div>
<div style="margin-top:14px;padding-top:12px;border-top:1px solid var(--line)">
<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
<button class="btn" id="verBtn" onclick="verCheck()">检查新版本</button>` + verHint + `
</div>
<div class="tip" id="verBody" style="margin-top:8px;display:none"></div>
</div></div>`)

	b.WriteString(`<div class="card"><h2>老人<span class="tag">点名字看二维码</span></h2>`)
	b.WriteString(`<div style="margin-bottom:10px"><button class="btn" onclick="addDevice()">＋ 添加老人</button></div>`)
	if len(list) == 0 {
		b.WriteString(`<div class="empty">还没有设备，点上面的按钮加一位。</div>`)
	}
	for _, it := range list {
		state := `<span class="bad">离线</span>`
		if it.Online {
			state = `<span class="ok">在线</span>`
		}
		age := "从未上报"
		if it.Age >= 0 {
			age = fmt.Sprintf("%d 分钟前", it.Age)
		}
		fmt.Fprintf(&b, `<div style="padding:12px 0;border-bottom:1px solid var(--line);display:flex;align-items:center;gap:10px">
<div style="flex:1;min-width:0">
<a href="d/%s?k=%s" style="font-size:16px;color:var(--accent);text-decoration:none;font-weight:500">%s ›</a>
<div style="font-size:12px;color:var(--muted);margin-top:3px">%s · %s · 围栏 %d</div>
</div>
<button class="btn danger" onclick="delDevice('%s','%s')">删除</button></div>`,
			url.QueryEscape(it.ID), url.QueryEscape(it.Token), html.EscapeString(it.Name),
			state, age, it.FenceCount, html.EscapeString(it.ID), html.EscapeString(it.Name))
	}
	b.WriteString(`</div>`)

	b.WriteString(`<div class="card"><h2>网页访问密码</h2>`)
	if accessOn {
		b.WriteString(`<div class="kv"><span class="k">当前</span><span class="v"><span class="ok">已开启</span>　打开这些页面要输一次密码</span></div>`)
	} else {
		b.WriteString(`<div class="kv"><span class="k">当前</span><span class="v"><span class="bad">未设置</span>　任何拿到地址的人都能看到全部令牌</span></div>`)
	}
	b.WriteString(`<div style="margin-top:10px">`)
	b.WriteString(`<button class="btn" onclick="setPwd()">`)
	if accessOn {
		b.WriteString(`修改密码`)
	} else {
		b.WriteString(`设置密码`)
	}
	b.WriteString(`</button>`)
	if accessOn {
		b.WriteString(`　<button class="btn danger" onclick="clearPwd()">取消密码</button>`)
	}
	b.WriteString(`</div><div class="tip">只保护网页和二维码；老人端、家人端走令牌，不受影响，加密码后不用重配。<br>`)
	b.WriteString(`代价：改密码后手机浏览器要重新输一次。</div></div>`)

	b.WriteString(`<div class="card warn"><h2>注意</h2><div class="tip">`)
	b.WriteString(`这个首页能看到所有老人的令牌，等同于总凭证。设置访问密码后，即使地址被转发出去，别人也打不开。<br>`)
	b.WriteString(`给家人时只发他那一页的链接，他看不到别的老人。`)
	b.WriteString(`</div></div>`)

	b.WriteString(`</div><script>
function addDevice(){
  var n=prompt('这位老人怎么称呼？（例如：妈、外婆）','');
  if(n===null)return;
  fetch('api/device',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:n})})
    .then(function(r){return r.json()})
    .then(function(j){if(j.ok){location.reload()}else{alert(j.error||'添加失败')}});
}
function delDevice(id,name){
  if(!confirm('删除「'+name+'」？\n他的围栏和上报记录会一起删掉，不能恢复。'))return;
  fetch('api/device?id='+encodeURIComponent(id),{method:'DELETE'})
    .then(function(r){return r.json()})
    .then(function(j){if(j.ok){location.reload()}else{alert(j.error||'删除失败')}});
}
function setPwd(){
  var p=prompt('新的访问密码（留空=取消密码）','');
  if(p===null)return;
  fetch('api/password?p='+encodeURIComponent(p),{method:'POST'})
    .then(function(r){return r.json()})
    .then(function(j){
      if(j.ok){alert(j.enabled?'已设置。下次打开要输密码（用户名随便填）。':'已取消密码。');location.reload()}
      else{alert(j.error||'失败')}
    });
}
function clearPwd(){
  if(!confirm('取消访问密码？之后任何拿到地址的人都能看到全部令牌。'))return;
  fetch('api/password?p=',{method:'POST'})
    .then(function(r){return r.json()})
    .then(function(j){if(j.ok){location.reload()}else{alert(j.error||'失败')}});
}
function verCheck(){
  var box=document.getElementById('verBody');
  var btn=document.getElementById('verBtn');
  box.style.display='block'; box.innerHTML='正在问 GitHub…';
  btn.disabled=true;
  fetch('api/version/check',{cache:'no-store'})
    .then(function(r){return r.json()})
    .then(function(j){
      btn.disabled=false;
      if(!j.ok){box.innerHTML='<span class="bad">'+esc(j.error||'检查失败')+'</span>'+(j.hint?'<br>'+esc(j.hint):'');return}
      if(j.newer){
        box.innerHTML='<span class="ok">有新版本 '+esc(j.latest)+'</span>（当前 '+esc(j.current)+'）<br>'+
          (j.notes?'<span class="muted" style="white-space:pre-wrap">'+esc(j.notes)+'</span><br>':'')+
          (j.url?'<a href="'+esc(j.url)+'" target="_blank" rel="noreferrer">去下载页看发布说明 ›</a>':'');
      }else{
        box.innerHTML='<span class="ok">已是最新</span>（'+esc(j.current)+'）';
      }
    })
    .catch(function(){btn.disabled=false;box.innerHTML='<span class="bad">请求失败</span>'});
}
function updUpload(){
  var f=document.getElementById('updFile').files[0];
  var body=document.getElementById('updBody');
  if(!f){alert('先选一个文件');return}
  if(!confirm('上传 '+f.name+'（'+(f.size/1048576).toFixed(1)+'MB）并替换当前程序？\n替换完会自动重启，页面会断开几秒。'))return;
  body.innerHTML='正在上传…';
  var fd=new FormData(); fd.append('file',f);
  fetch('api/update/upload',{method:'POST',body:fd})
    .then(function(r){return r.json()})
    .then(function(j){
      if(!j.ok){body.innerHTML='<span class="bad">'+esc(j.error||'升级失败')+'</span>';return}
      body.innerHTML='已替换，正在重启…';
      var n=0,t=setInterval(function(){
        n++;
        fetch('api/health',{cache:'no-store'}).then(function(r){return r.json()}).then(function(h){
          if(h&&h.ok){clearInterval(t);body.innerHTML='<span class="ok">已升级到 '+esc(h.version)+'</span>';setTimeout(function(){location.reload()},800)}
        }).catch(function(){});
        if(n>30){clearInterval(t);body.innerHTML='<span class="bad">等太久了，手动刷新页面</span>'}
      },1000);
    })
    .catch(function(e){body.innerHTML='<span class="bad">上传失败：'+e+'</span>'});
}
</script></body></html>`)

	writeHTML(w, r, b.String())
}

// ===== 页面骨架 =====

const pageCSS = `:root{--fg:#1a1a1a;--muted:#7a7a7a;--line:#e8e8e8;--bg:#fafafa;--accent:#185FA5;--ok:#1a7f37;--bad:#b42318}
*{box-sizing:border-box}
body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--fg);margin:0;padding:24px 16px 48px;line-height:1.65}
.wrap{max-width:640px;margin:0 auto}
h1{font-size:22px;font-weight:600;margin:0 0 4px}
.sub{color:var(--muted);font-size:13px;margin-bottom:20px;word-break:break-all}
.card{background:#fff;border:1px solid var(--line);border-radius:12px;padding:18px 20px;margin-bottom:14px}
h2{font-size:15px;font-weight:600;margin:0 0 14px;display:flex;align-items:center;gap:8px}
h2 .tag{font-size:11px;font-weight:400;color:var(--muted);border:1px solid var(--line);border-radius:20px;padding:1px 8px}
.qr{display:block;width:320px;max-width:86vw;height:auto;aspect-ratio:1/1;border-radius:8px;background:#fff;border:1px solid var(--line);padding:8px;margin:0 auto 14px;image-rendering:pixelated}
.kv{display:flex;gap:8px;align-items:flex-start;font-size:13px;margin:6px 0}
.kv .k{color:var(--muted);flex:0 0 62px}
.kv .v{flex:1;word-break:break-all}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;background:#f4f4f5;padding:2px 6px;border-radius:4px}
.btn{font:inherit;font-size:12px;border:1px solid var(--line);background:#fff;border-radius:6px;padding:4px 10px;cursor:pointer;color:var(--accent)}
.btn.danger{color:var(--bad)}
table{width:100%;border-collapse:collapse;font-size:13px}
td,th{text-align:left;padding:7px 4px;border-bottom:1px solid var(--line)}
th{color:var(--muted);font-weight:400;font-size:12px}
tr:last-child td{border-bottom:none}
.ok{color:var(--ok)}.bad{color:var(--bad)}.muted{color:var(--muted)}
.empty{color:var(--muted);font-size:13px;padding:6px 0}
.tip{font-size:12px;color:var(--muted);margin-top:10px;line-height:1.65}
.warn{background:#fffbf0;border-color:#f0e0b8}
a{color:var(--accent)}`

func pageHead(b *strings.Builder, title string) {
	b.WriteString(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">`)
	b.WriteString(`<meta name="viewport" content="width=device-width,initial-scale=1">`)
	b.WriteString(`<title>`)
	b.WriteString(html.EscapeString(title))
	b.WriteString(`</title><style>`)
	b.WriteString(pageCSS)
	b.WriteString(`</style></head><body><div class="wrap">`)
}

// writeHTML 输出页面。r 用来判断是不是从飞牛网关进来的：
// 网关下要注入 <base href="/app/elderrelay/">，页面里的相对路径才对得上。
func writeHTML(w http.ResponseWriter, r *http.Request, s string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(injectBase([]byte(s), rootOf(r)))
}

// ===== /d/<设备id> 单个老人的页面 =====

func (s *Store) handleDevicePage(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/d/"), "/")
	if id == "" {
		http.Redirect(w, r, rootOf(r), http.StatusFound)
		return
	}

	dev, ok := s.deviceByToken(tokenOf(r))
	if !ok || dev.ID != id {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`<!doctype html><meta charset="utf-8">` +
			`<meta name="viewport" content="width=device-width,initial-scale=1">` +
			`<p style="font-family:system-ui;padding:48px 24px;text-align:center;color:#666">` +
			`令牌不对，或者这个页面不属于你。<br><br><a href="./">回首页</a></p>`))
		return
	}

	type fenceView struct {
		ID     string
		Name   string
		Desc   string // "500 米" 或 "多边形 6 个点"
		Inside bool
	}

	s.mu.RLock()
	now := time.Now().UnixMilli()
	th := int64(s.cfg.OfflineMinutes) * 60000
	if th <= 0 {
		th = 90 * 60000
	}
	last := s.lastSeen[dev.ID]
	online := last > 0 && now-last <= th
	age := int64(-1)
	if last > 0 {
		age = (now - last) / 60000
	}
	rep := s.lastRep[dev.ID]

	fvs := make([]fenceView, 0, 4)
	for _, f := range s.fences {
		if f.Device != dev.ID {
			continue
		}
		inside := true
		if st := s.states[dev.ID][f.ID]; st != nil && st.Inited {
			inside = st.Inside
		}
		fvs = append(fvs, fenceView{f.ID, f.Name, fenceDesc(f), inside})
	}
	dir := s.dir
	accessOn2 := s.cfg.AccessPassword != ""
	s.mu.RUnlock()

	// 二维码里写哪个地址，由 public_base 决定（见 qrbase.go）。
	// 拿不准就等于空，宁可不出码，也不给一个扫了打不通的。
	base, baseFrom := s.qrBase(r)
	hasBase := base != ""
	basePublic := hasBase && looksPublic(base)
	reportURL := ""
	if hasBase {
		reportURL = base + "/api/report?k=" + dev.Token
	}
	tok := html.EscapeString(dev.Token)

	var b strings.Builder
	pageHead(&b, "守护中继 · "+dev.Name)
	fmt.Fprintf(&b, `<h1>%s</h1>`, html.EscapeString(dev.Name))

	state := `<span class="bad">离线</span>`
	if online {
		state = `<span class="ok">在线</span>`
	}
	ageTxt := "从未上报"
	if age >= 0 {
		ageTxt = fmt.Sprintf("%d 分钟前", age)
	}
	fmt.Fprintf(&b, `<div class="sub">%s · 最近上报 %s · 数据目录 <code>%s</code></div>`,
		state, ageTxt, html.EscapeString(dir))

	if rep != nil && rep.Battery > 0 {
		b.WriteString(`<div class="card"><h2>当前状态</h2>`)
		fmt.Fprintf(&b, `<div class="kv"><span class="k">电量</span><span class="v">%d%%`, rep.Battery)
		if rep.Charging {
			b.WriteString(`　充电中`)
		}
		b.WriteString(`</span></div><div class="kv"><span class="k">网络</span><span class="v">`)
		switch {
		case rep.Wifi:
			b.WriteString(`WiFi`)
		case rep.Mobile:
			b.WriteString(`移动数据`)
		default:
			b.WriteString(`未知`)
		}
		b.WriteString(`</span></div></div>`)
	}

	// 先把"码里到底是什么地址"摆出来，再给码。
	// 以前没有这一块：从飞牛图标或局域网 IP 打开时，码里就是那个地址，
	// 扫出来不是打不通就是只能在家用，而页面上一点提示都没有。
	b.WriteString(`<div class="card" id="qrBaseCard"><h2>二维码地址<span class="tag">决定扫码后能不能用</span></h2>`)
	switch baseFrom {
	case "config":
		fmt.Fprintf(&b, `<div class="kv"><span class="k">现在用</span><span class="v"><code>%s</code>　%s</span></div>`,
			html.EscapeString(base), baseTag(basePublic))
	case "gateway":
		b.WriteString(`<div class="kv"><span class="k">现在用</span><span class="v"><span class="bad">还不知道</span>　` +
			`通过飞牛图标进来时，浏览器地址是「飞牛域名 + /app/elderrelay」，那不是中继的对外入口</span></div>`)
	default:
		fmt.Fprintf(&b, `<div class="kv"><span class="k">现在用</span><span class="v"><code>%s</code>　%s</span></div>`,
			html.EscapeString(base), baseTag(basePublic))
	}

	b.WriteString(`<div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">`)
	b.WriteString(`<input id="baseIn" value="` + html.EscapeString(base) + `" ` +
		`placeholder="https://shouhu.example.com:812" style="flex:1;min-width:220px">`)
	b.WriteString(`<button class="btn" onclick="saveBase()">保存</button>`)
	if baseFrom != "gateway" && basePublic {
		b.WriteString(`<button class="btn ghost" onclick="fillBase()">用当前访问地址</button>`)
	}
	b.WriteString(`</div>`)

	switch baseFrom {
	case "config":
		b.WriteString(`<div class="tip">二维码和上报地址都用它。改了之后，老人端和家人端要重新扫一次才会换到新地址。</div>`)
	case "gateway":
		b.WriteString(`<div class="tip">填一个能从外网访问的地址（DDNS 域名或反向代理，例如 ` +
			`<code>https://shouhu.xxx.com:8898</code>），保存后这里才会出二维码。<br>` +
			`不填也能用：家人端手动添加、老人端手填地址都行，只是没法扫码。</div>`)
	default:
		if basePublic {
			b.WriteString(`<div class="tip">现在的访问地址就是域名，可以直接用。建议保存下来 —— ` +
				`以后从飞牛图标或局域网进来，二维码也不会跟着变。</div>`)
		} else {
			b.WriteString(`<div class="tip">现在用的是<b>局域网地址</b>：家里人扫了只能在家用，出门就查不到。` +
				`想让老人在外面也能用，填一个域名（DDNS / 反向代理）再保存。</div>`)
		}
	}
	b.WriteString(`</div>`)

	if hasBase {
		b.WriteString(`<div class="card"><h2>家人端<span class="tag">扫码添加</span></h2>`)
		b.WriteString(`<img class="qr" src="api/qr?t=family&k=` + tok + `" alt="家人端二维码">`)
		b.WriteString(`<div class="kv"><span class="k">地址</span><span class="v"><code>` + html.EscapeString(base) + `</code></span></div>`)
		b.WriteString(`<div class="kv"><span class="k">令牌</span><span class="v"><code>` + tok + `</code></span></div>`)
		b.WriteString(`<div class="tip">把这张截图发给家人，用家人端 App 扫一下就会自动配好；也可以直接把这个地址发给他。</div></div>`)

		b.WriteString(`<div class="card"><h2>老人端<span class="tag">扫码或粘贴</span></h2>`)
		b.WriteString(`<img class="qr" src="api/qr?t=elder&k=` + tok + `" alt="老人端二维码">`)
		b.WriteString(`<div class="kv"><span class="k">上报地址</span><span class="v"><code>` + html.EscapeString(reportURL) + `</code></span></div>`)
		b.WriteString(`<div class="tip">老人端「设置 → 定位和传输 → 主动上报」点「扫一扫」扫这个码即可；也能粘贴地址，间隔选 10 分钟。</div></div>`)
	} else {
		b.WriteString(`<div class="card"><h2>家人端 / 老人端二维码</h2>` +
			`<div class="empty">先在上面填「二维码地址」并保存，这里才会生成二维码。<br>` +
			`没有二维码也能用：家人端手动添加、老人端手填上报地址。</div></div>`)
	}

	fmt.Fprintf(&b, `<div class="card"><h2>围栏<span class="tag">%d 个</span></h2>`, len(fvs))
	if len(fvs) == 0 {
		b.WriteString(`<div class="empty">还没有围栏。在家人端 App 里点「设围栏」就会同步到这里。</div>`)
	} else {
		b.WriteString(`<table><tr><th>名字</th><th>范围</th><th>当前</th><th></th></tr>`)
		for _, f := range fvs {
			st := `<span class="ok">在内</span>`
			if !f.Inside {
				st = `<span class="bad">在外</span>`
			}
			fmt.Fprintf(&b, `<tr><td>%s</td><td>%s</td><td>%s</td><td>`+
				`<button class="btn danger" onclick="delFence('%s')">删除</button></td></tr>`,
				html.EscapeString(f.Name), html.EscapeString(f.Desc), st, html.EscapeString(f.ID))
		}
		b.WriteString(`</table>`)
	}
	b.WriteString(`</div>`)

	b.WriteString(`<div class="card warn"><h2>注意</h2><div class="tip">`)
	b.WriteString(`这个链接等同于这位老人的凭证，别转给不相关的人。<br>`)
	b.WriteString(`重新生成令牌后，老人端和家人端都要重新扫码。`)
	if !accessOn2 {
		b.WriteString(`<br><span class="bad">网页访问密码未设置</span>——拿到链接的人就能打开这一页，建议回首页设一个。`)
	}
	b.WriteString(`</div><div style="margin-top:12px">`)
	b.WriteString(`<button class="btn danger" onclick="resetToken()">重新生成令牌</button></div></div>`)

	fmt.Fprintf(&b, `</div><script>
function delFence(id){
  if(!confirm('删除这个围栏？'))return;
  fetch('api/fence?id='+encodeURIComponent(id)+'&k=%s',{method:'DELETE'})
    .then(function(r){return r.json()})
    .then(function(j){if(j.ok){location.reload()}else{alert(j.error||'删除失败')}});
}
function saveBase(){
  var v=document.getElementById('baseIn').value.trim();
  fetch('api/base?u='+encodeURIComponent(v),{method:'POST'})
    .then(function(r){return r.json()})
    .then(function(j){
      if(j.ok){location.reload()}else{alert((j&&(j.error||j.msg))||'保存失败')}
    })
    .catch(function(e){alert('保存失败：'+(e.message||e))});
}
function fillBase(){
  var el=document.getElementById('baseIn');
  if(el){el.value=location.origin}
}
function resetToken(){
  if(!confirm('重新生成后，老人端和家人都要重新扫码，确定？'))return;
  fetch('api/token?k=%s',{method:'POST'})
    .then(function(r){return r.json()})
    .then(function(j){
      if(j.ok){location.href='d/%s?k='+encodeURIComponent(j.token)}
      else{alert(j.error||'失败')}
    });
}
</script></body></html>`, tok, tok, url.QueryEscape(dev.ID))

	writeHTML(w, r, b.String())
}

// baseTag 给"这个地址能不能从外网访问"一个明确的标签。
// 局域网地址不拦着用，但必须写清楚"出门就用不了"。
func baseTag(public bool) string {
	if public {
		return `<span class="ok">外网可访问</span>`
	}
	return `<span class="bad">局域网地址 · 只能在家用</span>`
}

// finalFlush 退出前同步落盘
func (s *Store) finalFlush() {
	s.mu.Lock()
	st := stateFile{
		LastID:   s.lastID,
		Fences:   s.fences,
		States:   s.states,
		Events:   s.events,
		LastSeen: s.lastSeen,
		LastRep:  s.lastRep,
	}
	s.mu.Unlock()

	if b, err := json.Marshal(st); err == nil {
		tmp := filepath.Join(s.dir, "state.json.tmp")
		if os.WriteFile(tmp, b, 0o644) == nil {
			_ = os.Rename(tmp, filepath.Join(s.dir, "state.json"))
		}
	}
	if s.points != nil {
		_ = s.points.Sync()
		_ = s.points.Close()
	}
}
