package main

import (
	"bufio"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/skip2/go-qrcode"
)

// ===== 通用 =====

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]interface{}{"ok": false, "error": msg})
}

func tokenOf(r *http.Request) string {
	if k := r.URL.Query().Get("k"); k != "" {
		return k
	}
	return r.Header.Get("X-Token")
}

// ===== 鉴权 =====

// deviceByToken 按令牌找设备。每个老人一把令牌，令牌就是身份。
func (s *Store) deviceByToken(k string) (Device, bool) {
	if k == "" {
		return Device{}, false
	}
	for _, d := range s.cfg.Devices {
		if d.Token != "" && d.Token == k {
			return d, true
		}
	}
	return Device{}, false
}

// auth 只看"这个令牌认不认得出设备"
func (s *Store) auth(r *http.Request) bool {
	_, ok := s.deviceByToken(tokenOf(r))
	return ok
}

// baseURL 推断外部访问地址。走 Lucky 反代时靠 X-Forwarded-Proto 判断 https，
// Host 头由反代原样带过来（Lucky 默认"使用目标地址Host请求头：否"就是这个行为）。
func baseURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
		scheme = "https"
	}
	host := r.Host
	if host == "" {
		host = "127.0.0.1"
	}
	return scheme + "://" + host
}

// ===== /api/health =====

func (s *Store) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":      true,
		"version": Version,
		"time":    time.Now().UnixMilli(),
	})
}

// ===== /api/report 老人端上报 =====

func (s *Store) handleReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	dev, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		fail(w, http.StatusBadRequest, "读取请求体失败")
		return
	}

	rep := parseReport(dev.ID, body)
	s.processReport(rep)

	resp := map[string]interface{}{
		"ok":     true,
		"fences": s.fenceSnapshot(dev.ID),
		"cursor": s.currentID(),
	}
	// 有排队的命令就捎一条回去 —— 老人端不需要开端口，也不用长连接。
	//
	// 只给"实时上报"捎（live=1）。补发的历史报文也打这个地址，但老人端不看它们的响应，
	// 命令被捎过去就等于丢了：实测发一条体检，队列一直 pending=1 却永远不执行。
	live := r.URL.Query().Get("live")
	if c := s.takeCmdIfLive(dev.ID, live != "0"); c != nil {
		resp["cmd"] = map[string]interface{}{
			"id":  c.ID,
			"cmd": c.Cmd,
			"arg": c.Arg,
			"at":  c.At,
		}
	}
	writeJSON(w, http.StatusOK, resp)
}

