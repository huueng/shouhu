#!/usr/bin/env python3
"""生成守护中继的盾牌图标。

不依赖 Pillow：自己编码 PNG（zlib + struct 都是标准库）。
输出：
  ICON_256.PNG          256x256 品牌底 + 白色盾牌（飞牛桌面图标）
  ICON.PNG               64x64 同上（飞牛要求）
  shield_white_256.png  256x256 透明底纯白盾牌（Android 通知小图标用这个造型）

用法：python tools/gen-icon.py [输出目录]
"""

import math
import os
import struct
import sys
import zlib

SS = 4  # 每个像素 4x4 超采样，用来做抗锯齿

# 品牌色（与文档配图一致）
BG = (0x18, 0x5F, 0xA5)
FG = (0xFF, 0xFF, 0xFF)


def shield_inside(x: float, y: float) -> bool:
    """归一化坐标 (0..1) 是否落在盾牌内。

    轮廓：顶部两角圆弧过渡 → 两侧竖直 → 底部用余弦收口收成带圆润的尖。
    用 cos 而不是椭圆，是因为椭圆在底部切线垂直，出来是 U 形（像墓碑不是盾）。
    """
    cx = 0.5
    half = 0.275       # 半宽
    top = 0.165        # 顶边
    straight = 0.54    # 竖直段结束处
    tip = 0.865        # 底部尖端
    r = 0.075          # 顶部圆角半径

    if y < top or y > tip:
        return False

    if y < top + r:
        dy = (top + r) - y
        w = (half - r) + math.sqrt(max(0.0, r * r - dy * dy))
    elif y <= straight:
        w = half
    else:
        t = (y - straight) / (tip - straight)
        w = half * math.cos(t * math.pi / 2)

    return abs(x - cx) <= w


def rounded_rect_inside(x: float, y: float, r: float) -> bool:
    cx = min(max(x, r), 1.0 - r)
    cy = min(max(y, r), 1.0 - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def coverage(fn, px: int, py: int, size: int) -> float:
    """一个像素内被覆盖的比例（超采样）"""
    hit = 0
    for sy in range(SS):
        for sx in range(SS):
            x = (px + (sx + 0.5) / SS) / size
            y = (py + (sy + 0.5) / SS) / size
            if fn(x, y):
                hit += 1
    return hit / (SS * SS)


def make_png(size: int, with_bg: bool) -> bytes:
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            if with_bg:
                bg_a = coverage(lambda x, y: rounded_rect_inside(x, y, 0.22), px, py, size)
                sh_a = coverage(shield_inside, px, py, size)
                # 白色盾牌压在品牌底上
                r = round(BG[0] * (1 - sh_a) + FG[0] * sh_a)
                g = round(BG[1] * (1 - sh_a) + FG[1] * sh_a)
                b = round(BG[2] * (1 - sh_a) + FG[2] * sh_a)
                a = round(bg_a * 255)
            else:
                sh_a = coverage(shield_inside, px, py, size)
                r = g = b = 255
                a = round(sh_a * 255)
            row += bytes((r, g, b, a))
        rows.append(bytes(row))

    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(typ: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8bit RGBA
    idat = zlib.compress(raw, 9)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)

    targets = [
        ("ICON_256.PNG", 256, True),
        ("ICON.PNG", 64, True),
        ("shield_white_256.png", 256, False),
    ]
    for name, size, with_bg in targets:
        data = make_png(size, with_bg)
        path = os.path.join(out, name)
        with open(path, "wb") as f:
            f.write(data)
        print(f"{path}  {size}x{size}  {len(data)} bytes")


if __name__ == "__main__":
    main()
