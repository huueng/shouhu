#!/bin/bash
# 打"内置升级"用的发行包：多架构二进制 + update.json
#
# 用法：
#   bash tools/publish-update.sh 1.8.1 https://你的静态站/relay
#
# 产物在 dist/update/：
#   elderrelay-linux-amd64   elderrelay-linux-arm64   elderrelay-linux-armv7
#   update.json              ← 把这个目录整体传上去，然后把它的地址填进中继 config.json 的 update_url
#
# 注意：update.json 里的 url 是"相对 base"拼出来的，所以 base 要填真实可访问的前缀，
# 例如 https://raw.githubusercontent.com/you/repo/main/relay 或自己的域名目录。
set -eu

VERSION="${1:?用法: publish-update.sh <版本号> <下载前缀URL>}"
BASE="${2:?用法: publish-update.sh <版本号> <下载前缀URL>}"
BASE="${BASE%/}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/elder-guardian/relay"
OUT="$ROOT/dist/update"
GO="${GO:-$ROOT/.toolchain/go/bin/go.exe}"
[ -x "$GO" ] || GO="go"

rm -rf "$OUT"
mkdir -p "$OUT"

build() {
  local arch="$1" outfile="$2"
  echo "  编译 linux/$arch"
  ( cd "$SRC" && CGO_ENABLED=0 GOOS=linux GOARCH="${arch%%/*}" GOARM="${arch#*/}" \
      "$GO" build -trimpath -ldflags "-s -w -X main.Version=$VERSION" -o "$OUT/$outfile" . )
}

build amd64 elderrelay-linux-amd64
build arm64 elderrelay-linux-arm64
build "arm/7" elderrelay-linux-armv7

echo "  生成 update.json"
python - "$OUT" "$VERSION" "$BASE" <<'PY'
import hashlib, json, os, sys

out, version, base = sys.argv[1], sys.argv[2], sys.argv[3].rstrip('/')
files = {}
for key, name in [("linux-amd64", "elderrelay-linux-amd64"),
                  ("linux-arm64", "elderrelay-linux-arm64"),
                  ("linux-arm", "elderrelay-linux-armv7")]:
    p = os.path.join(out, name)
    h = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    files[key] = {"url": f"{base}/{name}", "sha256": h}
    print(f"    {key:12s} {os.path.getsize(p)/1024/1024:.1f} MB  {h[:16]}…")

json.dump({"version": version, "notes": f"守护中继 {version}", "files": files},
          open(os.path.join(out, 'update.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
PY

echo
echo "完成：$OUT"
echo "下一步："
echo "  1) 把 dist/update/ 里的文件传到 $BASE"
echo "  2) 在中继 config.json 里加一行：\"update_url\": \"$BASE/update.json\""
echo "  3) 重启中继，管理台点「检查更新」"