// parseReport 同时兼容两种上报格式：
//
//	A) 中继格式：{"pos":{"t","lat","lon","acc"},"battery":..,"event":"tick"}
//	B) 老人端现有格式：{"status":{"battery":..},"track":[{"t","lat","lon","acc"}]}
//
// 这样老人端第一版可以只改上报地址，不必改报文结构。
func parseReport(deviceID string, body []byte) *Report {
	rep := &Report{
		Device: deviceID,
		Time:   time.Now().UnixMilli(),
		Event:  "tick",
	}

	var raw map[string]json.RawMessage
	if json.Unmarshal(body, &raw) != nil {
		return rep
	}

	if v, ok := raw["time"]; ok {
		var t int64
		if json.Unmarshal(v, &t) == nil && t > 0 {
			rep.Time = t
		}
	}
	if v, ok := raw["version"]; ok {
		var sv string
		if json.Unmarshal(v, &sv) == nil {
			rep.Version = sv
		}
	}
	if v, ok := raw["event"]; ok {
		var sv string
		if json.Unmarshal(v, &sv) == nil && sv != "" {
			rep.Event = sv
		}
	}

	// 电量/充电/网络：先看顶层，再看 status 里
	if _, ok := raw["battery"]; ok {
		_ = json.Unmarshal(raw["battery"], &rep.Battery)
		rep.HasBattery = true
	}
	if _, ok := raw["charging"]; ok {
		_ = json.Unmarshal(raw["charging"], &rep.Charging)
		rep.HasBattery = true
	}
	if _, ok := raw["wifi"]; ok {
		_ = json.Unmarshal(raw["wifi"], &rep.Wifi)
		rep.HasNet = true
	}
	if _, ok := raw["mobile"]; ok {
		_ = json.Unmarshal(raw["mobile"], &rep.Mobile)
		rep.HasNet = true
	}
	// 网络名字/内网标记：顶层或 status 里都认
	readNetName := func(m map[string]interface{}) {
		if v, ok := m["wifiName"]; ok {
			if sv, _ := v.(string); sv != "" {
				rep.WifiName = sv
				rep.HasNetName = true
			}
		}
		if v, ok := m["intranet"]; ok {
			rep.Intranet = boolOf(v)
		}
		if v, ok := m["netFlag"]; ok {
			if sv, _ := v.(string); sv != "" {
				rep.NetFlag = sv
			}
		}
		if v, ok := m["ipv6"]; ok {
			if sv, _ := v.(string); sv != "" {
				rep.Ipv6 = sv
			}
		}
	}
	// 顶层的值是 RawMessage，单独解一遍（status 里是已解好的 map）
	func(m map[string]json.RawMessage) {
		strs := map[string]*string{
			"wifiName": &rep.WifiName,
			"netFlag":  &rep.NetFlag,
			"ipv6":     &rep.Ipv6,
		}
		for k, dst := range strs {
			if v, ok := m[k]; ok {
				var sv string
				if json.Unmarshal(v, &sv) == nil && sv != "" {
					*dst = sv
					if k == "wifiName" {
						rep.HasNetName = true
					}
				}
			}
		}
		if v, ok := m["intranet"]; ok {
			var bv bool
			if json.Unmarshal(v, &bv) == nil {
				rep.Intranet = bv
			}
		}
	}(raw)

	if st, ok := raw["status"]; ok {
		var m map[string]interface{}
		if json.Unmarshal(st, &m) == nil {
			readNetName(m)
			// 整包留着：/api/status 要按白名单透出音量、存储、内存这些
			rep.Status = m
			if _, exists := m["battery"]; exists {
				rep.Battery = intOf(m["battery"])
				rep.HasBattery = true
			}
			if _, exists := m["charging"]; exists {
				rep.Charging = boolOf(m["charging"])
			}
			if _, exists := m["wifi"]; exists {
				rep.Wifi = boolOf(m["wifi"])
				rep.HasNet = true
			}
			if _, exists := m["mobile"]; exists {
				rep.Mobile = boolOf(m["mobile"])
				rep.HasNet = true
			}
		}
	}

	// 定位点：优先 track（可能一次补报多个点），否则 pos
	var pts []Point
	// 老人端带上来的远程命令执行结果
	if v, ok := raw["cmdResult"]; ok {
		var cr CmdResult
		if json.Unmarshal(v, &cr) == nil {
			rep.CmdResult = &cr
		}
	}

	// 老人机上的快照列表：只描述，不带内容。家人端「恢复布局」要用它。
	if v, ok := raw["snaps"]; ok {
		var arr []SnapInfo
		if json.Unmarshal(v, &arr) == nil {
			if len(arr) > 20 {
				arr = arr[:20] // 快照不会这么多，防一手报文异常
			}
			rep.Snaps = arr
			rep.HasSnaps = true
		}
	}
	if v, ok := raw["interval"]; ok {
		var n int
		if json.Unmarshal(v, &n) == nil && n > 0 {
			rep.Interval = n
		}
	}

	// 电量有值就记下采样时间：报文没显式给就用整包时间，别让家人端把旧电量当新值
	if rep.HasBattery && rep.BatteryT == 0 {
		rep.BatteryT = rep.Time
	}

	if v, ok := raw["track"]; ok {
		var arr []Point
		if json.Unmarshal(v, &arr) == nil {
			for _, p := range arr {
				p.normalizeLng()
				if validPoint(p) {
					pts = append(pts, p)
				}
			}
		}
	}
	if len(pts) == 0 {
		if v, ok := raw["pos"]; ok {
			var p Point
			if err := json.Unmarshal(v, &p); err == nil {
				p.normalizeLng()
			}
			if validPoint(p) {
				pts = append(pts, p)
				rep.Pos = &p
			}
		}
	}

	sort.Slice(pts, func(i, j int) bool { return pts[i].T < pts[j].T })
	rep.Points = pts
	if len(pts) > 0 && rep.Pos == nil {
		last := pts[len(pts)-1]
		rep.Pos = &last
	}
	return rep
}

func validPoint(p Point) bool {
	if p.Lat == 0 && p.Lon == 0 {
		return false
	}
	if p.Lat < -90 || p.Lat > 90 || p.Lon < -180 || p.Lon > 180 {
		return false
	}
	if p.T <= 0 {
		p.T = time.Now().UnixMilli()
	}
	return true
}

func intOf(v interface{}) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case string:
		i, _ := strconv.Atoi(n)
		return i
	}
	return 0
}

