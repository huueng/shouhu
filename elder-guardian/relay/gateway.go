package main

import (
	"bytes"
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// 飞牛统一网关（Unified Gateway）
//
// 背景：在飞牛 APP / 桌面里点应用图标，打开的是
//     https://<NAS域名>:<端口>/app/<appname>/...
// 也就是**同一个域名下的一个路径前缀**，而不是 IPv6 + 端口。
// 网关先校验 NAS 登录态，再把请求转发给应用监听在 target/ 下的 Unix Socket。
//
// 所以应用要做三件事：
//  1. 额外监听 FNNAS_GATEWAY_SOCKET 指定的 Unix Socket（TCP 端口照旧保留，
//     老人端上报、Lucky 反代、局域网直连都还在用它）
//  2. 把 FNNAS_GATEWAY_PREFIX（/app/elderrelay）从前缀里剥掉再路由
//  3. 页面里的绝对路径改成相对路径 + 注入 <base>，否则 /api/... 会打到根上去，
//     在网关下就是 404

// ctxGatewayPrefix 把"这次请求是从网关进来的"记进 context。
// 不能用一个全局布尔：同一个进程同时服务 Socket 和 TCP，必须按请求区分。
type gwCtxKey struct{}

var gatewayPrefix = strings.TrimRight(os.Getenv("FNNAS_GATEWAY_PREFIX"), "/")

func gatewaySocketPath() string {
	return strings.TrimSpace(os.Getenv("FNNAS_GATEWAY_SOCKET"))
}

// stripGatewayPrefix 把 /app/elderrelay/xxx 变成 /xxx。
func stripGatewayPrefix(next http.Handler) http.Handler {
	if gatewayPrefix == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if p == gatewayPrefix || strings.HasPrefix(p, gatewayPrefix+"/") {
			np := strings.TrimPrefix(p, gatewayPrefix)
			if np == "" {
				np = "/"
			}
			nr := r.Clone(context.WithValue(r.Context(), gwCtxKey{}, gatewayPrefix))
			nr.URL.Path = np
			r = nr
		}
		next.ServeHTTP(w, r)
	})
}

// prefixOf 取本次请求的网关前缀（直连访问返回空串）。
func prefixOf(r *http.Request) string {
	if v, ok := r.Context().Value(gwCtxKey{}).(string); ok {
		return v
	}
	return ""
}

// rootOf 是页面里的"根"：网关下是 /app/elderrelay/，直连是 /。
func rootOf(r *http.Request) string {
	if p := prefixOf(r); p != "" {
		return p + "/"
	}
	return "/"
}

// injectBase 给 HTML 注入 <base>。
//
// 页面里的接口地址都写成相对路径（api/status 这种），配合这个 <base>：
//
//	网关：/app/elderrelay/ + api/status → /app/elderrelay/api/status
//	直连：/                 + api/status → /api/status
//
// 这样同一份 HTML 在两种入口下都对，不用维护两套。
func injectBase(html []byte, root string) []byte {
	if root == "" {
		root = "/"
	}
	if bytes.Contains(html, []byte("<base ")) {
		return html
	}
	base := []byte(`<base href="` + root + `">`)
	if i := bytes.Index(html, []byte("<head>")); i >= 0 {
		out := make([]byte, 0, len(html)+len(base))
		out = append(out, html[:i+6]...)
		out = append(out, base...)
		out = append(out, html[i+6:]...)
		return out
	}
	// 没有 <head>（比如极简错误页）：插到最前面也能生效
	return append(base, html...)
}

// listenGatewaySocket 监听飞牛统一网关的 Unix Socket。
// 路径由 FNNAS_GATEWAY_SOCKET 给出（一般是 <target>/app.sock）。
func listenGatewaySocket(path string) (net.Listener, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	// 上次异常退出可能留了个死 socket，先删掉，否则 bind 会 EADDRINUSE
	_ = os.Remove(path)
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	// 0770：只给 fnOS 网关那个用户组用，其他进程连不进来
	_ = os.Chmod(path, 0o770)
	log.Printf("[relay] 飞牛网关 Socket 就绪：%s（前缀 %s）", path, gatewayPrefix)
	return ln, nil
}
