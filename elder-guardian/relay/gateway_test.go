package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 飞牛网关：/app/elderrelay/xxx 要能路由到 /xxx，且直连（不带前缀）照旧能用。
func TestGatewayPrefixRouting(t *testing.T) {
	old := gatewayPrefix
	gatewayPrefix = "/app/elderrelay"
	defer func() { gatewayPrefix = old }()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "OK")
	})
	h := stripGatewayPrefix(mux)

	for _, path := range []string{"/app/elderrelay/api/health", "/api/health"} {
		req := httptest.NewRequest("GET", path, nil)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != 200 || rec.Body.String() != "OK" {
			t.Fatalf("%s 没路由到处理器：%d %q", path, rec.Code, rec.Body.String())
		}
	}

	// 带前缀时必须记住前缀：页面里的接口地址、<base> 都靠它
	req := httptest.NewRequest("GET", "/app/elderrelay/f?k=x", nil)
	if got := prefixOf(req); got != "" {
		t.Fatalf("没经过中间件就取到了前缀：%q", got)
	}
	rec := httptest.NewRecorder()
	stripGatewayPrefix(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if p := prefixOf(r); p != "/app/elderrelay" {
			t.Fatalf("前缀没记进 context：%q", p)
		}
		if r.URL.Path != "/f" {
			t.Fatalf("路径没剥干净：%q", r.URL.Path)
		}
		if root := rootOf(r); root != "/app/elderrelay/" {
			t.Fatalf("rootOf 不对：%q", root)
		}
	})).ServeHTTP(rec, req)
}

// <base> 注入：网关下 /app/elderrelay/，直连下 /
func TestInjectBase(t *testing.T) {
	html := []byte("<html><head><title>x</title></head><body></body></html>")

	got := string(injectBase(html, "/app/elderrelay/"))
	if !strings.Contains(got, `<base href="/app/elderrelay/">`) {
		t.Fatalf("网关下没注入前缀 base：%s", got)
	}
	got = string(injectBase(html, "/"))
	if !strings.Contains(got, `<base href="/">`) {
		t.Fatalf("直连下没注入根 base：%s", got)
	}
	// 已经有 base 就别重复插
	twice := injectBase(injectBase(html, "/"), "/")
	if strings.Count(string(twice), "<base ") != 1 {
		t.Fatalf("base 被插了多次：%s", twice)
	}
}

// 网关过来的人已经在 fnOS 登录过，不要再弹一次浏览器密码框
func TestGatewaySkipsBasicAuth(t *testing.T) {
	oldPrefix := gatewayPrefix
	gatewayPrefix = "/app/elderrelay"
	defer func() { gatewayPrefix = oldPrefix }()

	s, _ := newStore(t.TempDir())
	defer s.Close()
	s.mu.Lock()
	s.cfg.AccessPassword = "secret"
	s.mu.Unlock()

	direct := httptest.NewRequest("GET", "/admin", nil)
	if s.basicOK(direct) {
		t.Fatal("直连没带密码却通过了")
	}

	req := httptest.NewRequest("GET", "/app/elderrelay/admin", nil)
	rec := httptest.NewRecorder()
	stripGatewayPrefix(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.basicOK(r) {
			t.Fatal("网关请求不该再要密码")
		}
	})).ServeHTTP(rec, req)
}

// 端到端：真的监听 Unix Socket，模拟飞牛网关带着 /app/elderrelay 前缀转发进来。
func TestGatewayUnixSocket(t *testing.T) {
	old := gatewayPrefix
	gatewayPrefix = "/app/elderrelay"
	defer func() { gatewayPrefix = old }()

	dir := t.TempDir()
	sock := filepath.Join(dir, "app.sock")
	ln, err := listenGatewaySocket(sock)
	if err != nil {
		t.Skipf("本机不支持 Unix Socket：%v", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
		// 网关会带 X-Trim-Userid，这里只确认路由到了
		_, _ = io.WriteString(w, `{"ok":true}`)
	})
	srv := &http.Server{Handler: stripGatewayPrefix(mux)}
	go func() { _ = srv.Serve(ln) }()
	defer srv.Close()

	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return net.Dial("unix", sock)
		},
	}
	c := &http.Client{Transport: tr, Timeout: 3 * time.Second}

	resp, err := c.Get("http://nas.local/app/elderrelay/api/health")
	if err != nil {
		t.Fatalf("经 Socket 请求失败：%v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.Contains(string(body), `"ok":true`) {
		t.Fatalf("网关请求没走通：%d %s", resp.StatusCode, body)
	}
}

// 网关下点顶部两个标签必须留在应用里。
//
// 曾经写死 href="/" 和 href="/admin"：在飞牛网关里点「家人页」会跳到
// https://<域名>/ 被飞牛接管成桌面，「中继页」跳到 /admin 直接是飞牛的
// "这里暂时没有装入页面"（404）—— 手机 App 和电脑端表现完全一样。
func TestTabsKeepGatewayPrefix(t *testing.T) {
	old := gatewayPrefix
	gatewayPrefix = "/app/elderrelay"
	defer func() { gatewayPrefix = old }()

	s, _ := newStore(t.TempDir())
	defer s.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/", s.handleHome)
	mux.HandleFunc("/admin", s.handleIndex)
	h := stripGatewayPrefix(mux)

	get := func(path string) string {
		req := httptest.NewRequest("GET", path, nil)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != 200 {
			t.Fatalf("%s -> %d %s", path, rec.Code, rec.Body.String()[:min(120, rec.Body.Len())])
		}
		return rec.Body.String()
	}

	for _, p := range []string{"/app/elderrelay/", "/app/elderrelay/admin"} {
		body := get(p)
		if strings.Contains(body, `href="/"`) || strings.Contains(body, `href="/admin"`) {
			t.Errorf("%s 里还有裸根链接，网关下会跳出应用", p)
		}
		if !strings.Contains(body, `href="/app/elderrelay/"`) {
			t.Errorf("%s 缺带前缀的「家人页」链接", p)
		}
		if !strings.Contains(body, `href="/app/elderrelay/admin"`) {
			t.Errorf("%s 缺带前缀的「中继页」链接", p)
		}
	}

	// 直连（没有前缀）时要指回 / 和 /admin
	body := get("/admin")
	if !strings.Contains(body, `href="/"`) {
		t.Error("直连下「家人页」链接丢了")
	}
	if !strings.Contains(body, `href="/admin"`) {
		t.Error("直连下「中继页」链接丢了")
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// 家人页注入的接口地址要按浏览器自己的 origin 算。
// 网关请求是从 Unix Socket 进来的，服务端拿不到 https 信息，拼出来的 http://
// 会被浏览器按混合内容拦掉（页面能开、数据全空）。
func TestFamilyBootUsesLocationOrigin(t *testing.T) {
	old := gatewayPrefix
	gatewayPrefix = "/app/elderrelay"
	defer func() { gatewayPrefix = old }()

	s, _ := newStore(t.TempDir())
	defer s.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/", s.handleHome)
	h := stripGatewayPrefix(mux)

	req := httptest.NewRequest("GET", "/app/elderrelay/", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	body := rec.Body.String()

	if !strings.Contains(body, `var PFX="/app/elderrelay"`) {
		t.Error("注入脚本没拿到网关前缀，家人页会去请求根上的接口")
	}
	if !strings.Contains(body, "location.origin+PFX") {
		t.Error("接口 origin 还是服务端拼的，https 页面下会被拦成混合内容")
	}
	if !strings.Contains(body, "d.elders.forEach") {
		t.Error("设备地址没在浏览器里重写")
	}
}