func boolOf(v interface{}) bool {
	switch b := v.(type) {
	case bool:
		return b
	case float64:
		return b != 0
	}
	return false
}

// fenceSnapshot 返回该设备各围栏的当前状态（老人端可用它做本地加速，第一版可忽略）
func (s *Store) fenceSnapshot(deviceID string) []map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]map[string]interface{}, 0, len(s.fences))
	for _, f := range s.fences {
		if !f.Enabled {
			continue
		}
		inside := true
		if st := s.states[deviceID][f.ID]; st != nil && st.Inited {
			inside = st.Inside
		}
		out = append(out, map[string]interface{}{
			"id": f.ID, "name": f.Name,
			"inside": inside, "radius": f.Radius,
		})
	}
	return out
}

// ===== /api/events 家人端长轮询 =====

func (s *Store) handleEvents(w http.ResponseWriter, r *http.Request) {
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	wait, _ := strconv.Atoi(r.URL.Query().Get("wait"))
	if wait < 0 {
		wait = 0
	}
	// 上限压在 55 秒：nginx / Lucky 的 proxy_read_timeout 默认 60 秒，
	// 挂太久会被反代先掐断（504）。宁可多连一次，也不要每轮都撞超时。
	if wait > 55 {
		wait = 55
	}

	reply := func() {
		evs := s.eventsSinceFor(d.ID, since)
		cur := since
		for _, e := range evs {
			if e.ID > cur {
				cur = e.ID
			}
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"ok": true, "cursor": cur, "events": evs,
		})
	}

	if len(s.eventsSinceFor(d.ID, since)) > 0 || wait == 0 {
		reply()
		return
	}

	ch := s.waitChan()
	select {
	case <-ch:
		reply()
	case <-time.After(time.Duration(wait) * time.Second):
		if len(s.eventsSinceFor(d.ID, since)) > 0 {
			reply()
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case <-r.Context().Done():
		return
	}
}

// ===== /api/status =====

func (s *Store) handleStatus(w http.ResponseWriter, r *http.Request) {
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	now := time.Now().UnixMilli()
	th := int64(s.cfg.OfflineMinutes) * 60000
	if th <= 0 {
		th = 90 * 60000
	}

	last := s.lastSeen[d.ID]
	item := map[string]interface{}{
		"device":   d.ID,
		"name":     d.Name,
		"lastSeen": last,
		"online":   last > 0 && now-last <= th,
		"cursor":   s.lastID,
	}
	if rep := s.lastRep[d.ID]; rep != nil {
		item["battery"] = rep.Battery
		item["batteryT"] = rep.BatteryT
		item["charging"] = rep.Charging
		if rep.WifiName != "" {
			item["wifiName"] = rep.WifiName
		}
		if rep.Intranet {
			item["intranet"] = true
			item["netFlag"] = rep.NetFlag
			item["ipv6"] = rep.Ipv6
		}
		// 注意：这段在 RLock 区间内，不能调用会再次加锁的 helper
		if cr, ok := s.cmdResults[d.ID]; ok {
			item["cmdResult"] = cr
		}
		n := 0
		for _, c := range s.cmds {
			// 只数没下发过的：已经捎出去、只是老人端还没回结果的不该继续算"排队中"
			if c.Device == d.ID && !c.Taken {
				n++
			}
		}
		item["pendingCmds"] = n
		// 老人端状态里的"看得到就好"的字段，原样透给家人端。
		// 白名单而不是全透：status 里还有 topApps 这类体积大的东西，没必要每次都发。
		for _, key := range statusPassthrough {
			if rep.Status == nil {
				break
			}
			if v, ok := rep.Status[key]; ok {
				item[key] = v
			}
		}
		// 秒级通道是否连着：家人端据此决定提示文案，而不是一律说"最多 10 分钟"
		item["fastLink"] = s.linkN[d.ID] > 0
		if t := s.linkAt[d.ID]; t > 0 {
			item["fastLinkAt"] = t
		}
		if len(rep.Snaps) > 0 {
			item["snaps"] = rep.Snaps
		}
		if rep.Interval > 0 {
			item["intervalMin"] = rep.Interval
		}
		item["wifi"] = rep.Wifi
		item["mobile"] = rep.Mobile
		item["version"] = rep.Version
		if rep.Pos != nil {
			item["lat"] = rep.Pos.Lat
			item["lon"] = rep.Pos.Lon
			item["acc"] = rep.Pos.Acc
			item["posT"] = rep.Pos.T
		}
	}

	fences := make([]map[string]interface{}, 0, 4)
	for _, f := range s.fences {
		if f.Device != d.ID {
			continue
		}
		inside := true
		since := int64(0)
		if st := s.states[d.ID][f.ID]; st != nil && st.Inited {
			inside = st.Inside
			since = st.Since
		}
		item := map[string]interface{}{
			"id": f.ID, "name": f.Name, "inside": inside, "since": since,
			// lng 和 lon 都给：家人页历史上写的是 lng，之前这里只输出 lon，
			// 结果圆围栏算出来的坐标是 NaN，画不出来 —— 「加了围栏地图上没反应」就是它。
			"lat": f.Lat, "lon": f.Lon, "lng": f.Lon, "radius": f.Radius,
			"enabled": f.Enabled, "shape": shapeOf(f),
		}
		// 多边形把拐点也带上，否则家人端画不出来
		if f.Shape == "polygon" && len(f.Points) > 0 {
			item["points"] = f.Points
		}
		fences = append(fences, item)
	}
	item["fences"] = fences

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true, "devices": []interface{}{item},
	})
}

