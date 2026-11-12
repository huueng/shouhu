package main

import (
	"net/http"
	"strconv"
	"time"
)

// 命令长轮询（秒级响应）
//
// 之前只有"上报时捎一条命令"这一条路，延迟等于一个上报周期（默认 10 分钟）。
// 家人点「催一次定位」，最坏要等 10 分钟才轮到老人端取走 —— 体验上就是"没反应"。
//
// 这里让老人端挂一条长连接：
//
//	GET /api/wait?k=<设备令牌>&t=25
//	  → 最多挂 t 秒；期间一有命令立刻返回 {"ok":true,"cmd":{...}}
//	  → 没命令就返回 {"ok":true,"cmd":null}，老人端立刻再挂一次
//
// 注意 t 的上限压到 45 秒：Cloudflare 隧道单请求 100 秒封顶，
// 留足余量，别让边缘节点先掐断（那样老人端会误以为网络坏了）。
//
// 这条通道是"由老人端发起"的，所以不需要公网入站、不需要端口映射，
// 穿 NAT / 穿 CF 隧道都一样能用。
func (s *Store) handleWait(w http.ResponseWriter, r *http.Request) {
	d, ok := s.deviceByToken(tokenOf(r))
	if !ok {
		fail(w, http.StatusUnauthorized, "令牌无效")
		return
	}

	// 记下"这条快通道挂着"，家人页就能显示"秒级通道：已连接"
	s.linkOpen(d.ID)
	defer s.linkClose(d.ID)

	wait, _ := strconv.Atoi(r.URL.Query().Get("t"))
	if wait <= 0 {
		wait = 25
	}
	if wait > 45 {
		wait = 45
	}
	deadline := time.Now().Add(time.Duration(wait) * time.Second)

	for {
		// 有命令就立刻下发（同一个 takeCmdIfLive：取走即标记 Taken，不会重复下发）
		if c := s.takeCmdIfLive(d.ID, true); c != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{
				"ok": true,
				"cmd": map[string]interface{}{
					"id":  c.ID,
					"cmd": c.Cmd,
					"arg": c.Arg,
					"at":  c.At,
				},
			})
			return
		}

		ch := s.waitChan()
		remain := time.Until(deadline)
		if remain <= 0 {
			writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "cmd": nil})
			return
		}

		select {
		case <-ch:
			// 有变化（命令入队 / 新上报落库），回到循环顶部再取一次
		case <-time.After(remain):
			writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "cmd": nil})
			return
		case <-r.Context().Done():
			// 老人端主动断开（切网、熄屏被杀），什么都不用做
			return
		}
	}
}

// linkOpen / linkClose 维护"当前挂着的 /api/wait 条数"。
//
// 为什么按条数而不是布尔：老人端可能因为切网同时挂了两条（旧连接还没超时），
// 布尔值会被先结束的那条清零，显示成"未连接"，家人端就会误判成要等 10 分钟。
func (s *Store) linkOpen(device string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.linkN[device]++
	s.linkAt[device] = time.Now().UnixMilli()
}

func (s *Store) linkClose(device string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.linkN[device] > 0 {
		s.linkN[device]--
	}
}
