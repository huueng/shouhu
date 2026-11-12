package main

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ===== 数据结构 =====

type Device struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// Token 是这个老人专属的令牌：老人端上报、家人端读取都用它。
	// 每个老人一把，互不影响——一个家人可以同时绑多个老人。
	Token string `json:"token"`
}

type Config struct {
	Port int `json:"port"`
	// Bind 是监听地址。走了反向代理（Lucky/Nginx）之后，
	// 建议改成 127.0.0.1:8898，只让本机和反代能连，不暴露到公网。
	// 留空则按 Port 监听全部接口。
	Bind string `json:"bind"`
	// 以下两个是 1.0/1.1 留下的全局令牌，升级时会被分给各设备，然后清空。
	Token       string `json:"token,omitempty"`
	FamilyToken string `json:"family_token,omitempty"`

	// AccessPassword 是网页的访问密码。留空表示不校验（现状）。
	// 设了之后，首页 / 设备页 / 设备管理这些"人看的页面"要过一次登录框，
	// 用户名随便填、密码填它。老人端和家人端走 ?k= 令牌，不受影响。
	AccessPassword string `json:"access_password,omitempty"`

	// PublicBase 是写进二维码/上报地址的"对外地址"，例如
	// https://shouhu.example.com:812。
	//
	// 为什么不直接用请求的 Host：同一个中继可能被三种方式打开 ——
	// 飞牛网关 /app/elderrelay、局域网 IP:8898、DDNS 域名。
	// 用 Host 生成的二维码，扫出来能不能用完全取决于"你当时怎么打开的"，
	// 很误导人。地址应该是部署时确定的配置。
	PublicBase string `json:"public_base,omitempty"`

	// UpdateURL 是内置升级的"更新源"：一个 JSON，说明最新版本号和下载地址。
	// 留空则管理台只显示版本、不提供一键升级。
	UpdateURL string `json:"update_url,omitempty"`

	// UpdateRepo 是"在线检查新版本"用的 GitHub 仓库（owner/repo），
	// 例如 shouhu-app/shouhu。留空则去读环境变量 ELDER_UPDATE_REPO，
	// 都没有就不查（管理台上会提示怎么配）。只提示、不自动升级。
	UpdateRepo string `json:"update_repo,omitempty"`

	Devices        []Device `json:"devices"`
	OfflineMinutes int      `json:"offline_minutes"` // 超过多久没上报判定为失联
	KeepDays       int      `json:"keep_days"`       // 轨迹保留天数
}

// Point 是一个定位点
type Point struct {
	T   int64   `json:"t"`
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
	Acc int     `json:"acc"`

	// Lng 是老人端老版本轨迹点用的键名（TrackStore 以前只写 "lng"）。
	// 只认 lon 的后果：每个轨迹点经度都是 0，家人端地图画到本初子午线、
	// 围栏判定全乱。这里两个都收，取到哪个算哪个。
	Lng float64 `json:"lng,omitempty"`
}

// normalizeLng 把老键名补到 Lon 上（Lon 有值就不动）
func (p *Point) normalizeLng() {
	if p.Lon == 0 && p.Lng != 0 {
		p.Lon = p.Lng
	}
}

