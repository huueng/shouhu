// 生成一张二维码 PNG，内容和中继 /api/qr?type=elder 生成的完全一致。
// 只为本地验证「解码器能不能解出中继那个码」，不参与发布。
package main

import (
	"fmt"
	"os"

	qrcode "github.com/skip2/go-qrcode"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "用法: qrgen <内容> <输出.png>")
		os.Exit(2)
	}
	png, err := qrcode.Encode(os.Args[1], qrcode.Medium, 560)
	if err != nil {
		fmt.Fprintln(os.Stderr, "生成失败:", err)
		os.Exit(1)
	}
	if err := os.WriteFile(os.Args[2], png, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, "写文件失败:", err)
		os.Exit(1)
	}
	fmt.Println("已生成", os.Args[2], len(png), "字节")
}
