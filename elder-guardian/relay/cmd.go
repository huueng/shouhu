package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"time"
)

// 远程命令
//
// 老人机在内网、没有公网入口，中继连不进去。所以命令不做"推送"，而是：
//
//	家人端 POST /api/cmd → 入队 → 老人端下次上报时，响应里捎一条 → 老人端执行
//	→ 下一次上报把结果带回来 → 存在设备上，家人端查 /api/status 能看到。
//
// 好处：不需要长连接、不需要老人端开端口；断网期间命令在队列里等着，
// 网络恢复后第一次上报就能取到（最坏延迟 = 上报间隔）。
type CmdItem struct {
	ID     string `json:"id"`
	Device string `json:"device"`
	Cmd    string `json:"cmd"`
	Arg    string `json:"arg,omitempty"`
	At     int64  `json:"at"`
	Taken  bool   `json:"taken"` // 已经随某次上报下发过
}

// CmdResult 是老人端带回来的执行结果
type CmdResult struct {
	ID  string `json:"id"`
	Cmd string `json:"cmd"`
	OK  bool   `json:"ok"`
	Msg string `json:"msg"`
	At  int64  `json:"at"`
}

// 只允许这几条：都在老人端有对应实现，且不会把手机搞成砖
var allowedCmds = map[string]bool{
	"health":   true, // 体检
	"restore":  true, // 恢复首次基线（arg=manual 时用手动快照）
	"save":     true, // 存一份手动快照
	"restart":  true, // 把桌面拉回前台
	"unlock":   true, // 解除锁定任务（不动 Device Owner）
	"locate":   true, // 立刻定一次位并马上回传（家人按「查询位置」用）
	"snapdel":  true, // 删掉一份历史快照（arg=快照 id）
	"snapshow": true, // 看某份快照里的配置摘要（arg=快照 id）
	"volume":   true, // 调音量：up/down/mute/unmute/set:0-100，ring: 前缀调铃声
	"fastlink": true, // 秒级通道开关：on/off（中继可以远程控制）
}

// 队列最多留这么多条，防止家人手抖连点堆一堆
const maxCmds = 20

func newCmdID() string {
	b := make([]byte, 5)
	_, _ = rand.Read(b)
	return "c" + hex.EncodeToString(b)
}

