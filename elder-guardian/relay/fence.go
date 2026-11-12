package main

import (
	"math"
	"time"
)

// ===== 距离与几何 =====

const earthR = 6371008.8 // 米

func distanceM(lat1, lon1, lat2, lon2 float64) float64 {
	p1 := lat1 * math.Pi / 180
	p2 := lat2 * math.Pi / 180
	dp := (lat2 - lat1) * math.Pi / 180
	dl := (lon2 - lon1) * math.Pi / 180
	a := math.Sin(dp/2)*math.Sin(dp/2) + math.Cos(p1)*math.Cos(p2)*math.Sin(dl/2)*math.Sin(dl/2)
	if a < 0 {
		a = 0
	}
	if a > 1 {
		a = 1
	}
	return 2 * earthR * math.Asin(math.Sqrt(a))
}

// verdict 是一次判定的结论
type verdict int

const (
	vUncertain verdict = iota // 精度不足或在边界模糊带，维持原状态
	vInside
	vOutside
)

// classify 判定点相对围栏的位置，并返回与围栏边界的距离（米）。
//
// 核心是"精度缓冲"：老人机在室内多为网络定位，精度常在 300–1000 米，
// 而围栏半径典型只有 300–500 米。若直接用中心距离比半径，必然疯狂误报。
// 所以只有当「整个误差圆都在围栏外」才判外，只有「整个误差圆都在内」才判内。
func classify(f *Fence, p *Point) (verdict, float64) {
	acc := float64(p.Acc)

	if f.Shape == "polygon" && len(f.Points) >= 3 {
		inside, edgeDist := pointInPolygon(f.Points, p.Lat, p.Lon)
		// 精度太差：即便判出来了也不可信
		if acc > edgeDist*1.5 && edgeDist < 200 {
			return vUncertain, edgeDist
		}
		if inside {
			if edgeDist > acc {
				return vInside, edgeDist
			}
			return vUncertain, edgeDist
		}
		if edgeDist > acc {
			return vOutside, edgeDist
		}
		return vUncertain, edgeDist
	}

	// 圆形
	d := distanceM(f.Lat, f.Lon, p.Lat, p.Lon)
	r := float64(f.Radius)
	if r <= 0 {
		return vUncertain, d
	}
	if acc > r*1.5 {
		return vUncertain, d - r
	}
	if d-acc > r {
		return vOutside, d - r
	}
	if d+acc < r {
		return vInside, r - d
	}
	return vUncertain, math.Abs(d - r)
}

// fenceDesc 给页面用的一句话范围描述
func fenceDesc(f Fence) string {
	if f.Shape == "polygon" && len(f.Points) >= 3 {
		return "多边形 " + itoa(len(f.Points)) + " 个点"
	}
	return itoa(f.Radius) + " 米"
}

// pointInPolygon 射线法；同时返回点到多边形边界的最短距离（米，近似）
func pointInPolygon(poly [][2]float64, lat, lon float64) (bool, float64) {
	n := len(poly)
	inside := false
	minDist := math.MaxFloat64

	for i, j := 0, n-1; i < n; j, i = i, i+1 {
		yi, xi := poly[i][0], poly[i][1]
		yj, xj := poly[j][0], poly[j][1]

		if (yi > lat) != (yj > lat) {
			xCross := (xj-xi)*(lat-yi)/(yj-yi) + xi
			if lon < xCross {
				inside = !inside
			}
		}
		if d := pointSegDist(lat, lon, yi, xi, yj, xj); d < minDist {
			minDist = d
		}
	}
	if minDist == math.MaxFloat64 {
		minDist = 0
	}
	return inside, minDist
}

// pointSegDist 点到线段距离（米）。用局部等距投影，几十公里内足够准。
func pointSegDist(lat, lon, lat1, lon1, lat2, lon2 float64) float64 {
	mPerLat := 111320.0
	mPerLon := 111320.0 * math.Cos(lat*math.Pi/180)

	px, py := (lon-lon1)*mPerLon, (lat-lat1)*mPerLat
	bx, by := (lon2-lon1)*mPerLon, (lat2-lat1)*mPerLat

	den := bx*bx + by*by
	if den == 0 {
		return math.Hypot(px, py)
	}
	t := (px*bx + py*by) / den
	if t < 0 {
		t = 0
	} else if t > 1 {
		t = 1
	}
	return math.Hypot(px-t*bx, py-t*by)
}

// ===== 上报处理 =====