// Report 是老人端上报的报文
type Report struct {
	Device   string `json:"device"`
	Time     int64  `json:"time"`
	Pos      *Point `json:"pos"`
	Battery  int    `json:"battery"`
	Charging bool   `json:"charging"`
	Wifi     bool   `json:"wifi"`
	Mobile   bool   `json:"mobile"`

	// 内网 WIFI 模式：这台机器不插卡，只在家里连 WIFI 看视频。
	// 家人端要看到「连的哪个网」，所以 WIFI 名字必须落库 ——
	// 之前只存了 wifi=true/false，家人端只能显示成「WiFi 」后面空着。
	WifiName string `json:"wifiName,omitempty"`
	Intranet bool   `json:"intranet,omitempty"`
	NetFlag  string `json:"netFlag,omitempty"` // ok / no_wifi
	Ipv6     string `json:"ipv6,omitempty"`
	Event    string `json:"event"` // tick / boot / lowbat / call / manual
	Version  string `json:"version"`

	// BatteryT 是电量的采样时刻（毫秒）。报文没带电量时沿用上一次的采样时间，
	// 否则家人端会把"三小时前的电量"当成刚刚测出来的值。
	BatteryT int64 `json:"batteryT,omitempty"`

	// Points 是本次要判定的所有定位点（按时间升序），由 HTTP 层填充，不参与 JSON。
	Points []Point `json:"-"`

	// 报文里是否真的带了这些字段。没带就不能当成"0%/没网络"，
	// 否则一次只报定位的上报会把上次的电量冲掉。不参与 JSON。
	HasBattery bool `json:"-"`
	HasNet     bool `json:"-"`
	HasNetName bool `json:"-"`

	// CmdResult 是老人端带回来的命令执行结果（没带就是 nil）
	CmdResult *CmdResult `json:"-"`

	// Snaps 是老人机上现有的配置快照（家人端要能"挑一份历史配置恢复"）。
	// 只带 id / 名字 / 时间，不带快照内容 —— 快照里有上报令牌，不能往外发。
	Snaps    []SnapInfo `json:"snaps,omitempty"`
	HasSnaps bool       `json:"-"`

	// Status 是老人端上报的整包状态（StatusReport.build 的原样）。
	// 以前只挑了几个字段解析，音量/存储/内存/温度这些直接丢了，
	// 家人端「体检」就只剩电量和网络两行。
	Status map[string]interface{} `json:"-"`

	// Interval 是老人端当前的上报周期（分钟）。家人端要据此告诉用户
	// "不挂快通道的话大概等几分钟"，写死 10 分钟会说错。
	Interval int `json:"interval,omitempty"`
}

// SnapInfo 是老人机上的一份配置快照的描述（不含内容）
type SnapInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	At   int64  `json:"at"`
	Auto bool   `json:"auto,omitempty"` // true = 首次装机自动存的那份基线
}

// Fence 是围栏。Shape 先只支持 circle，预留 polygon 字段便于以后加方框/多边形。
// Device 决定这个围栏属于哪个老人——多个老人共用一台中继时，围栏必须隔离。
type Fence struct {
	ID      string       `json:"id"`
	Device  string       `json:"device"`
	Name    string       `json:"name"`
	Lat     float64      `json:"lat"`
	Lon     float64      `json:"lon"`
	Radius  int          `json:"radius"` // 米
	Enabled bool         `json:"enabled"`
	Shape   string       `json:"shape"`            // circle | polygon
	Points  [][2]float64 `json:"points,omitempty"` // polygon 时用（[lat,lon] 有序点）
}

// FenceState 是某个设备在某个围栏里的当前状态
type FenceState struct {
	Inited   bool   `json:"inited"` // 是否已用第一个点做了初判
	Inside   bool   `json:"inside"`
	Since    int64  `json:"since"`
	Pending  int    `json:"pending"` // 连续"确定在外"的点数
	LastKind string `json:"lastKind"`
	LastPush int64  `json:"lastPush"`
}

// Event 是推给家人端的事件
type Event struct {
	ID         int64   `json:"id"`
	T          int64   `json:"t"`
	Device     string  `json:"device"`
	DeviceName string  `json:"deviceName"`
	Kind       string  `json:"kind"` // fence_out / fence_in / offline / lowbat
	FenceID    string  `json:"fenceId"`
	FenceName  string  `json:"fenceName"`
	Lat        float64 `json:"lat"`
	Lon        float64 `json:"lon"`
	Dist       float64 `json:"dist"`
	Acc        int     `json:"acc"`
	Text       string  `json:"text"`
}

// stateFile 是落盘内容
type stateFile struct {
	LastID   int64                             `json:"lastId"`
	Fences   []Fence                           `json:"fences"`
	States   map[string]map[string]*FenceState `json:"states"`
	Events   []Event                           `json:"events"`
	LastSeen map[string]int64                  `json:"lastSeen"`
	LastRep  map[string]*Report                `json:"lastRep"`

	// 远程命令队列与最近一次执行结果（见 cmd.go）
	Cmds       []CmdItem            `json:"cmds,omitempty"`
	CmdResults map[string]CmdResult `json:"cmdResults,omitempty"`
}

