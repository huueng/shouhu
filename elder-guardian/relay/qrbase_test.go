package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 二维码里写哪个地址：配置优先；网关进来又没配置时宁可空着。
func TestQrBase(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	lan := httptest.NewRequest("GET", "http://192.168.1.10:8898/d/elder1", nil)
	base, from := s.qrBase(lan)
	if base != "http://192.168.1.10:8898" || from != "request" {
		t.Fatalf("普通访问应回请求地址，实际 %q / %q", base, from)
	}

	// 配了对外地址就一律用它 —— 不管从哪个入口打开
	s.mu.Lock()
	s.cfg.PublicBase = "https://shouhu.example.com:812/"
	s.mu.Unlock()
	base, from = s.qrBase(lan)
	if base != "https://shouhu.example.com:812" || from != "config" {
		t.Fatalf("配置没生效（应去掉尾斜杠）：%q / %q", base, from)
	}

	// 网关进来：Host 是飞牛域名，中继的接口不在那个路径上 → 不能拿它拼码
	s.mu.Lock()
	s.cfg.PublicBase = ""
	s.mu.Unlock()
	gw := lan.WithContext(context.WithValue(lan.Context(), gwCtxKey{}, "/app/elderrelay"))
	if base, from = s.qrBase(gw); base != "" || from != "gateway" {
		t.Fatalf("网关下没配置时应返回空，实际 %q / %q", base, from)
	}
}

func TestLooksPublic(t *testing.T) {
	cases := map[string]bool{
		"https://shouhu.example.com:812": true,
		"http://8.8.8.8:8898":            true,
		"http://192.168.1.10:8898":        false,
		"http://10.0.0.5:8898":           false,
		"http://172.16.3.3:8898":         false,
		"http://127.0.0.1:8898":          false,
		"http://[2408:8220::1]:8898":     true,
		"http://[fe80::1]:8898":          false,
	}
	for u, want := range cases {
		if got := looksPublic(u); got != want {
			t.Errorf("%s -> %v，应为 %v", u, got, want)
		}
	}
}

// 网关下又没配对外地址时，页面要给提示、并且不出二维码（出了也是废的）。
func TestDevicePageHidesQRWithoutBase(t *testing.T) {
	oldPrefix := gatewayPrefix
	gatewayPrefix = "/app/elderrelay"
	defer func() { gatewayPrefix = oldPrefix }()

	s, _ := newStore(t.TempDir())
	defer s.Close()

	s.mu.RLock()
	tok := s.cfg.Devices[0].Token
	s.mu.RUnlock()

	mux := http.NewServeMux()
	mux.HandleFunc("/d/", s.handleDevicePage)
	mux.HandleFunc("/api/qr", s.handleQR)
	h := stripGatewayPrefix(mux)

	get := func(path string) string {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", path, nil))
		return rec.Body.String()
	}

	body := get("/app/elderrelay/d/elder1?k=" + tok)
	if !strings.Contains(body, "二维码地址") {
		t.Error("设备页没有「二维码地址」这一块")
	}
	if strings.Contains(body, `class="qr"`) {
		t.Error("不知道对外地址时不该出二维码")
	}
	if !strings.Contains(body, "填一个能从外网访问的地址") {
		t.Error("缺少「怎么填」的提示")
	}

	// 配上之后就该有码
	s.mu.Lock()
	s.cfg.PublicBase = "https://shouhu.example.com:812"
	s.mu.Unlock()
	body = get("/app/elderrelay/d/elder1?k=" + tok)
	if !strings.Contains(body, `class="qr"`) {
		t.Error("配了对外地址却没出二维码")
	}
	if !strings.Contains(body, "外网可访问") {
		t.Error("没标出「外网可访问」")
	}
}
