package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 多边形围栏：建出来 → 出现在 status 里 → 判定正确 → 事件里的距离是离边界的距离
func TestPolygonFenceEndToEnd(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	s.mu.Lock()
	tok := s.cfg.Devices[0].Token
	s.cfg.OfflineMinutes = 90
	s.mu.Unlock()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/fence", s.handleFence)
	mux.HandleFunc("/api/status", s.handleStatus)
	mux.HandleFunc("/api/report", s.handleReport)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	// 一个 0.005° 见方的方框（约 550 x 450 米）
	body := `{"name":"镇上","shape":"polygon","enabled":true,
		"lat":39.9,"lon":116.394,
		"points":[[39.898,116.392],[39.898,116.396],[39.902,116.396],[39.902,116.392]]}`

	resp, err := http.Post(srv.URL+"/api/fence?k="+tok, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("建围栏失败 %d %s", resp.StatusCode, raw)
	}

	// status 里要带 points，否则家人端画不出来
	resp, err = http.Get(srv.URL + "/api/status?k=" + tok)
	if err != nil {
		t.Fatal(err)
	}
	var st struct {
		Devices []struct {
			Fences []struct {
				Name   string       `json:"name"`
				Shape  string       `json:"shape"`
				Points [][2]float64 `json:"points"`
			} `json:"fences"`
		} `json:"devices"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&st)
	resp.Body.Close()

	if len(st.Devices) != 1 || len(st.Devices[0].Fences) != 1 {
		t.Fatalf("status 里围栏数量不对：%+v", st)
	}
	f := st.Devices[0].Fences[0]
	if f.Shape != "polygon" || len(f.Points) != 4 {
		t.Fatalf("status 没带拐点：shape=%s points=%d", f.Shape, len(f.Points))
	}

	// 框内（中心）→ 在里；框外（北边 1km）→ 在外
	post := func(lat, lon float64) {
		rep := map[string]interface{}{
			"battery": 80, "charging": false,
			"pos": map[string]interface{}{"t": 1000, "lat": lat, "lon": lon, "acc": 10},
		}
		b, _ := json.Marshal(rep)
		r2, err := http.Post(srv.URL+"/api/report?k="+tok, "application/json", strings.NewReader(string(b)))
		if err != nil {
			t.Fatal(err)
		}
		r2.Body.Close()
	}

	post(39.9, 116.394) // 第一次：只建基线
	post(39.91, 116.394) // 出去（约 1.1km 北）
	post(39.91, 116.394) // 连续两点，才报

	s.mu.RLock()
	var outs []Event
	for _, e := range s.events {
		if e.Kind == "fence_out" {
			outs = append(outs, e)
		}
	}
	s.mu.RUnlock()

	if len(outs) != 1 {
		t.Fatalf("应该正好有一条 fence_out，实际 %d 条", len(outs))
	}
	if outs[0].FenceName != "镇上" {
		t.Fatalf("围栏名不对：%s", outs[0].FenceName)
	}
	// 离南边界约 39.91-39.902 ≈ 890 米，不该是离中心的 1.1km
	if outs[0].Dist < 700 || outs[0].Dist > 1000 {
		t.Fatalf("多边形事件的距离应接近边界距离（约 890 米），实际 %.0f", outs[0].Dist)
	}
}
