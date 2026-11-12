package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// 内置升级
//
// 背景：以前每次改中继都要打 fpk、打开飞牛应用中心、上传、重装 —— 太麻烦。
// 现在中继自己去"更新源"看一眼，有新版就下载、校验、替换，然后原地重启。
//
// 更新源是一个 JSON（放 GitHub Release / 自己的静态站 / CF 后面都行）：
//
//	{
//	  "version": "1.8.0",
//	  "notes": "修了什么",
//	  "files": {
//	    "linux-amd64": {"url": "https://.../elderrelay-linux-amd64", "sha256": "..."},
//	    "linux-arm64": {"url": "...", "sha256": "..."}
//	  }
//	}
//
// 关键点：**不覆盖 fpk 装出来的只读目录**。新二进制落到数据目录
// <data>/current/elderrelay，启动脚本优先用它（见 cmd/main）。
// 这样升级不需要 root、也不会被下次重装 fpk 覆盖成旧版
//（重装 fpk 时 upgrade_callback 会删掉 current/，让新包生效）。

type updateFile struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}

type updateManifest struct {
	Version string                `json:"version"`
	Notes   string                `json:"notes"`
	Files   map[string]updateFile `json:"files"`
}

// archKey 形如 linux-amd64
func archKey() string {
	return runtime.GOOS + "-" + runtime.GOARCH
}

func (s *Store) updateSource() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return strings.TrimSpace(s.cfg.UpdateURL)
}

func (s *Store) fetchUpdateManifest() (*updateManifest, error) {
	src := s.updateSource()
	if src == "" {
		return nil, fmt.Errorf("还没配置更新源（config.json 里的 update_url）")
	}
	cli := &http.Client{Timeout: 15 * time.Second}
	resp, err := cli.Get(src)
	if err != nil {
		return nil, fmt.Errorf("拉取更新源失败：%v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("更新源返回 HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var m updateManifest
	if err := json.Unmarshal(body, &m); err != nil {
		return nil, fmt.Errorf("更新源不是合法 JSON：%v", err)
	}
	if m.Version == "" {
		return nil, fmt.Errorf("更新源里没有 version")
	}
	return &m, nil
}

func verNewer(latest, cur string) bool {
	pa, pb := strings.Split(latest, "."), strings.Split(cur, ".")
	for i := 0; i < 3; i++ {
		var a, b int
		if i < len(pa) {
			fmt.Sscanf(pa[i], "%d", &a)
		}
		if i < len(pb) {
			fmt.Sscanf(pb[i], "%d", &b)
		}
		if a != b {
			return a > b
		}
	}
	return false
}

// currentBinPath 是"升级后实际运行"的位置
func (s *Store) currentBinPath() string {
	return filepath.Join(s.dir, "current", "elderrelay")
}

// prepareUpdate 下载并校验新二进制，返回它的路径。失败时不动现有文件。
func (s *Store) prepareUpdate(m *updateManifest) (string, error) {
	f, ok := m.Files[archKey()]
	if !ok {
		return "", fmt.Errorf("更新源里没有 %s 的包", archKey())
	}
	if f.URL == "" {
		return "", fmt.Errorf("更新源里 %s 缺少 url", archKey())
	}

	cli := &http.Client{Timeout: 5 * time.Minute}
	resp, err := cli.Get(f.URL)
	if err != nil {
		return "", fmt.Errorf("下载失败：%v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("下载返回 HTTP %d", resp.StatusCode)
	}

	dir := filepath.Join(s.dir, "current")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("建目录失败：%v", err)
	}
	tmp := filepath.Join(dir, "elderrelay.new")
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return "", fmt.Errorf("写临时文件失败：%v", err)
	}

	h := sha256.New()
	// 上限 200MB：正常二进制 8MB 左右，超过基本是下错了
	n, err := io.Copy(io.MultiWriter(out, h), io.LimitReader(resp.Body, 200<<20))
	cerr := out.Close()
	if err != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("下载中断（已收 %d 字节）：%v", n, err)
	}
	if cerr != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("落盘失败：%v", cerr)
	}

	sum := hex.EncodeToString(h.Sum(nil))
	if f.SHA256 != "" && !strings.EqualFold(sum, f.SHA256) {
		os.Remove(tmp)
		return "", fmt.Errorf("校验不通过：期望 %s，实际 %s（文件可能下坏了）", f.SHA256, sum)
	}
	if err := os.Chmod(tmp, 0o755); err != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("设置可执行位失败：%v", err)
	}
	return tmp, nil
}

