package main

import (
	"net"
	"net/http"
	"net/url"
	"strings"
)

// 二维码里到底该写哪个地址？
//
// 同一个中继有三种打开方式，Host 完全不一样：
//
//	飞牛网关   https://<飞牛域名>/app/elderrelay   ← 中继的接口不在这个路径上，码扫了必然 404
//	局域网     http://192.168.1.10:8898            ← 出门就废
//	DDNS 域名  https://shouhu.xxx.com:812          ← 只有这个"到哪都能用"
//
// 以前直接拿 r.Host 拼二维码，于是"扫出来能不能用"取决于你当时怎么打开的：
// 从飞牛图标进、从局域网 IP 进，扫出来的都是废地址，而页面上又没有任何提示，
// 非常误导人。
//
// 地址应该是部署时定下来的一份配置（public_base），不是访问方式的副产品。
// 没配的时候：网关进来不给码（给了也是错的），局域网进来给码但明说只能在家用。

// qrBase 返回（地址，来源）。
// 地址为空表示"现在不知道对外地址"，调用方不要生成二维码。
func (s *Store) qrBase(r *http.Request) (string, string) {
	s.mu.RLock()
	pb := strings.TrimSpace(s.cfg.PublicBase)
	s.mu.RUnlock()
	if pb != "" {
		return strings.TrimRight(pb, "/"), "config"
	}
	if prefixOf(r) != "" {
		// 飞牛网关：Host 是飞牛域名，而中继的接口挂在 /app/elderrelay 下，
		// 拿 Host 拼出来的 https://<飞牛域名>/api/report 是打不通的。
		return "", "gateway"
	}
	return baseURL(r), "request"
}

// looksPublic 粗略判断一个地址是否"不在同一个局域网也能访问"。
// 只用来决定提示文案（域名 / 公网 IP 算公网），不参与任何权限判断。
func looksPublic(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return false
	}
	ip := net.ParseIP(u.Hostname())
	if ip == nil {
		return true // 域名
	}
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
		return false
	}
	return true
}

// handlePublicBase 保存"对外地址"，二维码和上报地址都用它。
func (s *Store) handlePublicBase(w http.ResponseWriter, r *http.Request) {
	if !s.basicOK(r) {
		fail(w, http.StatusUnauthorized, "需要访问密码")
		return
	}
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	u := strings.TrimSpace(r.URL.Query().Get("u"))
	if u != "" {
		if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
			fail(w, http.StatusBadRequest, "地址要带 http:// 或 https://")
			return
		}
		if pu, err := url.Parse(u); err != nil || pu.Host == "" {
			fail(w, http.StatusBadRequest, "地址写得不对")
			return
		}
	}
	u = strings.TrimRight(u, "/")

	s.mu.Lock()
	s.cfg.PublicBase = u
	s.saveConfig()
	s.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "publicBase": u})
}
