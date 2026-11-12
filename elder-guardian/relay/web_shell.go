package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// 单页双视图：/ = 家人页（默认），/admin = 中继页。
//
// 以前是两个桌面图标、两套入口；现在只留一个图标，页面顶部两个标签切换，
// 家里人打开看到的就是位置，不用先看一堆令牌和管理项。
// 这里刻意不合并两边的代码 —— 家人页和手机 App 共用同一份 index.html，
// 合并只会让两边都变难维护。

const tabsCSS = `<style>
.tabs{display:flex;gap:8px;margin:0 0 16px;font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
.tabs a{flex:1;text-align:center;padding:11px 0;border:1px solid #e8e8e8;border-radius:10px;background:#fff;
color:#6b7280;text-decoration:none;font-size:14px;line-height:1.2}
.tabs a.on{background:#185FA5;border-color:#185FA5;color:#fff;font-weight:600}
</style>`

// pageTabs 生成顶部两个标签。
//
// prefix 是本次访问的"根"：直连时是空串，飞牛网关下是 /app/elderrelay。
// **必须带上它**，不能写死 href="/"。写死的时候在网关里点「家人页」会跑到
// https://<域名>/ 被飞牛接管成桌面，「中继页」跑到 /admin 直接变成飞牛自己的
// "这里暂时没有装入页面"—— 手机和电脑上表现一模一样。
func pageTabs(active, prefix string) string {
	fam, adm := "", ""
	if active == "family" {
		fam = ` class="on"`
	} else {
		adm = ` class="on"`
	}
	home := prefix + "/"
	admin := prefix + "/admin"
	return tabsCSS + `<div class="tabs"><a href="` + home + `"` + fam +
		`>家人页</a><a href="` + admin + `"` + adm + `>中继页</a></div>`
}

// handleHome 是 "/" 的入口：默认家人页，?tab=admin 或 /admin 进中继页。
func (s *Store) handleHome(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	if r.URL.Query().Get("tab") == "admin" {
		s.handleIndex(w, r)
		return
	}
	s.handleFamily(w, r)
}

type bootDevice struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Mode  string `json:"mode"`
	Base  string `json:"base"`
	Token string `json:"token"`
}

// originOf 取请求的来源（考虑 CF / 反代的 X-Forwarded-Proto）
func originOf(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		scheme = p
	}
	// 网关访问时页面挂在 /app/elderrelay 下，接口地址也必须跟着带前缀，
	// 否则家人页会去请求 https://host/api/status（根上什么都没有）。
	return scheme + "://" + r.Host + prefixOf(r)
}

