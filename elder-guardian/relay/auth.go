package main

import (
	"crypto/subtle"
	"log"
	"net/http"
	"strings"
)

// 网页访问密码。
//
// 为什么要这一层：首页能看到所有老人的令牌，等于总凭证。
// 域名虽然不公开，但只要有人拿到（转发、日志、DNS 记录泄露），
// 就能看到全部令牌、加删设备。所以页面这一侧必须能加一把锁。
//
// 边界很清楚：
//   · 人看的页面（/ 、/d/…、设备管理、令牌重置、二维码）→ 走密码
//   · 老人端 / 家人端调的接口 → 走 ?k= 设备令牌，不受影响
// 这样加上密码之后，手机上已经配好的两端不需要改任何东西。

// basicOK 判断请求里带的 Basic 认证是否正确。
// 用户名随便填（浏览器登录框必须填点什么），只比密码。
func (s *Store) basicOK(r *http.Request) bool {
	// 从飞牛网关进来的请求，fnOS 已经校验过 NAS 登录态了（还会带上 X-Trim-Userid）。
	// 在飞牛 APP 的窗口里再弹一次密码框，体验很差，而且没有增加安全性：
	// Socket 只有网关能连，网关那一步已经挡过人了。
	if prefixOf(r) != "" {
		return true
	}
	want := s.accessPassword()
	if want == "" {
		return true // 没设密码 = 不校验
	}
	_, pass, ok := r.BasicAuth()
	if !ok {
		return false
	}
	// 定长比较，避免按字符逐位猜（差异可以忽略，但这样写是免费的）
	return subtle.ConstantTimeCompare([]byte(pass), []byte(want)) == 1
}

// guard 给需要密码的页面套一层校验。没通过就返回 401 让浏览器弹登录框。
func (s *Store) guard(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.basicOK(r) {
			h(w, r)
			return
		}
		w.Header().Set("WWW-Authenticate", `Basic realm="守护中继", charset="UTF-8"`)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`<!doctype html><meta charset="utf-8">` +
			`<meta name="viewport" content="width=device-width,initial-scale=1">` +
			`<p style="font-family:system-ui;padding:56px 24px;text-align:center;color:#666;line-height:1.8">` +
			`需要访问密码。<br>用户名随便填，密码填你在 config.json 里设的 <code>access_password</code>。</p>`))
	}
}

// handlePassword 修改网页访问密码。
//
//	POST /api/password?p=新密码     已设密码时，当前请求要带对旧密码
//	POST /api/password?p=           清空密码（关闭校验）
//
// 这是唯一一个"既能设密码、又没被密码挡住"的接口，
// 所以内部自己判一次：已有密码时必须先通过认证，否则 401。
func (s *Store) handlePassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	if !s.basicOK(r) {
		w.Header().Set("WWW-Authenticate", `Basic realm="守护中继", charset="UTF-8"`)
		fail(w, http.StatusUnauthorized, "要先通过当前密码验证")
		return
	}

	p := strings.TrimSpace(r.URL.Query().Get("p"))
	if len(p) > 64 {
		fail(w, http.StatusBadRequest, "密码太长（最多 64 个字符）")
		return
	}

	s.setAccessPassword(p)
	log.Printf("[auth] 访问密码已更新（长度 %d）", len(p))
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":      true,
		"enabled": p != "",
	})
}