// handleCmd 接收家人端下发的命令
//
//	POST /api/cmd?k=<设备令牌>
//	{"cmd":"health"}  或  {"cmd":"restore","arg":"manual"}
func (s *Store) handleCmd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	var body struct {
		Cmd string `json:"cmd"`
		Arg string `json:"arg"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		fail(w, http.StatusBadRequest, "请求体不是 JSON")
		return
	}
	if !allowedCmds[body.Cmd] {
		fail(w, http.StatusBadRequest, "不支持的命令："+body.Cmd)
		return
	}

	it := CmdItem{
		ID:     newCmdID(),
		Device: d.ID,
		Cmd:    body.Cmd,
		Arg:    body.Arg,
		At:     time.Now().UnixMilli(),
	}
	it, dup := s.pushCmd(it)

	// 把"现在能不能秒级送达"直接告诉家人端：
	//   fastLink=true  → 老人端挂着长轮询，几秒内就执行
	//   fastLink=false → 只能等下次上报，把真实周期也带上，别让页面写死 10 分钟
	link, pending, every := s.linkState(d.ID), s.pendingCount(d.ID), s.intervalOf(d.ID)
	note := "已下发，老人端已连接，几秒内执行"
	if !link {
		if every > 0 {
			note = "已下发，老人端没挂着连接，等下次上报（约 " + itoa(every) + " 分钟）"
		} else {
			note = "已下发，老人端没挂着连接，等下次上报时执行"
		}
	}
	if dup {
		note = "这条命令已经在队列里了，没有重复下发"
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":       true,
		"id":       it.ID,
		"dup":      dup,
		"fastLink": link,
		"pending":  pending,
		"interval": every,
		"note":     note,
	})
}

// pushCmd 入队（同一设备最多留 maxCmds 条，超了丢最老的）。
//
// 同一个命令重复点会就地合并：家人着急时连点十几下「健康检测」，
// 以前就是十几条一模一样的东西排在队列里（实测堆到 20 条），
// 既看不出到底发没发出去，手机端还要一条条执行。
// 这里只要队列里还有一条没被取走的同参数命令，就直接返回它。
func (s *Store) pushCmd(it CmdItem) (CmdItem, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, c := range s.cmds {
		if c.Device == it.Device && c.Cmd == it.Cmd && c.Arg == it.Arg && !c.Taken {
			s.dirty = true
			return c, true
		}
	}

	s.cmds = append(s.cmds, it)

	n := 0
	for _, c := range s.cmds {
		if c.Device == it.Device {
			n++
		}
	}
	if n > maxCmds {
		out := make([]CmdItem, 0, len(s.cmds))
		drop := n - maxCmds
		for _, c := range s.cmds {
			if c.Device == it.Device && drop > 0 {
				drop--
				continue
			}
			out = append(out, c)
		}
		s.cmds = out
	}
	s.dirty = true
	// 唤醒正在 /api/wait 上挂着的老人端 —— 这是"秒级下发"的关键一步，
	// 少了它命令要等到下一次上报才会被看到。
	s.broadcast()
	return it, false
}

// takeCmdIfLive 只在"实时上报"时才取命令。allowed=false 表示这是补发请求，
// 取走也没人看响应，不如留着等下一次实时上报。
func (s *Store) takeCmdIfLive(device string, allowed bool) *CmdItem {
	if !allowed {
		return nil
	}
	return s.takeCmd(device)
}

// takeCmd 取该设备最早一条还没下发的命令，并标记已下发
func (s *Store) takeCmd(device string) *CmdItem {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.cmds {
		c := &s.cmds[i]
		if c.Device != device || c.Taken {
			continue
		}
		c.Taken = true
		s.dirty = true
		cp := *c
		return &cp
	}
	return nil
}

// putCmdResult 保存老人端带回来的结果（自己加锁，供 HTTP 层用）
func (s *Store) putCmdResult(device string, r CmdResult) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.putCmdResultLocked(device, r)
}

// putCmdResultLocked 是上面的"已持锁"版本。
// processReport 全程持有 s.mu，里面必须调这个，否则会把自己锁死（测试就卡在这）。
func (s *Store) putCmdResultLocked(device string, r CmdResult) {
	if r.ID == "" && r.Cmd == "" {
		return
	}
	if s.cmdResults == nil {
		s.cmdResults = map[string]CmdResult{}
	}
	s.cmdResults[device] = r

	// 执行完了就把队列里那条删掉；老人端若没收到 id（老版本），按命令名清最早的
	out := make([]CmdItem, 0, len(s.cmds))
	for _, c := range s.cmds {
		if c.Device != device {
			out = append(out, c)
			continue
		}
		if (r.ID != "" && c.ID == r.ID) || (r.ID == "" && c.Cmd == r.Cmd && c.Taken) {
			continue
		}
		out = append(out, c)
	}
	s.cmds = out
	s.dirty = true
}

// pendingCount 还有几条没执行
func (s *Store) pendingCount(device string) int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	n := 0
	for _, c := range s.cmds {
		if c.Device == device {
			n++
		}
	}
	return n
}

// linkState 现在是否挂着快通道（家人端据此提示"几秒内"还是"等下次上报"）
func (s *Store) linkState(device string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.linkN[device] > 0
}

// intervalOf 取老人端当前的上报周期（分钟），没报过就是 0
func (s *Store) intervalOf(device string) int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if r := s.lastRep[device]; r != nil {
		return r.Interval
	}
	return 0
}

// clearCmds 把某设备还没下发的命令全丢掉。
// 连点堆了十几条时，家人需要能"一键清空"，不然只能干等它们一条条执行。
func (s *Store) clearCmds(device string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]CmdItem, 0, len(s.cmds))
	n := 0
	for _, c := range s.cmds {
		if c.Device == device && !c.Taken {
			n++
			continue
		}
		out = append(out, c)
	}
	s.cmds = out
	s.dirty = true
	return n
}

// handleCmdClear 清空该设备还没下发的命令
//
//	POST /api/cmd/clear?k=<设备令牌>
func (s *Store) handleCmdClear(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}
	n := s.clearCmds(d.ID)
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "cleared": n})
}
