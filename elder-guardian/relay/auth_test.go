package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 访问密码：设了之后页面要认证，接口不受影响；改密码要先通过旧密码。
func TestAccessPassword(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	s.mu.Lock()
	tok := s.cfg.Devices[0].Token
	s.mu.Unlock()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/password", s.handlePassword)
	mux.HandleFunc("/api/qr", s.guard(s.handleQR))
	mux.HandleFunc("/d/", s.guard(s.handleDevicePage))
	mux.HandleFunc("/", s.guard(s.handleIndex))
	mux.HandleFunc("/api/status", s.handleStatus)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	call := func(method, path, user, pass string) (int, string) {
		req, err := http.NewRequest(method, srv.URL+path, nil)
		if err != nil {
			t.Fatal(err)
		}
		if user != "" || pass != "" {
			req.SetBasicAuth(user, pass)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}

	// 没设密码：首页直接开
	if code, _ := call("GET", "/", "", ""); code != 200 {
		t.Fatalf("无密码时首页应 200，实际 %d", code)
	}

	// 设密码（此时无密码，允许直接设）
	if code, body := call("POST", "/api/password?p=abc123", "", ""); code != 200 {
		t.Fatalf("设密码失败 code=%d body=%s", code, body)
	}

	// 不带认证 → 401
	if code, _ := call("GET", "/", "", ""); code != 401 {
		t.Fatalf("设密码后应 401，实际 %d", code)
	}
	// 密码错 → 401
	if code, _ := call("GET", "/", "x", "wrong"); code != 401 {
		t.Fatalf("密码错应 401，实际 %d", code)
	}
	// 用户名随便填 + 密码对 → 200
	if code, _ := call("GET", "/", "any", "abc123"); code != 200 {
		t.Fatalf("密码对应 200，实际 %d", code)
	}
	// 设备页、二维码同样受保护
	if code, _ := call("GET", "/api/qr?t=family&k="+tok, "", ""); code != 401 {
		t.Fatalf("二维码应受保护，实际 %d", code)
	}
	if code, _ := call("GET", "/api/qr?t=family&k="+tok, "", "abc123"); code != 200 {
		t.Fatalf("带密码后二维码应 200，实际 %d", code)
	}

	// 老人端接口不受影响（走 ?k= 令牌）
	if code, body := call("GET", "/api/status?k="+tok, "", ""); code != 200 || !strings.Contains(body, `"ok":true`) {
		t.Fatalf("接口不应被密码挡住 code=%d", code)
	}

	// 改密码必须带旧密码
	if code, _ := call("POST", "/api/password?p=new999", "", ""); code != 401 {
		t.Fatalf("无旧密码改密码应 401，实际 %d", code)
	}
	if code, _ := call("POST", "/api/password?p=new999", "", "abc123"); code != 200 {
		t.Fatalf("带旧密码改密码应 200，实际 %d", code)
	}
	if code, _ := call("GET", "/", "", "new999"); code != 200 {
		t.Fatalf("新密码应生效，实际 %d", code)
	}

	// 清空密码 → 回到不需要认证
	if code, _ := call("POST", "/api/password?p=", "", "new999"); code != 200 {
		t.Fatalf("清空密码失败 code=%d", code)
	}
	if code, _ := call("GET", "/", "", ""); code != 200 {
		t.Fatalf("清空后首页应 200，实际 %d", code)
	}
}
