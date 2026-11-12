package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 纬度 1 度约 111320 米，所以 +0.009 度 ≈ 1000 米
const (
	cLat = 39.9006
	cLon = 116.3944
)

func TestParseOldFormat(t *testing.T) {
	body := []byte(`{"device":"Redmi 9A","version":"2.0","time":1000,
	  "status":{"battery":76,"charging":false,"wifi":true,"mobile":false},
	  "track":[{"t":1000,"lat":39.9006,"lon":116.3944,"acc":20}]}`)

	rep := parseReport("elder1", body)

	if rep.Battery != 76 {
		t.Errorf("电量应解析为 76，实际 %d", rep.Battery)
	}
	if !rep.Wifi {
		t.Error("wifi 应为 true")
	}
	if rep.Charging {
		t.Error("charging 应为 false")
	}
	if rep.Time != 1000 {
		t.Errorf("时间应为 1000，实际 %d", rep.Time)
	}
	if len(rep.Points) != 1 {
		t.Fatalf("应解析出 1 个点，实际 %d", len(rep.Points))
	}
	if rep.Points[0].Acc != 20 {
		t.Errorf("精度应为 20，实际 %d", rep.Points[0].Acc)
	}
}

func TestClassifyCircle(t *testing.T) {
	f := &Fence{Lat: cLat, Lon: cLon, Radius: 500, Shape: "circle", Enabled: true}

	cases := []struct {
		name string
		p    Point
		want verdict
	}{
		{"正中，精度好", Point{Lat: cLat, Lon: cLon, Acc: 20}, vInside},
		{"边缘内 100m，精度好", Point{Lat: cLat + 0.0036, Lon: cLon, Acc: 20}, vInside},
		{"外面 1000m，精度好", Point{Lat: cLat + 0.009, Lon: cLon, Acc: 20}, vOutside},
		{"外面 1000m，精度 900（太差）", Point{Lat: cLat + 0.009, Lon: cLon, Acc: 900}, vUncertain},
		{"外面 300m，精度 400（误差圆压边）", Point{Lat: cLat + 0.0027, Lon: cLon, Acc: 400}, vUncertain},
	}

	for _, c := range cases {
		got, _ := classify(f, &c.p)
		if got != c.want {
			t.Errorf("%s: 期望 %v，实际 %v", c.name, c.want, got)
		}
	}
}

func TestClassifyPolygon(t *testing.T) {
	// 一个 400m 见方的方框，中心 cLat/cLon
	d := 0.0018 // ≈ 200m
	f := &Fence{
		Shape:   "polygon",
		Enabled: true,
		Points: [][2]float64{
			{cLat - d, cLon - d}, {cLat - d, cLon + d},
			{cLat + d, cLon + d}, {cLat + d, cLon - d},
		},
	}
	if v, _ := classify(f, &Point{Lat: cLat, Lon: cLon, Acc: 20}); v != vInside {
		t.Errorf("方框中心应判在内，实际 %v", v)
	}
	if v, _ := classify(f, &Point{Lat: cLat + d*3, Lon: cLon, Acc: 20}); v != vOutside {
		t.Errorf("方框外应判在外，实际 %v", v)
	}
}

