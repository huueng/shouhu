#!/bin/sh
# 传文件到 OpenWrt 路由器（dropbear 没装 sftp-server，必须走 legacy scp -O）
#
# 用法：
#   ROUTER_PASS=你的密码 tools/rpush.sh dist/xxx.ipk /tmp/
#
# 密码只从环境变量读，不写进仓库。
set -eu
HOST=${ROUTER_HOST:-192.168.1.1}
PORT=${ROUTER_PORT:-22}
USER=${ROUTER_USER:-root}
: "${ROUTER_PASS:?请先设置 ROUTER_PASS 环境变量（路由器 root 密码）}"

[ $# -ge 1 ] || { echo "用法: ROUTER_PASS=xxx $0 <本地文件> [远端路径]"; exit 2; }
SRC=$1
DST=${2:-/tmp/}

ASK=$(mktemp "${TMPDIR:-/tmp}/shouhu-askpass.XXXXXX")
trap 'rm -f "$ASK" 2>/dev/null || true' EXIT INT TERM
printf '#!/bin/sh\nprintf "%%s\\n" "$ROUTER_PASS"\n' > "$ASK"
chmod 700 "$ASK"

DISPLAY=:0 SSH_ASKPASS="$ASK" SSH_ASKPASS_REQUIRE=force ROUTER_PASS="$ROUTER_PASS" \
  scp -O -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -o LogLevel=ERROR -o ConnectTimeout=8 -o PreferredAuthentications=password -o PubkeyAuthentication=no \
      -P "$PORT" "$SRC" "$USER@$HOST:$DST"