// Store 是全部内存状态 + 落盘
type Store struct {
	mu       sync.RWMutex
	dir      string
	cfg      Config
	fences   []Fence
	states   map[string]map[string]*FenceState
	events   []Event
	lastID   int64
	lastSeen map[string]int64
	lastRep  map[string]*Report

	cmds       []CmdItem
	cmdResults map[string]CmdResult
	points     *os.File
	notify     chan struct{}
	closed     chan struct{}
	dirty      bool

	// offlineSince 记录每个设备"开始失联"的时间，0 表示正常。不落盘，重启后重新判。
	offlineSince map[string]int64

	// linkN / linkAt 记录每个设备当前有几条 /api/wait 长轮询挂着。
	// 它决定家人端看到的是"秒级通道已连接，几秒内执行"还是"等下次上报"——
	// 之前完全没有这个概念，家人点了按钮只能盯着"最多 10 分钟"猜。
	// 不落盘：进程重启后连接本来就断了，重新挂上会再记。
	linkN  map[string]int
	linkAt map[string]int64

	// lastPointT 是每个设备已经落过盘的最新轨迹点时间。
	// 老人端断网补发时可能把同一批点重发一次，靠它挡掉，避免轨迹重影。
	// 不落盘：重启后最多重复一批，量很小。
	lastPointT map[string]int64
}

const maxEvents = 500

func newStore(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	s := &Store{
		dir:        dir,
		states:     map[string]map[string]*FenceState{},
		lastSeen:   map[string]int64{},
		lastRep:    map[string]*Report{},
		cmdResults: map[string]CmdResult{},
		linkN:      map[string]int{},
		linkAt:     map[string]int64{},
		notify:     make(chan struct{}),
		closed:     make(chan struct{}),
	}
	s.loadConfig()
	s.loadState()

	f, err := os.OpenFile(filepath.Join(dir, "points.jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	s.points = f

	go s.flushLoop()
	return s, nil
}

// ===== 配置 =====

func randToken(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "fallback-token"
	}
	return hex.EncodeToString(b)
}

func (s *Store) loadConfig() {
	p := filepath.Join(s.dir, "config.json")
	b, err := os.ReadFile(p)
	loaded := false
	if err == nil && json.Unmarshal(b, &s.cfg) == nil {
		loaded = true
	}

	if loaded {
		if s.cfg.Port == 0 {
			s.cfg.Port = 8898
		}
		if s.cfg.OfflineMinutes <= 0 {
			s.cfg.OfflineMinutes = 90
		}
		if s.cfg.KeepDays <= 0 {
			s.cfg.KeepDays = 90
		}
		if len(s.cfg.Devices) == 0 {
			s.cfg.Devices = []Device{{ID: "elder1", Name: "老人"}}
		}

		// 老配置升级：1.0/1.1 的全局令牌分给第一个设备，其余设备各自生成。
		// 不能让多个设备共用同一个令牌，否则 token 就分不清是谁了。
		for i := range s.cfg.Devices {
			d := &s.cfg.Devices[i]
			if d.ID == "" {
				d.ID = "elder" + strconv.Itoa(i+1)
			}
			if d.Name == "" {
				d.Name = "老人" + strconv.Itoa(i+1)
			}
			if d.Token == "" {
				switch {
				case i == 0 && s.cfg.Token != "":
					d.Token = s.cfg.Token
				case i == 0 && s.cfg.FamilyToken != "":
					d.Token = s.cfg.FamilyToken
				default:
					d.Token = randToken(6)
				}
			}
		}
		s.cfg.Token = ""
		s.cfg.FamilyToken = ""
		s.saveConfig()
		return
	}

	// 首次启动：生成默认配置（一个老人一把令牌）
	s.cfg = Config{
		Port:           8898,
		Bind:           ":8898",
		Devices:        []Device{{ID: "elder1", Name: "老人", Token: randToken(6)}},
		OfflineMinutes: 90,
		KeepDays:       90,
	}
	s.saveConfig()
	log.Printf("[init] 已生成配置，设备令牌 %s", s.cfg.Devices[0].Token)
}

func (s *Store) saveConfig() {
	b, _ := json.MarshalIndent(s.cfg, "", "  ")
	_ = os.WriteFile(filepath.Join(s.dir, "config.json"), b, 0o644)
}

func (s *Store) loadState() {
	b, err := os.ReadFile(filepath.Join(s.dir, "state.json"))
	if err != nil {
		return
	}
	var st stateFile
	if json.Unmarshal(b, &st) != nil {
		return
	}
	s.lastID = st.LastID
	s.fences = st.Fences
	if st.States != nil {
		s.states = st.States
	}
	if st.Events != nil {
		s.events = st.Events
	}
	if st.LastSeen != nil {
		s.lastSeen = st.LastSeen
	}
	if st.LastRep != nil {
		s.lastRep = st.LastRep
	}
	if st.Cmds != nil {
		s.cmds = st.Cmds
	}
	if st.CmdResults != nil {
		s.cmdResults = st.CmdResults
	}
}

// flushLoop 每 5 秒把脏数据落盘，避免频繁写
func (s *Store) flushLoop() {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-s.closed:
			return
		case <-t.C:
		}

		s.mu.Lock()
		if !s.dirty {
			s.mu.Unlock()
			continue
		}
		s.dirty = false
		st := stateFile{
			LastID:     s.lastID,
			Fences:     s.fences,
			States:     s.states,
			Events:     s.events,
			LastSeen:   s.lastSeen,
			LastRep:    s.lastRep,
			Cmds:       s.cmds,
			CmdResults: s.cmdResults,
		}
		s.mu.Unlock()

		b, err := json.Marshal(st)
		if err == nil {
			tmp := filepath.Join(s.dir, "state.json.tmp")
			if os.WriteFile(tmp, b, 0o644) == nil {
				_ = os.Rename(tmp, filepath.Join(s.dir, "state.json"))
			}
		}
	}
}

