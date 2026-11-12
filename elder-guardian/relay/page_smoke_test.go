package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 多老人页面必须能渲染，且不同令牌只能看自己那一页
func TestPagesRender(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	// 加第二个老人，验证多设备
	s.mu.Lock()
	s.cfg.Devices = append(s.cfg.Devices, Device{ID: "elder2", Name: "外婆", Token: randToken(6)})
	s.saveConfig()
	tok1 := s.cfg.Devices[0].Token
	tok2 := s.cfg.Devices[1].Token
	s.mu.Unlock()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/qr", s.handleQR)
	mux.HandleFunc("/d/", s.handleDevicePage)
	mux.HandleFunc("/", s.handleIndex)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	get := func(u string) (int, string) {
		resp, err := http.Get(srv.URL + u)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}

	if code, body := get("/"); code != 200 || !strings.Contains(body, "守护中继") {
		t.Fatalf("首页异常 code=%d", code)
	}

	if code, body := get("/d/elder1?k=" + tok1); code != 200 || !strings.Contains(body, "家人端") {
		t.Fatalf("设备页异常 code=%d", code)
	}

	if code, body := get("/d/elder2?k=" + tok2); code != 200 || !strings.Contains(body, "外婆") {
		t.Fatalf("第二个设备页异常 code=%d", code)
	}

	// 拿 1 号令牌看 2 号页面，必须被拒
	if code, _ := get("/d/elder2?k=" + tok1); code != 403 {
		t.Fatalf("越权访问应为 403，实际 %d", code)
	}

	// 二维码
	resp, err := http.Get(srv.URL + "/api/qr?t=family&k=" + tok1)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/png" {
		t.Fatalf("二维码异常 code=%d type=%s", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
}