// swapAndRestart 把新二进制换上去，然后原地重启进程
func (s *Store) swapAndRestart(tmp string) error {
	dst := s.currentBinPath()
	// Linux 允许 rename 覆盖正在运行的可执行文件（inode 不变，旧进程继续跑）
	if err := os.Rename(tmp, dst); err != nil {
		return fmt.Errorf("替换文件失败：%v", err)
	}
	return execSelf(dst)
}

// ===== HTTP =====

func (s *Store) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	if !s.basicOK(r) {
		fail(w, http.StatusUnauthorized, "需要访问密码")
		return
	}
	m, err := s.fetchUpdateManifest()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"ok": false, "error": err.Error(), "current": Version,
			"configured": s.updateSource() != "",
		})
		return
	}
	_, has := m.Files[archKey()]
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":         true,
		"current":    Version,
		"latest":     m.Version,
		"notes":      m.Notes,
		"newer":      verNewer(m.Version, Version),
		"hasPackage": has,
		"arch":       archKey(),
		"configured": true,
	})
}

func (s *Store) handleUpdateApply(w http.ResponseWriter, r *http.Request) {
	if !s.basicOK(r) {
		fail(w, http.StatusUnauthorized, "需要访问密码")
		return
	}
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	m, err := s.fetchUpdateManifest()
	if err != nil {
		fail(w, http.StatusBadRequest, err.Error())
		return
	}
	if !verNewer(m.Version, Version) {
		fail(w, http.StatusBadRequest, "已经是最新的（"+Version+"）")
		return
	}
	tmp, err := s.prepareUpdate(m)
	if err != nil {
		fail(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 先回响应，再重启：否则浏览器会看到连接被重置
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true, "restarting": true, "version": m.Version,
		"note": "已下载并校验通过，正在重启，几秒后刷新页面",
	})
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	go func() {
		time.Sleep(800 * time.Millisecond)
		if err := s.swapAndRestart(tmp); err != nil {
			// 重启不了的兜底：至少把新二进制留在原地，日志里能查到
			fmt.Fprintf(os.Stderr, "[update] 重启失败：%v\n", err)
		}
	}()
}

// handleUpdateUpload 手动上传升级包（不走外网更新源）。
// 表单字段名 file，内容是新架构的 Linux 二进制。
func (s *Store) handleUpdateUpload(w http.ResponseWriter, r *http.Request) {
	if !s.basicOK(r) {
		fail(w, http.StatusUnauthorized, "需要访问密码")
		return
	}
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "只接受 POST")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<20)
	if err := r.ParseMultipartForm(64 << 20); err != nil {
		fail(w, http.StatusBadRequest, "解析上传失败："+err.Error())
		return
	}
	f, hdr, err := r.FormFile("file")
	if err != nil {
		fail(w, http.StatusBadRequest, "没收到文件（表单字段应为 file）")
		return
	}
	defer f.Close()
	if hdr.Size < 1<<20 {
		fail(w, http.StatusBadRequest, "文件太小（"+fmt.Sprint(hdr.Size)+" 字节），不像是中继程序")
		return
	}

	dir := filepath.Join(s.dir, "current")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(w, http.StatusInternalServerError, "建目录失败："+err.Error())
		return
	}
	tmp := filepath.Join(dir, "elderrelay.new")
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		fail(w, http.StatusInternalServerError, "写文件失败："+err.Error())
		return
	}
	n, err := io.Copy(out, f)
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp)
		fail(w, http.StatusInternalServerError, "接收文件失败："+err.Error())
		return
	}
	if err := os.Chmod(tmp, 0o755); err != nil {
		os.Remove(tmp)
		fail(w, http.StatusInternalServerError, "设置可执行位失败："+err.Error())
		return
	}
	// 简单校验：Linux 可执行文件以 ELF 开头。传错文件（比如 apk）当场拦下，
	// 免得把服务换成跑不起来的东西。
	head := make([]byte, 4)
	if fh, e := os.Open(tmp); e == nil {
		_, _ = io.ReadFull(fh, head)
		fh.Close()
	}
	if string(head) != "\x7fELF" {
		os.Remove(tmp)
		fail(w, http.StatusBadRequest, "这不是 Linux 可执行文件（应以 ELF 开头），请上传 publish-update.sh 生成的文件")
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true, "restarting": true, "size": n,
		"note": "已收到并检查通过，正在重启",
	})
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	go func() {
		time.Sleep(800 * time.Millisecond)
		if err := s.swapAndRestart(tmp); err != nil {
			fmt.Fprintf(os.Stderr, "[update] 重启失败：%v\n", err)
		}
	}()
}