func shapeOf(f Fence) string {
	if f.Shape == "" {
		return "circle"
	}
	return f.Shape
}

// ===== /api/track =====

func (s *Store) handleTrack(w http.ResponseWriter, r *http.Request) {
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 5000 {
		limit = 2000
	}

	pts, err := s.readPoints(d.ID, since, limit)
	if err != nil {
		fail(w, http.StatusInternalServerError, "读取轨迹失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "points": pts})
}

func (s *Store) readPoints(device string, since int64, limit int) ([]Point, error) {
	f, err := os.Open(filepath.Join(s.dir, "points.jsonl"))
	if err != nil {
		if os.IsNotExist(err) {
			return []Point{}, nil
		}
		return nil, err
	}
	defer f.Close()

	type row struct {
		D   string  `json:"d"`
		T   int64   `json:"t"`
		Lat float64 `json:"lat"`
		Lon float64 `json:"lon"`
		Acc int     `json:"acc"`
	}

	var out []Point
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	for sc.Scan() {
		var v row
		if json.Unmarshal(sc.Bytes(), &v) != nil {
			continue
		}
		if device != "" && v.D != device {
			continue
		}
		if v.T <= since {
			continue
		}
		out = append(out, Point{T: v.T, Lat: v.Lat, Lon: v.Lon, Acc: v.Acc})
	}
	if len(out) > limit {
		out = out[len(out)-limit:]
	}
	if out == nil {
		out = []Point{}
	}
	return out, nil
}

// ===== /api/fence =====

func (s *Store) handleFence(w http.ResponseWriter, r *http.Request) {
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	switch r.Method {
	case http.MethodGet:
		s.mu.RLock()
		out := make([]Fence, 0, 4)
		for _, f := range s.fences {
			if f.Device == d.ID {
				out = append(out, f)
			}
		}
		s.mu.RUnlock()
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "fences": out})

	case http.MethodPost:
		var f Fence
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&f); err != nil {
			fail(w, http.StatusBadRequest, "围栏格式不对")
			return
		}
		if strings.TrimSpace(f.Name) == "" {
			fail(w, http.StatusBadRequest, "围栏要有名字")
			return
		}
		if f.Shape == "" {
			f.Shape = "circle"
		}
		if f.Shape == "circle" && f.Radius <= 0 {
			fail(w, http.StatusBadRequest, "圆形围栏要填半径")
			return
		}

		// 归属由令牌决定，不接受客户端传 device，避免写到别人名下
		f.Device = d.ID
		if f.ID == "" {
			f.ID = "f" + strconv.FormatInt(time.Now().UnixNano()/1e6, 36)
		}

		s.mu.Lock()
		replaced := false
		for i := range s.fences {
			if s.fences[i].ID == f.ID && s.fences[i].Device == d.ID {
				s.fences[i] = f
				replaced = true
				break
			}
		}
		if !replaced {
			s.fences = append(s.fences, f)
		}
		// 改了围栏就重置该围栏的状态基线，避免拿旧基线误报
		if m := s.states[d.ID]; m != nil {
			delete(m, f.ID)
		}
		s.markDirty()
		s.mu.Unlock()

		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "fence": f})

	case http.MethodDelete:
		id := r.URL.Query().Get("id")
		if id == "" {
			fail(w, http.StatusBadRequest, "缺少 id")
			return
		}
		s.mu.Lock()
		kept := s.fences[:0]
		for _, f := range s.fences {
			if f.ID != id || f.Device != d.ID {
				kept = append(kept, f)
			}
		}
		s.fences = kept
		if m := s.states[d.ID]; m != nil {
			delete(m, id)
		}
		s.markDirty()
		s.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})

	default:
		fail(w, http.StatusMethodNotAllowed, "不支持的方法")
	}
}