// processReport 处理一次老人端上报：落点 → 逐围栏判定 → 生成事件
func (s *Store) processReport(r *Report) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// lastSeen 用"到达时间"而不是报文里的时间：
	// 老人端断网补发会把几小时前的报文按顺序重放一遍，
	// 若用报文时间，设备会被误判成失联。
	arrival := time.Now().UnixMilli()
	if arrival > s.lastSeen[r.Device] {
		s.lastSeen[r.Device] = arrival
	}
	// 与前一次上报合并：本次没带的字段沿用旧值，
	// 否则一次只报定位的上报会把电量冲成 0%，家人在 App 上看到"电量 0%"会吓一跳。
	if prev := s.lastRep[r.Device]; prev != nil {
		if !r.HasNetName && prev.WifiName != "" {
			// 只报定位的那种包不带 WIFI 名，沿用上次的，别让家人端看到空名字
			r.WifiName = prev.WifiName
			r.HasNetName = true
			if r.NetFlag == "" {
				r.NetFlag = prev.NetFlag
			}
			if r.Ipv6 == "" {
				r.Ipv6 = prev.Ipv6
			}
			r.Intranet = prev.Intranet
		}
		if !r.HasSnaps && len(prev.Snaps) > 0 {
			// 只报定位的包不带快照列表，沿用上次的，别让家人端的
			// 「恢复布局」列表突然变空
			r.Snaps = prev.Snaps
			r.HasSnaps = true
		}
		if r.Status == nil {
			// 只报定位的包不带 status，沿用上一次的全量状态，
			// 否则一次轨迹包就会把音量/存储从家人端抹掉
			r.Status = prev.Status
		}
		if r.Interval == 0 {
			r.Interval = prev.Interval
		}
		if !r.HasBattery {
			r.Battery = prev.Battery
			r.Charging = prev.Charging
			// 电量沿用旧值，采样时间也必须跟着沿用，否则家人端会以为这是刚测的
			r.BatteryT = prev.BatteryT
		}
		if !r.HasNet {
			r.Wifi = prev.Wifi
			r.Mobile = prev.Mobile
		}
		if r.Pos == nil {
			r.Pos = prev.Pos
		}
	}

	// 老人端带回来的命令结果：存下来给家人端看，并把它从队列里删掉
	if r.CmdResult != nil {
		// processReport 已持写锁，这里必须用 Locked 版本
		s.putCmdResultLocked(r.Device, *r.CmdResult)
	}

	s.lastRep[r.Device] = r
	s.markDirty()

	if len(r.Points) == 0 {
		return
	}
	if s.states[r.Device] == nil {
		s.states[r.Device] = map[string]*FenceState{}
	}
	devName := s.deviceName(r.Device)

	// 一次补报可能带多个点，逐点按时间顺序判定，"出去又回来"也能正确识别
	for i := range r.Points {
		p := r.Points[i]
		s.appendPoint(r.Device, p)
		s.judgeLocked(r.Device, devName, &p)
	}
}

// judgeLocked 对单个点做全部围栏的判定。必须持有 s.mu。
func (s *Store) judgeLocked(deviceID, devName string, p *Point) {
	at := p.T
	if at <= 0 {
		at = time.Now().UnixMilli()
	}
	if s.states[deviceID] == nil {
		s.states[deviceID] = map[string]*FenceState{}
	}

	for i := range s.fences {
		f := &s.fences[i]
		// 只判自己名下的围栏：一台中继可能管好几个老人
		if !f.Enabled || f.Device != deviceID {
			continue
		}

		st := s.states[deviceID][f.ID]
		if st == nil {
			st = &FenceState{Inside: true, Since: at}
			s.states[deviceID][f.ID] = st
		}

		v, edge := classify(f, p)
		if v == vUncertain {
			continue // 精度不足或落在模糊带，维持上一状态
		}
		// 报文里的"距离"：圆用中心距，多边形用离边界的距离（中心距对多边形没意义）
		dist := math.Round(distanceM(f.Lat, f.Lon, p.Lat, p.Lon))
		if f.Shape == "polygon" && len(f.Points) >= 3 {
			dist = math.Round(math.Abs(edge))
		}

		// 首次拿到可信点：只建立基线，不报警（避免开机就炸一条"已离开"）
		if !st.Inited {
			st.Inited = true
			st.Inside = v == vInside
			st.Since = at
			st.Pending = 0
			s.markDirty()
			continue
		}

		if v == vOutside {
			st.Pending++
			// 连续两点都在外才报，单点漂移不报
			if st.Inside && st.Pending >= 2 {
				st.Inside = false
				st.Since = at
				st.Pending = 0
				s.pushEvent(Event{
					T: at, Device: deviceID, DeviceName: devName,
					Kind: "fence_out", FenceID: f.ID, FenceName: f.Name,
					Lat: p.Lat, Lon: p.Lon,
					Dist: dist,
					Acc:  p.Acc,
					Text: "离开了「" + f.Name + "」",
				})
			}
			continue
		}

		// vInside
		st.Pending = 0
		if !st.Inside {
			st.Inside = true
			st.Since = at
			s.pushEvent(Event{
				T: at, Device: deviceID, DeviceName: devName,
				Kind: "fence_in", FenceID: f.ID, FenceName: f.Name,
				Lat: p.Lat, Lon: p.Lon,
				Dist: dist,
				Acc:  p.Acc,
				Text: "回到了「" + f.Name + "」",
			})
		}
	}
}

func (s *Store) deviceName(id string) string {
	for _, d := range s.cfg.Devices {
		if d.ID == id {
			return d.Name
		}
	}
	return id
}

// ===== 失联检测 =====

// checkOffline 由定时器调用。只在"跨过阈值的那一刻"推一次，恢复时再推一次。
func (s *Store) checkOffline() {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UnixMilli()
	th := int64(s.cfg.OfflineMinutes) * 60000
	if th <= 0 {
		th = 90 * 60000
	}
	if s.offlineSince == nil {
		s.offlineSince = map[string]int64{}
	}

	for _, d := range s.cfg.Devices {
		last := s.lastSeen[d.ID]
		if last == 0 {
			continue // 从没上报过，不判失联
		}
		down := now-last > th

		if down && s.offlineSince[d.ID] == 0 {
			s.offlineSince[d.ID] = now
			s.pushEvent(Event{
				T: now, Device: d.ID, DeviceName: d.Name,
				Kind: "offline",
				Text: "已 " + itoa(int((now-last)/60000)) + " 分钟没有上报，可能关机或断网",
			})
		} else if !down && s.offlineSince[d.ID] != 0 {
			s.offlineSince[d.ID] = 0
			s.pushEvent(Event{
				T: now, Device: d.ID, DeviceName: d.Name,
				Kind: "online",
				Text: "已恢复上报",
			})
		}
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b [16]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
