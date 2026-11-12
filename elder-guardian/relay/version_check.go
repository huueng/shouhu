package main

// 在线检查新版本（连 GitHub Releases）
//
// 设计取向：**只提示，不自动升级**。
//
// 中继跑在别人家的 NAS / 路由器上，自动升级一旦失败（下载中断、架构不对、
// 断电），后果是全家人都联系不上老人 —— 这个代价值不得。所以这里只做三件事：
// 查最新版本、跟当前版本比、把 release 页面链接给你，剩下你自己决定怎么升。
//
// 仓库地址从哪来：
//  1. config.json 的 update_repo（owner/repo）
//  2. 环境变量 ELDER_UPDATE_REPO（Docker / 路由器上用这个更省事）
//  3. 都没有就返回一条提示，不去猜
//
// 为什么要缓存：GitHub 匿名 API 限 60 次/小时/IP，而管理台可能被反复刷新。

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	ghAPI         = "https://api.github.com/repos/"
	ghTimeout     = 8 * time.Second
	ghCacheTTL    = 10 * time.Minute
	ghMaxBodySize = 1 << 20 // 1MB，防着返回意外的大响应
)

type ghRelease struct {
	TagName     string `json:"tag_name"`
	Name        string `json:"name"`
	HTMLURL     string `json:"html_url"`
	Body        string `json:"body"`
	PublishedAt string `json:"published_at"`
	Prerelease  bool   `json:"prerelease"`
}

type versionInfo struct {
	OK        bool   `json:"ok"`
	Current   string `json:"current"`
	Latest    string `json:"latest,omitempty"`
	Newer     bool   `json:"newer"`
	Repo      string `json:"repo,omitempty"`
	URL       string `json:"url,omitempty"`
	Notes     string `json:"notes,omitempty"`
	Published string `json:"published,omitempty"`
	Cached    bool   `json:"cached,omitempty"`
	Error     string `json:"error,omitempty"`
	Hint      string `json:"hint,omitempty"`
}

var (
	ghMu      sync.Mutex
	ghCached  versionInfo
	ghCachedAt time.Time
)

// updateRepo 决定查哪个仓库
func (s *Store) updateRepo() string {
	s.mu.RLock()
	r := s.cfg.UpdateRepo
	s.mu.RUnlock()
	r = strings.TrimSpace(r)
	if r == "" {
		r = strings.TrimSpace(os.Getenv("ELDER_UPDATE_REPO"))
	}
	return strings.Trim(r, "/")
}

// cmpVersion 比较两个版本号，a>b 返回 1，相等 0，a<b 返回 -1。
// 只认数字段：1.14.1 / v1.14.1 / 1.14.1-beta 都能比，
// 预发布后缀一律当"更小"（1.15.0-beta < 1.15.0），够用了。
func cmpVersion(a, b string) int {
	pa, preA := splitVer(a)
	pb, preB := splitVer(b)
	n := len(pa)
	if len(pb) > n {
		n = len(pb)
	}
	for i := 0; i < n; i++ {
		x, y := 0, 0
		if i < len(pa) {
			x = pa[i]
		}
		if i < len(pb) {
			y = pb[i]
		}
		if x != y {
			if x > y {
				return 1
			}
			return -1
		}
	}
	switch {
	case preA == preB:
		return 0
	case preA == "":
		return 1 // 正式版 > 预发布
	case preB == "":
		return -1
	default:
		if preA > preB {
			return 1
		}
		return -1
	}
}

// splitVer 把 "v1.14.1-beta.2" 拆成 [1,14,1] 和 "beta.2"
func splitVer(v string) ([]int, string) {
	v = strings.TrimSpace(v)
	v = strings.TrimPrefix(strings.TrimPrefix(v, "v"), "V")
	pre := ""
	if i := strings.IndexAny(v, "-+"); i >= 0 {
		pre = v[i+1:]
		v = v[:i]
	}
	var out []int
	for _, seg := range strings.Split(v, ".") {
		// 段里可能混着非数字（1.14a），取前面的数字部分
		j := 0
		for j < len(seg) && seg[j] >= '0' && seg[j] <= '9' {
			j++
		}
		n, err := strconv.Atoi(seg[:j])
		if err != nil {
			n = 0
		}
		out = append(out, n)
	}
	return out, pre
}

// handleVersionCheck 查 GitHub 上的最新 release。
// 只读操作，但要过访问密码：它会替服务器往外发请求，不该让路人随便触发。
func (s *Store) handleVersionCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		fail(w, http.StatusMethodNotAllowed, "只接受 GET")
		return
	}
	repo := s.updateRepo()
	if repo == "" {
		writeJSON(w, http.StatusOK, versionInfo{
			OK:      false,
			Current: Version,
			Error:   "没配置更新源",
			Hint:    "在 config.json 里加 update_repo（例如 your-name/shouhu），或设环境变量 ELDER_UPDATE_REPO",
		})
		return
	}

	ghMu.Lock()
	if ghCached.Repo == repo && time.Since(ghCachedAt) < ghCacheTTL {
		v := ghCached
		v.Cached = true
		ghMu.Unlock()
		writeJSON(w, http.StatusOK, v)
		return
	}
	ghMu.Unlock()

	info := fetchLatest(repo)
	info.Current = Version
	if info.OK {
		info.Newer = cmpVersion(info.Latest, Version) > 0
	}

	ghMu.Lock()
	ghCached, ghCachedAt = info, time.Now()
	ghMu.Unlock()

	writeJSON(w, http.StatusOK, info)
}

func fetchLatest(repo string) versionInfo {
	out := versionInfo{Repo: repo}
	req, err := http.NewRequest(http.MethodGet, ghAPI+repo+"/releases/latest", nil)
	if err != nil {
		out.Error = err.Error()
		return out
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "shouhu-relay/"+Version)
	// 有令牌就用（CI 或自建环境里能提到限额），没有也能用
	if t := os.Getenv("GITHUB_TOKEN"); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}

	cli := &http.Client{Timeout: ghTimeout}
	resp, err := cli.Do(req)
	if err != nil {
		out.Error = "连不上 GitHub：" + err.Error()
		out.Hint = "这台机器可能没有外网，或者被墙/被限流；不影响中继正常功能。"
		return out
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusNotFound:
		out.Error = "仓库或 release 不存在：" + repo
		out.Hint = "确认 update_repo 写的是 owner/repo，且已经发过 Release。"
		return out
	case http.StatusForbidden:
		out.Error = "GitHub 限流了（匿名 60 次/小时）"
		out.Hint = "过一会儿再试，或设 GITHUB_TOKEN 环境变量。"
		return out
	default:
		out.Error = "GitHub 返回 HTTP " + strconv.Itoa(resp.StatusCode)
		return out
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, ghMaxBodySize))
	if err != nil {
		out.Error = "读取 GitHub 响应失败：" + err.Error()
		return out
	}
	var rel ghRelease
	if err := json.Unmarshal(body, &rel); err != nil {
		out.Error = "解析 GitHub 响应失败：" + err.Error()
		return out
	}
	out.OK = true
	out.Latest = rel.TagName
	out.URL = rel.HTMLURL
	out.Notes = firstLines(rel.Body, 6)
	out.Published = rel.PublishedAt
	return out
}

// firstLines 取 release notes 的前几行，够在管理台上瞄一眼改了啥
func firstLines(s string, n int) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	lines := strings.Split(s, "\n")
	if len(lines) > n {
		lines = lines[:n]
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}
