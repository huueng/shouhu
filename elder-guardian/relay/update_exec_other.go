//go:build !linux

package main

import "fmt"

// 非 Linux（本机调试时是 Windows/macOS）不做原地重启，
// 让开发期也能编译、也能测"检查更新/下载/校验"这一段。
func execSelf(path string) error {
	return fmt.Errorf("当前平台不支持原地重启（%s），请手动重启中继", path)
}