// 核心行为：基线不报警、单点不报警、连续两点才报、回围栏也报
func TestFenceStateMachine(t *testing.T) {
	s, err := newStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.fences = []Fence{{
		ID: "home", Device: "elder1", Name: "家", Lat: cLat, Lon: cLon,
		Radius: 500, Enabled: true, Shape: "circle",
	}}

	in := func(ts int64) *Report {
		return &Report{Device: "elder1", Time: ts,
			Points: []Point{{T: ts, Lat: cLat, Lon: cLon, Acc: 20}}}
	}
	out := func(ts int64) *Report {
		return &Report{Device: "elder1", Time: ts,
			Points: []Point{{T: ts, Lat: cLat + 0.009, Lon: cLon, Acc: 20}}}
	}

	// 1. 第一个点在围栏内：只建基线
	s.processReport(in(1000))
	if n := len(s.eventsSince(0)); n != 0 {
		t.Fatalf("建立基线不应产生事件，实际 %d 条", n)
	}

	// 2. 第一个在外点：单点漂移，不报
	s.processReport(out(2000))
	if n := len(s.eventsSince(0)); n != 0 {
		t.Fatalf("单点在外不应报警，实际 %d 条", n)
	}

	// 3. 第二个在外点：确认越界
	s.processReport(out(3000))
	evs := s.eventsSince(0)
	if len(evs) != 1 || evs[0].Kind != "fence_out" {
		t.Fatalf("连续两点在外应报 fence_out，实际 %+v", evs)
	}
	if evs[0].FenceName != "家" {
		t.Errorf("事件应带围栏名，实际 %q", evs[0].FenceName)
	}
	if evs[0].Dist < 900 || evs[0].Dist > 1100 {
		t.Errorf("距离应约 1000 米，实际 %.0f", evs[0].Dist)
	}

	// 4. 回到围栏内：要报 fence_in（家人得知道人回来了）
	s.processReport(in(4000))
	evs = s.eventsSince(0)
	if len(evs) != 2 || evs[1].Kind != "fence_in" {
		t.Fatalf("回到围栏内应报 fence_in，实际 %+v", evs)
	}

	// 5. 稳定在内：不重复报
	s.processReport(in(5000))
	if n := len(s.eventsSince(0)); n != 2 {
		t.Fatalf("稳定状态不应重复报警，实际 %d 条", n)
	}
}

// 精度不足时必须闭嘴：这是防误报的关键
func TestLowAccuracySilent(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()
	s.fences = []Fence{{ID: "home", Device: "elder1", Name: "家", Lat: cLat, Lon: cLon, Radius: 500, Enabled: true, Shape: "circle"}}

	s.processReport(&Report{Device: "elder1", Time: 1000,
		Points: []Point{{T: 1000, Lat: cLat, Lon: cLon, Acc: 20}}})

	for i := int64(0); i < 5; i++ {
		s.processReport(&Report{Device: "elder1", Time: 2000 + i,
			Points: []Point{{T: 2000 + i, Lat: cLat + 0.009, Lon: cLon, Acc: 900}}})
	}
	if n := len(s.eventsSince(0)); n != 0 {
		t.Fatalf("精度 900 米时不应报警，实际 %d 条", n)
	}
}

// 一次补报多个点（出去又回来）也要正确识别
func TestBatchPoints(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()
	s.fences = []Fence{{ID: "home", Device: "elder1", Name: "家", Lat: cLat, Lon: cLon, Radius: 500, Enabled: true, Shape: "circle"}}

	s.processReport(&Report{Device: "elder1", Time: 1000,
		Points: []Point{{T: 1000, Lat: cLat, Lon: cLon, Acc: 20}}})

	// 一批里包含：外、外、内
	s.processReport(&Report{Device: "elder1", Time: 5000, Points: []Point{
		{T: 2000, Lat: cLat + 0.009, Lon: cLon, Acc: 20},
		{T: 3000, Lat: cLat + 0.009, Lon: cLon, Acc: 20},
		{T: 4000, Lat: cLat, Lon: cLon, Acc: 20},
	}})

	evs := s.eventsSince(0)
	if len(evs) != 2 {
		t.Fatalf("应产生 出去+回来 两条事件，实际 %d 条: %+v", len(evs), evs)
	}
	if evs[0].Kind != "fence_out" || evs[1].Kind != "fence_in" {
		t.Fatalf("事件顺序不对: %+v", evs)
	}
}

