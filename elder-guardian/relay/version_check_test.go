package main

import "testing"

func TestCmpVersion(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.14.1", "1.14.1", 0},
		{"1.14.2", "1.14.1", 1},
		{"1.14.1", "1.14.2", -1},
		{"v1.15.0", "1.14.9", 1},   // v 前缀不影响
		{"1.2", "1.10", -1},        // 按数字比，不是字符串比
		{"1.14", "1.14.0", 0},      // 缺段当 0
		{"1.15.0", "1.15.0-beta", 1}, // 正式版大于预发布
		{"1.15.0-beta", "1.15.0", -1},
		{"2.0.0", "1.99.99", 1},
	}
	for _, c := range cases {
		if got := cmpVersion(c.a, c.b); got != c.want {
			t.Errorf("cmpVersion(%q,%q)=%d，期望 %d", c.a, c.b, got, c.want)
		}
	}
}

func TestFirstLines(t *testing.T) {
	in := "第一行\r\n第二行\n第三行\n第四行\n第五行\n第六行\n第七行"
	got := firstLines(in, 6)
	if got != "第一行\n第二行\n第三行\n第四行\n第五行\n第六行" {
		t.Fatalf("firstLines 结果不对：%q", got)
	}
}
