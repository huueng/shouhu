#!/bin/sh
# 在 OpenWrt 路由器上执行命令（非交互，密码走 SSH_ASKPASS）
#
# 用法：
#   ROUTER_PASS=你的密码 tools/rssh.sh 'opkg list-installed | grep elder'
#   ROUTER_HOST=192.168.31.1 ROUTER_PASS=xxx tools/rssh.sh 'df -h /overlay'
#
# 密码只从环境变量读，不写进仓库。
set -eu
HOST=${ROUTER_HOST:-192.168.1.1}
PORT=${ROUTER_PORT:-22}
USER=${ROUTER_USER:-root}
: "${ROUTER_PASS:?请先设置 ROUTER_PASS 环境变量（路由器 root 密码）}"

ASK=$(mktemp "${TMPDIR:-/tmp}/shouhu-askpass.XXXXXX")
trap 'rm -f "$ASK" 2>/dev/null || true' EXIT INT TERM
printf '#!/bin/sh\nprintf "%%s\\n" "$ROUTER_PASS"\n' > "$ASK"
chmod 700 "$ASK"

DISPLAY=:0 SSH_ASKPASS="$ASK" SSH_ASKPASS_REQUIRE=force ROUTER_PASS="$ROUTER_PASS" \
  ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -o PreferredAuthentications=password -o PubkeyAuthentication=no \
      -o LogLevel=ERROR -o ConnectTimeout=8 -p "$PORT" "$USER@$HOST" "$@"