// 完整 HTTP 流程，同时验证能解析老人端现有的 status/track 报文
func TestHTTPFlow(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()
	mux := http.NewServeMux()
	mux.HandleFunc("/api/report", s.handleReport)
	mux.HandleFunc("/api/events", s.handleEvents)
	mux.HandleFunc("/api/status", s.handleStatus)
	mux.HandleFunc("/api/fence", s.handleFence)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	ft := s.cfg.Devices[0].Token
	dt := ft

	// 建围栏
	body := `{"name":"家","lat":39.9006,"lon":116.3944,"radius":500,"enabled":true}`
	resp, err := http.Post(srv.URL+"/api/fence?k="+ft, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("建围栏失败 %d", resp.StatusCode)
	}
	resp.Body.Close()

	// 令牌错误要被拒
	resp, _ = http.Post(srv.URL+"/api/report?k=wrong", "application/json", strings.NewReader("{}"))
	if resp.StatusCode != 401 {
		t.Fatalf("错误令牌应返回 401，实际 %d", resp.StatusCode)
	}
	resp.Body.Close()

	// 用老人端现有报文格式上报（status + track）
	old := `{"device":"Redmi 9A","version":"2.0","time":1000,
	  "status":{"battery":76,"charging":false,"wifi":true,"mobile":false},
	  "track":[{"t":1000,"lat":39.9006,"lon":116.3944,"acc":20}]}`
	resp, _ = http.Post(srv.URL+"/api/report?k="+dt, "application/json", strings.NewReader(old))
	if resp.StatusCode != 200 {
		t.Fatalf("上报失败 %d", resp.StatusCode)
	}
	resp.Body.Close()

	// 无事件时 wait=0 应立即返回空列表
	resp, _ = http.Get(srv.URL + "/api/events?since=0&wait=0&k=" + ft)
	var er struct {
		Cursor int64   `json:"cursor"`
		Events []Event `json:"events"`
	}
	json.NewDecoder(resp.Body).Decode(&er)
	resp.Body.Close()
	if len(er.Events) != 0 {
		t.Fatalf("首个点不应有事件，实际 %+v", er.Events)
	}

	// 连续两个在外点
	out := `{"track":[{"t":2000,"lat":39.9096,"lon":116.3944,"acc":20}]}`
	http.Post(srv.URL+"/api/report?k="+dt, "application/json", strings.NewReader(out))
	resp, _ = http.Post(srv.URL+"/api/report?k="+dt, "application/json", strings.NewReader(
		`{"track":[{"t":3000,"lat":39.9096,"lon":116.3944,"acc":20}]}`))
	resp.Body.Close()

	resp, _ = http.Get(srv.URL + "/api/events?since=0&wait=0&k=" + ft)
	json.NewDecoder(resp.Body).Decode(&er)
	resp.Body.Close()
	if len(er.Events) != 1 || er.Events[0].Kind != "fence_out" {
		t.Fatalf("期望 1 条 fence_out，实际 %+v", er.Events)
	}

	// status 应能反映电量与围栏状态
	resp, _ = http.Get(srv.URL + "/api/status?k=" + ft)
	var st struct {
		Devices []map[string]interface{} `json:"devices"`
	}
	json.NewDecoder(resp.Body).Decode(&st)
	resp.Body.Close()
	if len(st.Devices) != 1 {
		t.Fatalf("应返回 1 个设备")
	}
	if b, _ := st.Devices[0]["battery"].(float64); int(b) != 76 {
		t.Errorf("电量应为 76，实际 %v", st.Devices[0]["battery"])
	}
}

// 改围栏后状态基线要重置，不能拿旧基线误报
func TestFenceResetOnEdit(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()
	s.fences = []Fence{{ID: "home", Device: "elder1", Name: "家", Lat: cLat, Lon: cLon, Radius: 500, Enabled: true, Shape: "circle"}}
	s.processReport(&Report{Device: "elder1", Time: 1000,
		Points: []Point{{T: 1000, Lat: cLat, Lon: cLon, Acc: 20}}})

	s.mu.Lock()
	delete(s.states["elder1"], "home")
	s.mu.Unlock()

	// 重置后第一个在外点仍然不该报警
	s.processReport(&Report{Device: "elder1", Time: 2000,
		Points: []Point{{T: 2000, Lat: cLat + 0.009, Lon: cLon, Acc: 20}}})
	if n := len(s.eventsSince(0)); n != 0 {
		t.Fatalf("重置基线后首点不应报警，实际 %d 条", n)
	}
}

// 电量采样时间：带了电量就记这一包的时间；没带就沿用上一次的时间，
// 否则家人端会把三小时前的电量当成刚刚测的。
func TestBatteryTimeFollowsBattery(t *testing.T) {
	first := parseReport("elder1", []byte(`{"time":5000,"status":{"battery":80}}`))
	if !first.HasBattery || first.BatteryT != 5000 {
		t.Fatalf("第一包应带电量且时间为 5000，实际 has=%v t=%d", first.HasBattery, first.BatteryT)
	}

	st, err := newStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	st.processReport(first)

	second := parseReport("elder1", []byte(`{"time":9000,"track":[{"t":9000,"lat":34.07,"lon":116.04,"acc":10}]}`))
	st.processReport(second)

	got := st.lastRep["elder1"]
	if got.Battery != 80 {
		t.Errorf("电量应沿用 80，实际 %d", got.Battery)
	}
	if got.BatteryT != 5000 {
		t.Errorf("电量时间应沿用 5000，实际 %d（不能用新包时间，否则家人端会以为电量是刚测的）", got.BatteryT)
	}
}