// Close 停止后台协程并释放文件句柄
func (s *Store) Close() {
	select {
	case <-s.closed:
	default:
		close(s.closed)
	}
	s.mu.Lock()
	if s.points != nil {
		_ = s.points.Sync()
		_ = s.points.Close()
		s.points = nil
	}
	s.mu.Unlock()
}

// markDirty 需要在持有 s.mu 时调用
func (s *Store) markDirty() {
	s.dirty = true
}

// broadcast 唤醒所有正在长轮询的客户端
func (s *Store) broadcast() {
	// 必须在持有 s.mu 时调用；用 close+replace 实现广播
	close(s.notify)
	s.notify = make(chan struct{})
}

// waitChan 取当前的通知通道（读锁内）
func (s *Store) waitChan() chan struct{} {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.notify
}

// appendPoint 追加一个轨迹点（不持有锁）
func (s *Store) appendPoint(device string, p Point) {
	if s.points == nil {
		return
	}
	// 断网补发会把没被确认的批次重发一次，这里按时间挡掉重复点
	if s.lastPointT == nil {
		s.lastPointT = map[string]int64{}
	}
	if p.T > 0 && p.T <= s.lastPointT[device] {
		return
	}
	if p.T > 0 {
		s.lastPointT[device] = p.T
	}
	b, err := json.Marshal(map[string]interface{}{
		"d": device, "t": p.T, "lat": p.Lat, "lon": p.Lon, "acc": p.Acc,
	})
	if err != nil {
		return
	}
	b = append(b, '\n')
	_, _ = s.points.Write(b)
}

// pushEvent 追加事件并广播，需要在持有 s.mu 时调用
func (s *Store) pushEvent(ev Event) {
	s.lastID++
	ev.ID = s.lastID
	s.events = append(s.events, ev)
	if len(s.events) > maxEvents {
		s.events = s.events[len(s.events)-maxEvents:]
	}
	s.markDirty()
	s.broadcast()
}

// eventsSince 取 ID 大于 since 的事件
func (s *Store) eventsSince(since int64) []Event {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Event, 0, 8)
	for _, e := range s.events {
		if e.ID > since {
			out = append(out, e)
		}
	}
	return out
}

func (s *Store) currentID() int64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastID
}

// eventsSinceFor 只取某个设备的事件——多老人共用一台中继时必须隔离，
// 否则 A 家会收到 B 家的告警。
func (s *Store) eventsSinceFor(device string, since int64) []Event {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Event, 0, 8)
	for _, e := range s.events {
		if e.ID > since && e.Device == device {
			out = append(out, e)
		}
	}
	return out
}

// ===== 设备（老人）管理 =====

// findDeviceLocked 需要持有 s.mu
func (s *Store) findDeviceLocked(id string) *Device {
	for i := range s.cfg.Devices {
		if s.cfg.Devices[i].ID == id {
			return &s.cfg.Devices[i]
		}
	}
	return nil
}