// ===== /api/config（只读，便于家人端拿到围栏与设备信息） =====

func (s *Store) handleConfig(w http.ResponseWriter, r *http.Request) {
	if !s.auth(r) {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}
	s.mu.RLock()
	defer s.mu.RUnlock()

	devs := make([]map[string]string, 0, len(s.cfg.Devices))
	for _, d := range s.cfg.Devices {
		devs = append(devs, map[string]string{"id": d.ID, "name": d.Name})
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":              true,
		"version":         Version,
		"devices":         devs,
		"offline_minutes": s.cfg.OfflineMinutes,
		"keep_days":       s.cfg.KeepDays,
	})
}

// ===== /api/qr 二维码 =====
//
// t=family（默认）：家人端扫码用的配置串
// t=elder        ：老人端直接扫码填的上报地址

func (s *Store) handleQR(w http.ResponseWriter, r *http.Request) {
	dev, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	typ := r.URL.Query().Get("t")
	if typ == "" {
		typ = "family"
	}

	base, from := s.qrBase(r)
	if base == "" {
		// 网关进来且没配对外地址：这里拼任何东西都是错的，明确报错比给个废码强
		fail(w, http.StatusBadRequest,
			"还没设置对外地址（"+from+"）。在设备页「二维码地址」里填一个域名再扫码")
		return
	}
	var content string
	switch typ {
	case "elder":
		content = base + "/api/report?k=" + dev.Token
	default:
		// 不带 d（设备 id）了：家人端根本不用它，但它会让二维码多出几十个字符，
		// 模块变密、更难扫。手机上那张图只有 320px 宽，密一点就废了。
		content = "elderguard://relay?base=" + url.QueryEscape(base) +
			"&k=" + dev.Token + "&n=" + url.QueryEscape(dev.Name)
	}

	png, err := qrcode.Encode(content, qrcode.Medium, 900)
	if err != nil {
		fail(w, http.StatusInternalServerError, "二维码生成失败")
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(png)
}

// ===== /api/token 重置令牌 =====

func (s *Store) handleTokenReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	dev, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	s.mu.Lock()
	var t string
	for i := range s.cfg.Devices {
		if s.cfg.Devices[i].ID == dev.ID {
			s.cfg.Devices[i].Token = randToken(6)
			t = s.cfg.Devices[i].Token
			break
		}
	}
	s.saveConfig()
	s.mu.Unlock()

	log.Printf("[token] 设备 %s 的令牌已重新生成", dev.ID)
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "token": t})
}

// ===== /api/device 设备管理（新增 / 改名 / 删除） =====
//
// 和首页一样不做令牌校验：能打开首页的人本来就看得到所有令牌。
// 想更严一点，就在 Lucky 上给这个域名加一层访问密码。
func (s *Store) handleDevice(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		var body struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body)

		if body.ID != "" {
			if !s.renameDevice(body.ID, body.Name) {
				fail(w, http.StatusNotFound, "没有这个设备")
				return
			}
			writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})
			return
		}

		d := s.addDevice(body.Name)
		log.Printf("[device] 新增老人 %s（%s）", d.Name, d.ID)
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "device": d})

	case http.MethodDelete:
		id := r.URL.Query().Get("id")
		if id == "" {
			fail(w, http.StatusBadRequest, "缺少 id")
			return
		}
		if !s.removeDevice(id) {
			fail(w, http.StatusNotFound, "没有这个设备")
			return
		}
		log.Printf("[device] 已删除老人 %s", id)
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})

	default:
		fail(w, http.StatusMethodNotAllowed, "不支持的方法")
	}
}

// statusPassthrough 是 /api/status 原样带出去的老人端状态字段。
// 都是 "读一次就能拿到" 的轻量值（音量/存储/内存/温度/开机时长），
// 中继只做搬运，不做解释 —— 怎么显示交给家人页。
var statusPassthrough = []string{
	"ringVolume", "ringMax", "muted", "volMusic", "volMusicMax", "ringerMode",
	"storageFreeMB", "memFreeMB", "memTotalMB", "memLow", "uptimeMin", "battTemp",
	"screenOn", "locked", "lockTask", "locateOn", "trackPoints",
}
