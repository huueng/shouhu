// 老人守护 · 电脑端 启动器
//
// 为什么需要它：
// 打包成单文件 exe 后，外层的自解压程序直接拉起 .cmd 或 powershell，
// 都会留下一个黑色控制台窗口；用 -WindowStyle Hidden 藏它，
// 又会把"共用的那个控制台"一起藏掉（从别的终端启动时会误伤终端），
// 而且 STARTUPINFO 的 SW_HIDE 会连 WinForms 窗体的第一次显示都被系统忽略。
//
// 所以用一个 GUI 子系统的原生小程序来起头：
//   · 它自己没有控制台（-H windowsgui）
//   · 它用 CREATE_NO_WINDOW 拉起 powershell，子进程根本没有控制台窗口
//   · 不设置 STARTF_USESHOWWINDOW，窗体的 Show 就能正常按 SW_SHOW 生效
//
// 编译（在 tools/launcher 下）：
//   GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
//     go build -trimpath -ldflags "-s -w -H windowsgui" -o ../../.build/guard-launcher.exe .
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
)

const createNoWindow = 0x08000000

func main() {
	exe, err := os.Executable()
	if err != nil {
		os.Exit(1)
	}
	dir := filepath.Dir(exe)
	script := filepath.Join(dir, "pc.ps1")

	if _, err := os.Stat(script); err != nil {
		msgBox("找不到 pc.ps1，这个压缩包可能不完整。\r\n\r\n" + dir)
		os.Exit(1)
	}

	args := []string{
		"-NoProfile",
		"-ExecutionPolicy", "Bypass",
		"-File", script,
	}
	args = append(args, os.Args[1:]...)

	cmd := exec.Command("powershell.exe", args...)
	cmd.Dir = dir
	// 只要 CREATE_NO_WINDOW，不要 HideWindow：
	// HideWindow 会额外带一个 SW_HIDE 的启动信息，反而把窗体也藏了。
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: createNoWindow}

	// 等它退出：外层自解压包会在这一步之后清理临时目录，
	// 提前返回会导致程序运行到一半文件就没了。
	out, err := cmd.CombinedOutput()
	if err != nil {
		writeLog(out, err)
		if ee, ok := err.(*exec.ExitError); ok {
			// 用户关窗导致的正常退出，不打扰
			if ee.ExitCode() == 0 {
				return
			}
		}
		msgBox("电脑端启动失败：\r\n" + err.Error())
	}
}

// writeLog 把子进程输出留到 %TEMP%\guard-launcher.log，
// 出问题时让人能查到原因，不用再去猜。
func writeLog(out []byte, err error) {
	path := filepath.Join(os.TempDir(), "guard-launcher.log")
	f, e := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if e != nil {
		return
	}
	defer f.Close()
	fmt.Fprintf(f, "--- %s\n%v\n%s\n", time.Now().Format("2006-01-02 15:04:05"), err, string(out))
}

// msgBox 用系统自带的 MessageBox，避免为了报错再引依赖。
func msgBox(text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	proc := user32.NewProc("MessageBoxW")
	title, _ := syscall.UTF16PtrFromString("老人守护 · 电脑端")
	msg, _ := syscall.UTF16PtrFromString(fmt.Sprintf("%s", text))
	_, _, _ = proc.Call(0, uintptr(unsafe.Pointer(msg)), uintptr(unsafe.Pointer(title)), 0x40)
}