// addDevice 新增一位老人，自动生成不重复的 id 与令牌
func (s *Store) addDevice(name string) Device {
	s.mu.Lock()
	defer s.mu.Unlock()

	name = strings.TrimSpace(name)
	if name == "" {
		name = "老人" + strconv.Itoa(len(s.cfg.Devices)+1)
	}

	n := len(s.cfg.Devices) + 1
	id := "elder" + strconv.Itoa(n)
	for s.findDeviceLocked(id) != nil {
		n++
		id = "elder" + strconv.Itoa(n)
	}

	d := Device{ID: id, Name: name, Token: randToken(6)}
	s.cfg.Devices = append(s.cfg.Devices, d)
	s.saveConfig()
	s.markDirty()
	return d
}

// removeDevice 删掉一位老人，连带清掉他的围栏、状态、上报记录
func (s *Store) removeDevice(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	idx := -1
	for i := range s.cfg.Devices {
		if s.cfg.Devices[i].ID == id {
			idx = i
			break
		}
	}
	if idx < 0 {
		return false
	}

	s.cfg.Devices = append(s.cfg.Devices[:idx], s.cfg.Devices[idx+1:]...)

	kept := make([]Fence, 0, len(s.fences))
	for _, f := range s.fences {
		if f.Device != id {
			kept = append(kept, f)
		}
	}
	s.fences = kept
	delete(s.states, id)
	delete(s.lastSeen, id)
	delete(s.lastRep, id)

	s.saveConfig()
	s.markDirty()
	return true
}

// renameDevice 改名
func (s *Store) renameDevice(id, name string) bool {
	name = strings.TrimSpace(name)
	if name == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.findDeviceLocked(id)
	if d == nil {
		return false
	}
	d.Name = name
	s.saveConfig()
	s.markDirty()
	return true
}

// ===== 网页访问密码 =====

// accessPassword 返回当前密码（空 = 不校验）
func (s *Store) accessPassword() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg.AccessPassword
}

// setAccessPassword 设置或清除密码（传空字符串即关闭校验）
func (s *Store) setAccessPassword(p string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cfg.AccessPassword = strings.TrimSpace(p)
	s.saveConfig()
	if s.cfg.AccessPassword == "" {
		log.Printf("[auth] 网页访问密码已清除")
	} else {
		log.Printf("[auth] 网页访问密码已更新")
	}
}

// ===== 轨迹清理 =====

// cleanupPoints 丢掉超过 KeepDays 的轨迹点。
// 做法是整文件过滤后原子替换——轨迹一天几十行，几个月也才几千行，重写没有压力。
func (s *Store) cleanupPoints() {
	s.mu.RLock()
	days := s.cfg.KeepDays
	s.mu.RUnlock()
	if days <= 0 {
		return
	}
	cutoff := time.Now().AddDate(0, 0, -days).UnixMilli()

	// 先放开写句柄，否则 Windows 上无法替换文件
	s.mu.Lock()
	if s.points != nil {
		_ = s.points.Sync()
		_ = s.points.Close()
		s.points = nil
	}
	s.mu.Unlock()
	defer s.reopenPoints()

	src := filepath.Join(s.dir, "points.jsonl")
	in, err := os.Open(src)
	if err != nil {
		return
	}

	tmp := src + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		in.Close()
		return
	}

	var total, kept int
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	w := bufio.NewWriter(out)
	for sc.Scan() {
		total++
		line := sc.Bytes()
		var v struct {
			T int64 `json:"t"`
		}
		if json.Unmarshal(line, &v) == nil && v.T > 0 && v.T < cutoff {
			continue
		}
		_, _ = w.Write(line)
		_ = w.WriteByte('\n')
		kept++
	}
	_ = w.Flush()
	_ = in.Close()
	_ = out.Close()

	if err := os.Rename(tmp, src); err != nil {
		_ = os.Remove(tmp)
		return
	}
	if total != kept {
		log.Printf("[cleanup] 轨迹清理 %d → %d 行（保留 %d 天）", total, kept, days)
	}
}

func (s *Store) reopenPoints() {
	f, err := os.OpenFile(filepath.Join(s.dir, "points.jsonl"),
		os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	s.mu.Lock()
	s.points = f
	s.mu.Unlock()
}
