#!/bin/bash
# 构建守护中继的飞牛 fpk 离线安装包
#
# 用法：bash tools/build-fpk.sh [版本号]
# 产物：dist/elderrelay-<版本>.fpk
#
# 说明：走 native 版（Go 静态二进制直接跑），不依赖 Docker，
#      所以整个 .fpk 是自包含的，可以拷到没有外网的机器上安装。
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/elder-guardian/relay"
FPKSRC="$SRC/fpk"
STAGE="$ROOT/.build/fpk-stage"
ICONS="$ROOT/.build/icons"
BIN="$ROOT/dist/elderrelay"
OUT="$ROOT/dist/elderrelay-${VERSION}.fpk"

echo "== 1/6 准备目录 =="
rm -rf "$STAGE"
mkdir -p "$STAGE/cmd" "$STAGE/config" "$STAGE/app/ui/images" "$ICONS" "$ROOT/dist"

echo "== 2/6 编译 linux/amd64 =="
GO_BIN="${GO_BIN:-}"
if [ -z "$GO_BIN" ]; then
  if command -v go >/dev/null 2>&1; then
    GO_BIN="go"
  elif [ -x "$ROOT/.toolchain/go/bin/go.exe" ]; then
    GO_BIN="$ROOT/.toolchain/go/bin/go.exe"
  elif [ -x "$ROOT/.toolchain/go/bin/go" ]; then
    GO_BIN="$ROOT/.toolchain/go/bin/go"
  fi
fi

# 家人页和手机 App 共用同一份 index.html：打包前从 App 资源同步过来，
# 免得两边改动各写一半（以前就得靠手工 cp，容易忘）。
cp "$ROOT/elder-guardian/family/src/main/assets/index.html" "$SRC/web/family.html" 2>/dev/null \
  && echo "   家人页已同步到中继"

if [ -n "$GO_BIN" ]; then
  ( cd "$SRC" && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
      "$GO_BIN" build -trimpath -ldflags="-s -w -X main.Version=$VERSION" -o "$BIN" . )
  echo "   编译完成：$BIN"
else
  [ -f "$BIN" ] || { echo "找不到 Go 工具链，且 $BIN 不存在，无法继续"; exit 1; }
  echo "   跳过编译，使用已有二进制：$BIN"
fi

if [ ! -f "$BIN" ]; then
  echo "二进制缺失：$BIN"
  exit 1
fi

echo "== 3/6 生成图标 =="
# Linux 上通常只有 python3（CI runner 就没有 python 这个别名），两个都试一下
PY="$(command -v python3 || command -v python)"
[ -n "$PY" ] || { echo "找不到 python3/python，无法生成图标"; exit 1; }
"$PY" "$ROOT/tools/gen-icon.py" "$ICONS" >/dev/null
cp "$ICONS/ICON.PNG" "$STAGE/ICON.PNG"
cp "$ICONS/ICON_256.PNG" "$STAGE/ICON_256.PNG"
cp "$ICONS/ICON.PNG" "$STAGE/app/ui/images/icon_64.png"
cp "$ICONS/ICON_256.PNG" "$STAGE/app/ui/images/icon_256.png"

echo "== 4/6 组装文件 =="
sed "s/^version=.*/version=${VERSION}/" "$FPKSRC/manifest" > "$STAGE/manifest"

# cmd 下全部 9 个生命周期脚本。统一转成 LF（CRLF 会让 shebang 变成 bad interpreter）
# 并补 0755（Windows 上打包会丢可执行位）。
for f in "$FPKSRC"/cmd/*; do
  name="$(basename "$f")"
  tr -d '\r' < "$f" > "$STAGE/cmd/$name"
  chmod 755 "$STAGE/cmd/$name" 2>/dev/null || true
done

cp "$FPKSRC/config/privilege" "$STAGE/config/privilege"
# resource 必须是单行 JSON，带换行会被安装器拒收
printf '{}' > "$STAGE/config/resource"
cp "$FPKSRC/app/ui/config" "$STAGE/app/ui/config"
cp "$BIN" "$STAGE/app/elderrelay"
# config/ 也要进 app.tgz：飞牛的 target 里要有 privilege/resource（官方 fnpack 就是这么打的）
mkdir -p "$STAGE/app/config"
cp "$FPKSRC/config/privilege" "$STAGE/app/config/privilege"
printf '{}' > "$STAGE/app/config/resource"
chmod 755 "$STAGE/app/elderrelay" 2>/dev/null || true

echo "== 5/6 打内层 app.tgz =="
# --mode 保证在 Windows 上打包时也能带上可执行位，
# 否则装到 NAS 上会变成"缺程序文件"或权限不足。
# 关键：打包 app/ 里的内容（不是 app 这个目录本身）。
# 以前是 tar ... app，解出来变成 <target>/app/elderrelay、<target>/app/ui/config，
# 而飞牛找的是 <target>/ui/config —— 结果桌面入口和「应用设置 → 访问端口」全都不显示。
# 官方 fnpack 的 app.tgz 里是 elderrelay + ui/ + config/ 直接在根，这里对齐。
( cd "$STAGE/app" && tar --mode=755 --owner=0 --group=0 -czf "$STAGE/app.tgz" elderrelay ui config )

# 注入 checksum：飞牛按 md5(app.tgz) 校验，缺失或对不上会被拒收
CK="$(md5sum "$STAGE/app.tgz" | awk '{print $1}')"
sed -i '/^checksum=/d' "$STAGE/manifest"
printf 'checksum=%s\n' "$CK" >> "$STAGE/manifest"
echo "   checksum=$CK"

echo "== 6/6 打外层 fpk =="
( cd "$STAGE" && tar --mode=755 --owner=0 --group=0 -czf "$OUT" \
    manifest ICON.PNG ICON_256.PNG cmd config app.tgz )

SIZE=$(wc -c < "$OUT" | tr -d ' ')
echo
echo "完成：$OUT  (${SIZE} 字节)"
echo
echo "-- 包内结构 --"
tar -tzf "$OUT"
echo
echo "-- 权限（cmd/* 与二进制应为 rwxr-xr-x）--"
# 这些只是给人看的自检：grep 没匹配到不该让整个打包判失败（脚本是 set -e + pipefail）。
tar -tvzf "$OUT" | grep -E ' cmd/| manifest' || true
tar -tvzf "$STAGE/app.tgz" | grep 'elderrelay' || true
echo
echo "-- manifest --"
tar -xzOf "$OUT" manifest
echo
echo "-- resource（应为单行 {}）--"
tar -xzOf "$OUT" config/resource | od -c | head -2