// injectFamily 往家人页里注入一段脚本：
//   - 把这台中继上"已绑定"的老人直接写进本地存储（不再需要扫码/手填）
//   - 隐藏"添加老人""自动刷新""后台提醒"这些在网页里用不到的东西
//   - 顶部加「家人页 / 中继页」切换
//   - 只有一位老人时直接进详情，省一次点击
func injectFamily(body []byte, devs []bootDevice, origin, prefix, active string) []byte {
	db, _ := json.Marshal(map[string]interface{}{"elders": devs})
	boot := fmt.Sprintf(`<script>(function(){
// 接口地址的 origin 在浏览器里自己算，不用服务端拼：
// 走飞牛网关时请求是从 Unix Socket 进来的，r.TLS 是空的、X-Forwarded-Proto 也不一定有，
// 服务端很容易拼出 http:// 交给一个 https 页面 —— 浏览器直接按混合内容拦掉，
// 表现就是"页面打开了但数据全是空的"。location.origin 永远和页面同源，不会有这问题。
var PFX=%q,SRV=%q;
try{
 var d=JSON.parse(%q);
 var org=(location.protocol==='http:'||location.protocol==='https:')?location.origin+PFX:SRV;
 d.elders.forEach(function(x){x.base=org;});
 localStorage.setItem('elder_family_v2',JSON.stringify(d));
}catch(e){try{localStorage.setItem('elder_family_v2',%q);}catch(e2){}}
try{if(typeof autoStatusOn!=='undefined')autoStatusOn=false;}catch(e){}
try{var h=document.getElementById('autoHint');if(h)h.textContent='';}catch(e){}
var css=document.createElement('style');
// 网页版用不到：添加老人（中继里已经绑好了）、自动刷新、后台提醒。
// 注意第一个卡片的标题要整块藏掉 —— 只藏按钮的话页面上还留着一个"添加老人"，
// 看着像还要扫码。
css.textContent='#addPanel{display:none!important}.autoline{display:none!important}'+
 '#viewList>.card:first-child h1{display:none!important}'+
 '#viewList>.card:first-child .tip{display:none!important}';
document.head.appendChild(css);
// 注入的 localStorage 是在页面脚本之后写的，界面已经渲染成"还没有添加"了。
// 这里让页面用新数据重画一次，否则会一直显示空白列表。
try{
 if(typeof loadDB==='function'){loadDB();}
 if(typeof renderList==='function'){renderList();}
}catch(e){}
try{
 if(%q==='family'&&%d===1&&typeof openElder==='function'){setTimeout(function(){openElder(%q)},260);}
}catch(e){}
})();</script>`,
		prefix, origin, string(db), string(db), active, len(devs), firstID(devs))

	out := bytes.Replace(body, []byte("</body>"), append([]byte(boot), []byte("</body>")...), 1)
	return insertAfterBodyStart(out, pageTabs(active, prefix))
}

func firstID(devs []bootDevice) string {
	if len(devs) == 0 {
		return ""
	}
	return devs[0].ID
}

// bootDevices 把中继里已绑定的设备转成家人页要的结构
func (s *Store) bootDevices(origin string) []bootDevice {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]bootDevice, 0, len(s.cfg.Devices))
	for _, d := range s.cfg.Devices {
		out = append(out, bootDevice{ID: d.ID, Name: d.Name, Mode: "relay", Base: origin, Token: d.Token})
	}
	return out
}

// 401 页面（家人页/管理台共用）
func needPassword(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", `Basic realm="守护中继", charset="UTF-8"`)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusUnauthorized)
	_, _ = w.Write([]byte(`<!doctype html><meta charset="utf-8">` +
		`<meta name="viewport" content="width=device-width,initial-scale=1">` +
		`<p style="font-family:system-ui;padding:56px 24px;text-align:center;color:#666;line-height:1.8">` +
		`需要访问密码。<br>用户名随便填，密码填中继的访问密码。</p>`))
}

// 让 strings 包被使用（页头拼接会用到），避免以后改动时忘了引入
var _ = strings.TrimSpace

// insertAfterBodyStart 把一段 HTML 直接放到 <body> 开头。
//
// 原来是靠 JS 里 bar.innerHTML="..." 注入的 —— 那样在源码里是转义过的 \"，
// 测试和肉眼排查都容易看错，而且首屏会先闪一下没有标签的样子。
// 直接在服务端插进去更直观。
func insertAfterBodyStart(html []byte, frag string) []byte {
	if frag == "" {
		return html
	}
	i := bytes.Index(bytes.ToLower(html), []byte("<body"))
	if i < 0 {
		return append([]byte(frag), html...)
	}
	j := bytes.IndexByte(html[i:], '>')
	if j < 0 {
		return append([]byte(frag), html...)
	}
	cut := i + j + 1
	// 管理台的正文包在 <div class="wrap"> 里（有 max-width 和居中对齐），
	// 标签插到 wrap 外面会横贯整个窗口、跟下面的内容对不齐。
	if k := bytes.Index(bytes.ToLower(html[cut:]), []byte(`<div class="wrap">`)); k >= 0 {
		if len(bytes.TrimSpace(html[cut:cut+k])) == 0 {
			cut += k + len(`<div class="wrap">`)
		}
	}
	out := make([]byte, 0, len(html)+len(frag))
	out = append(out, html[:cut]...)
	out = append(out, frag...)
	out = append(out, html[cut:]...)
	return out
}
