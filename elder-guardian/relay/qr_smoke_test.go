package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/skip2/go-qrcode"
)

func TestQRRender(t *testing.T) {
	content := "elderguard://relay?base=https%3A%2F%2Fshouhu.example.com%3A812&k=abc123def456&n=%E5%AE%B6"
	png, err := qrcode.Encode(content, qrcode.Medium, 560)
	if err != nil {
		t.Fatal(err)
	}
	out := filepath.Join("..", "..", ".build", "qr-test.png")
	_ = os.MkdirAll(filepath.Dir(out), 0o755)
	if err := os.WriteFile(out, png, 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("二维码 %d 字节，已写到 %s", len(png), out)
}