// 远程命令的完整流转：家人端点按钮 → 入队 → 老人端上报时捎走
// → 老人端带结果回报 → status 能看到结果、队列已清空。
func TestRemoteCommandFlow(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/report", s.handleReport)
	mux.HandleFunc("/api/status", s.handleStatus)
	mux.HandleFunc("/api/cmd", s.handleCmd)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	tok := s.cfg.Devices[0].Token

	// 1) 家人端下发体检
	resp, err := http.Post(srv.URL+"/api/cmd?k="+tok, "application/json",
		strings.NewReader(`{"cmd":"health"}`))
	if err != nil {
		t.Fatal(err)
	}
	var cr struct {
		OK bool   `json:"ok"`
		ID string `json:"id"`
	}
	json.NewDecoder(resp.Body).Decode(&cr)
	resp.Body.Close()
	if !cr.OK || cr.ID == "" {
		t.Fatalf("下发命令失败：%+v", cr)
	}

	// 2) 不支持的命令要被拒
	resp, _ = http.Post(srv.URL+"/api/cmd?k="+tok, "application/json",
		strings.NewReader(`{"cmd":"rm -rf"}`))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("非法命令应返回 400，实际 %d", resp.StatusCode)
	}
	resp.Body.Close()

	// 3) 老人端上报一次：响应里应该捎带这条命令
	resp, _ = http.Post(srv.URL+"/api/report?k="+tok, "application/json",
		strings.NewReader(`{"device":"x","time":1000,"status":{"battery":80}}`))
	var rep struct {
		Cmd *struct {
			ID  string `json:"id"`
			Cmd string `json:"cmd"`
		} `json:"cmd"`
	}
	json.NewDecoder(resp.Body).Decode(&rep)
	resp.Body.Close()
	if rep.Cmd == nil || rep.Cmd.Cmd != "health" {
		t.Fatalf("上报响应里没带命令：%+v", rep.Cmd)
	}
	if rep.Cmd.ID != cr.ID {
		t.Fatalf("命令 id 不一致：期望 %s 实际 %s", cr.ID, rep.Cmd.ID)
	}

	// 4) 同一批命令不会重复下发
	resp, _ = http.Post(srv.URL+"/api/report?k="+tok, "application/json",
		strings.NewReader(`{"time":1500,"status":{"battery":79}}`))
	var rep2 struct {
		Cmd *struct {
			ID string `json:"id"`
		} `json:"cmd"`
	}
	json.NewDecoder(resp.Body).Decode(&rep2)
	resp.Body.Close()
	if rep2.Cmd != nil {
		t.Fatalf("已下发的命令不该重复给：%+v", rep2.Cmd)
	}

	// 5) 老人端带结果回报
	result := `{"time":2000,"cmdResult":{"id":"` + cr.ID + `","cmd":"health","ok":true,"msg":"9/10 正常","at":2000}}`
	resp, _ = http.Post(srv.URL+"/api/report?k="+tok, "application/json",
		strings.NewReader(result))
	resp.Body.Close()

	// 6) status 里能看到结果，队列已清空
	resp, _ = http.Get(srv.URL + "/api/status?k=" + tok)
	var st struct {
		Devices []struct {
			CmdResult   *CmdResult `json:"cmdResult"`
			PendingCmds int        `json:"pendingCmds"`
		} `json:"devices"`
	}
	json.NewDecoder(resp.Body).Decode(&st)
	resp.Body.Close()
	if len(st.Devices) != 1 {
		t.Fatalf("应返回 1 台设备，实际 %d", len(st.Devices))
	}
	d := st.Devices[0]
	if d.CmdResult == nil || !d.CmdResult.OK || d.CmdResult.Msg != "9/10 正常" {
		t.Fatalf("status 里没有命令结果：%+v", d.CmdResult)
	}
	if d.PendingCmds != 0 {
		t.Fatalf("结果回传后队列应清空，实际还剩 %d", d.PendingCmds)
	}
}

