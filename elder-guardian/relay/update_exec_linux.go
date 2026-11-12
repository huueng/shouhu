//go:build linux

package main

import (
	"os"
	"syscall"
)

// execSelf 用新二进制原地替换当前进程：PID 不变，服务管理器（systemd / fnOS 的守护）
// 不会以为应用挂了，也就不需要重启整个应用。参数与环境变量原样继承。
func execSelf(path string) error {
	return syscall.Exec(path, append([]string{path}, os.Args[1:]...), os.Environ())
}