// 内网 WIFI 模式：WIFI 名字要落库；只带定位的包不能把名字冲成空。
func TestWifiNameSurvivesMerge(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	first := parseReport("elder1", []byte(`{"time":1000,"status":{"battery":80,"wifi":true,`+
		`"wifiName":"FF","intranet":true,"netFlag":"ok","ipv6":"2408:8220::1"}}`))
	s.processReport(first)

	got := s.lastRep["elder1"]
	if got.WifiName != "FF" || !got.Intranet || got.NetFlag != "ok" {
		t.Fatalf("WIFI 名/内网标记没落库：%+v", got)
	}

	second := parseReport("elder1", []byte(`{"time":2000,"track":[{"t":2000,"lat":34.07,"lon":116.04,"acc":8}]}`))
	s.processReport(second)

	got = s.lastRep["elder1"]
	if got.WifiName != "FF" {
		t.Errorf("只报定位的包不该清掉 WIFI 名，实际 %q", got.WifiName)
	}
	if got.Ipv6 != "2408:8220::1" {
		t.Errorf("IPv6 应沿用上一次，实际 %q", got.Ipv6)
	}
}

// 老人端老版本轨迹点用 "lng"，中继必须也认，否则经度全变 0。
func TestTrackAcceptsLngKey(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	// 老格式：只有 lng
	oldRep := parseReport("elder1", []byte(`{"time":1000,"track":[{"t":1000,"lat":34.1,"lng":116.2,"acc":10}]}`))
	s.processReport(oldRep)
	if len(oldRep.Points) != 1 {
		t.Fatalf("点没解析出来：%+v", oldRep.Points)
	}
	if oldRep.Points[0].Lon != 116.2 {
		t.Errorf("老格式 lng 没归一化到 Lon，实际 %v", oldRep.Points[0].Lon)
	}

	// 新格式：只有 lon
	newRep := parseReport("elder1", []byte(`{"time":2000,"track":[{"t":2000,"lat":34.2,"lon":116.3,"acc":10}]}`))
	if newRep.Points[0].Lon != 116.3 {
		t.Errorf("新格式 lon 没读到，实际 %v", newRep.Points[0].Lon)
	}

	// pos 里也要兼容
	p := parseReport("elder1", []byte(`{"time":3000,"pos":{"t":3000,"lat":34.3,"lng":116.4,"acc":10}}`))
	if p.Pos == nil || p.Pos.Lon != 116.4 {
		t.Errorf("pos 老格式没归一化：%+v", p.Pos)
	}
}

// 家人页「加围栏地图没反应」的根因：status 里只给了 lon，页面读的是 lng。
func TestStatusFenceHasLng(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	s.mu.Lock()
	tok := s.cfg.Devices[0].Token
	s.cfg.OfflineMinutes = 90
	s.mu.Unlock()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/fence", s.handleFence)
	mux.HandleFunc("/api/status", s.handleStatus)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	body := `{"name":"家","shape":"circle","enabled":true,"lat":34.07,"lon":116.394,"radius":500}`
	resp, err := http.Post(srv.URL+"/api/fence?k="+tok, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	resp, err = http.Get(srv.URL + "/api/status?k=" + tok)
	if err != nil {
		t.Fatal(err)
	}
	var st struct {
		Devices []struct {
			Fences []map[string]interface{} `json:"fences"`
		} `json:"devices"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&st)
	resp.Body.Close()

	if len(st.Devices) != 1 || len(st.Devices[0].Fences) != 1 {
		t.Fatalf("围栏没输出：%+v", st)
	}
	f := st.Devices[0].Fences[0]
	lng, ok := f["lng"].(float64)
	if !ok || lng < 100 {
		t.Fatalf("status 没给 lng（页面就画不出圆）：%+v", f)
	}
}

// 连点「健康检测」不该堆成 20 条；清空要能一次清掉。
func TestCmdDedupAndClear(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	dev := s.cfg.Devices[0].ID
	first, dup := s.pushCmd(CmdItem{ID: "c1", Device: dev, Cmd: "health"})
	if dup || first.ID != "c1" {
		t.Fatalf("第一条不该被判重复：%+v dup=%v", first, dup)
	}
	for i := 0; i < 5; i++ {
		got, d := s.pushCmd(CmdItem{ID: "x" + itoa(i), Device: dev, Cmd: "health"})
		if !d || got.ID != "c1" {
			t.Fatalf("重复命令应就地合并到 c1，实际 %+v dup=%v", got, d)
		}
	}
	if n := s.pendingCount(dev); n != 1 {
		t.Fatalf("队列里应只有 1 条，实际 %d", n)
	}

	// 参数不同就是另一条（存快照带名字）
	if _, d := s.pushCmd(CmdItem{ID: "c2", Device: dev, Cmd: "save", Arg: "换卡前"}); d {
		t.Fatal("参数不同的命令不该合并")
	}
	if n := s.pendingCount(dev); n != 2 {
		t.Fatalf("应变成 2 条，实际 %d", n)
	}
	if n := s.clearCmds(dev); n != 2 {
		t.Fatalf("应清掉 2 条，实际 %d", n)
	}
	if n := s.pendingCount(dev); n != 0 {
		t.Fatalf("清空后还有 %d 条", n)
	}
}

// 快照列表随上报落库；只报定位的包不能把它冲空
func TestSnapsSurviveMerge(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	r1 := parseReport("elder1", []byte(`{"time":1000,"status":{"battery":80},
		"interval":15,
		"snaps":[{"id":"first","name":"首次装机基线","at":900,"auto":true},
		         {"id":"s1","name":"换卡前","at":950}]}`))
	s.processReport(r1)
	if got := len(s.lastRep["elder1"].Snaps); got != 2 {
		t.Fatalf("快照列表没落库：%d", got)
	}
	if s.lastRep["elder1"].Interval != 15 {
		t.Fatalf("上报周期没落库：%d", s.lastRep["elder1"].Interval)
	}

	r2 := parseReport("elder1", []byte(`{"time":2000,"track":[{"t":2000,"lat":34.07,"lon":116.04,"acc":8}]}`))
	s.processReport(r2)
	if got := len(s.lastRep["elder1"].Snaps); got != 2 {
		t.Errorf("只报定位的包不该清掉快照列表，实际 %d 条", got)
	}
}

// 体检要显示音量/存储/温度这些，中继必须把 status 里的字段透出去；
// 只报定位的包不能把它们清空。
func TestStatusPassthrough(t *testing.T) {
	s, _ := newStore(t.TempDir())
	defer s.Close()

	s.mu.Lock()
	tok := s.cfg.Devices[0].Token
	s.cfg.OfflineMinutes = 90
	s.mu.Unlock()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/report", s.handleReport)
	mux.HandleFunc("/api/status", s.handleStatus)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	body := `{"time":1000,"status":{"battery":80,"charging":false,"wifi":true,
		"ringVolume":0,"ringMax":7,"muted":true,"volMusic":8,"volMusicMax":15,
		"storageFreeMB":2048,"memFreeMB":900,"memTotalMB":3800,"uptimeMin":4321,
		"battTemp":36.5,"screenOn":true,"locked":false,"trackPoints":12}}`
	resp, err := http.Post(srv.URL+"/api/report?k="+tok, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	get := func() map[string]interface{} {
		r2, err := http.Get(srv.URL + "/api/status?k=" + tok)
		if err != nil {
			t.Fatal(err)
		}
		defer r2.Body.Close()
		var st struct {
			Devices []map[string]interface{} `json:"devices"`
		}
		_ = json.NewDecoder(r2.Body).Decode(&st)
		if len(st.Devices) != 1 {
			t.Fatalf("设备数不对：%+v", st)
		}
		return st.Devices[0]
	}

	d := get()
	for _, k := range []string{"ringVolume", "ringMax", "muted", "volMusic", "volMusicMax",
		"storageFreeMB", "memFreeMB", "uptimeMin", "battTemp", "trackPoints"} {
		if _, ok := d[k]; !ok {
			t.Errorf("status 漏了 %s（家人端体检就显示不出来）", k)
		}
	}

	// 只带轨迹的包：状态沿用上一次
	track := `{"time":2000,"track":[{"t":2000,"lat":34.07,"lon":116.04,"acc":8}]}`
	r3, err := http.Post(srv.URL+"/api/report?k="+tok, "application/json", strings.NewReader(track))
	if err != nil {
		t.Fatal(err)
	}
	r3.Body.Close()

	d = get()
	if _, ok := d["volMusic"]; !ok {
		t.Error("只报定位的包把状态清空了")
	}
}
