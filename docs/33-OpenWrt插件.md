# OpenWrt / ImmortalWrt 插件（rax3000m）

> 2026-11-12 更新：22 端口已开，**已在真机装上并跑起来**。
> 本节结论都是实测过的，包括三个会让 opkg 直接失败或段错误的坑。

## 结论先说

- 包：`dist/elderrelay_1.15.0_aarch64_cortex-a53.ipk`（2.3 MB，静态二进制，无依赖）
- 环境：**CMCC RAX3000M / mediatek filogic / aarch64_cortex-a53 / ImmortalWrt 24.10.6**
- 已安装并自启：`elderrelay - 1.15.0`，监听 `:::8898`，RSS 仅 **6.8 MB**
- 三个坑（都在下面「踩过的坑」里）：ipk 不能用 ar 格式、不能有空 `Depends:`、不能带 `./` 目录项

## 路由器实际情况

| 项 | 值 |
|---|---|
| 型号 / 目标 | CMCC RAX3000M / mediatek filogic |
| 架构 | aarch64_cortex-a53 |
| 固件 | ImmortalWrt 24.10.6（内核 6.6.133） |
| 内存 | 502 MB（中继实占 ~7 MB） |
| overlay（可写空间） | 81.9 MB 总量，装完剩 46 MB |
| opkg | 2024-10-16 构建，`option check_signature` 已开 |
| SSH | 22 端口，dropbear，密码登录 root |

## 安装

```bash
# 1) 传包（dropbear 没装 sftp-server，必须用 legacy scp 协议 -O）
scp -O dist/elderrelay_1.15.0_aarch64_cortex-a53.ipk root@192.168.1.1:/tmp/

# 2) 装（固件开了签名校验，我们的包没官方私钥，必须 --force-signature）
ssh root@192.168.1.1 'opkg install --force-signature /tmp/elderrelay_1.15.0_aarch64_cortex-a53.ipk'

# 3) 确认
ssh root@192.168.1.1 '/etc/init.d/elderrelay status; netstat -ln | grep 8898; logread -e elderrelay | tail'
```

`postinst` 里已经做了 `enable` + `restart`，装完即跑。

项目里有两个小工具省掉手输密码（密码走 `SSH_ASKPASS`，非交互）：

```bash
tools/rssh.sh 'opkg list-installed | grep elder'     # 在路由器上执行命令
tools/rpush.sh dist/xxx.ipk /tmp/                    # 传文件（自动走 -O）
```

### 升级 / 重装

```bash
opkg install --force-signature --force-reinstall /tmp/elderrelay_x.y.z.ipk
```

实测：覆盖升级会先 `prerm`（停服务）再 `postinst`（启服务），
**`/etc/elderrelay/config.json` 里的设备令牌不变**，老人端不用重新扫码。
（`/etc/elderrelay` 不在包内，opkg 不会动它；`/etc/config/elderrelay` 是 conffiles，改过会保留。）

## 踩过的坑（都实测复现过）

### 1. ipk 不能是 ar 归档，必须是 `gzip(tar)`

老式 Debian 风格的 ipk 是 ar 归档（`debian-binary` / `control.tar.gz` / `data.tar.gz`）。
ImmortalWrt 24.10 这版 opkg **不认**，报：

```
pkg_init_from_file: Malformed package file /tmp/xxx.ipk
```

因为它是按 `gzip → tar` 读的，ar 头被当成"不是 gzip"，control 抽成空流，
于是"没有 Package 字段"→ 直接判畸形。

正确结构（和官方 24.10 产物完全一致）：

```
elderrelay_x.ipk            = gzip(tar)
  ./debian-binary           "2.0\n"
  ./data.tar.gz             gzip(tar)：./usr/bin/elderrelay 等
  ./control.tar.gz          gzip(tar)：./control 等
```

顺序必须是 debian-binary → data.tar.gz → control.tar.gz；
tar 里 `uname`/`gname` 留空（官方就是这样），uid/gid 为 0。

### 2. 空的 `Depends:` 会让 opkg 段错误

```
Segmentation fault（RC=139）
```

`Depends: `（冒号后面什么都没有）会让 `parse_deplist` 崩掉。
**没有依赖就别写这一行**，不要留空字段。

### 3. 不能往 tar 里塞 `./` 目录项

官方包里有 `./` 目录项，但我们的包带上它，opkg 取 control 时按"第一个成员"读，
拿到 0 字节的目录内容 → 又是 `Malformed package file`（有时直接段错误）。
只放文件、不放目录项即可；`/usr/bin`、`/etc/init.d`、`/etc/config` 本来就在系统里，
解包时会自动建父目录。

## 装完怎么用

1. 浏览器打开 `http://192.168.1.1:8898` → 首页是家人页
2. 点「中继页」进管理台，先设**访问密码**
3. 设备页「二维码地址」填对外地址（如 `http://shouhu.xxx.com:812`），
   否则从内网打开时二维码里写的是 `192.168.1.1`，出门就用不了
4. **老人端要重新扫码**：路由器上是全新实例，令牌是 `a1b2c3d4e5f6`，和 NAS 那个不是同一个

### 公网访问（待你决定）

路由器 WAN 侧是 `192.168.1.1`（在上一级路由器后面，二级 NAT），
全局 IPv6 是 `2408:8220:7f10:78c0:ca75:f4ff:fe5c:e759`。
但域名 `shouhu.example.com` 现在只解析出 AAAA = `2408:8220:7f10:78c0:d8bc:fae7:42cc:1bea`，
**不是这台路由器**——说明公网入口还指着别的设备（DDNS 也没指过来）。

想直接走这台路由器，两条路选一条：

```bash
# A. 中继改成 812 端口，域名端口不变
vi /etc/elderrelay/config.json      # "port": 812, "bind": ":812"
/etc/init.d/elderrelay restart

# B. 保持 8898，在防火墙上开 8898（IPv6 直连路由器本身，不需要 DNAT）
uci add firewall rule
uci set firewall.@rule[-1].name='Allow-elderrelay'
uci set firewall.@rule[-1].src='wan'
uci set firewall.@rule[-1].dest_port='8898'
uci set firewall.@rule[-1].proto='tcp'
uci set firewall.@rule[-1].target='ACCEPT'
uci commit firewall && /etc/init.d/firewall reload
```

选 A 的话，域名解析也得改成这台路由器的 IPv6（DDNS）。

> ⚠️ 两个中继不要同时对外：老人端只能指向一个上报地址。

## 端口冲突

默认 8898。要改：

```bash
vi /etc/elderrelay/config.json        # 改 "port"（bind 留空表示监听所有接口）
/etc/init.d/elderrelay restart
```

中继的端口一直以 `config.json` 为准（和 NAS 上那份是同一个程序、同一套配置），
UCI 里只放「开关 + 数据目录」，避免两套配置互相打架。

## 常用命令

```bash
/etc/init.d/elderrelay status
/etc/init.d/elderrelay restart
logread -e elderrelay -f          # 跟踪日志
opkg remove elderrelay            # 卸载（/etc/elderrelay 数据保留）
opkg files elderrelay             # 看装了哪些文件
```

装进系统的就三个文件：

```
/usr/bin/elderrelay              静态 Go 二进制（arm64，无 libc 依赖）
/etc/init.d/elderrelay           procd 启动脚本（START=95）
/etc/config/elderrelay           UCI：enabled / data
/etc/elderrelay/                 数据目录（首次启动生成 config.json / points.jsonl）
```

## 重新打包

```bash
# 1) 交叉编译（静态）
cd elder-guardian/relay
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 \
  ../../.toolchain/go/bin/go.exe build -trimpath \
  -ldflags "-s -w -X main.Version=1.15.0" -o ../../.build/relay-arm64 .

# 2) 打 ipk
cd ../.. && python tools/build-ipk.py 1.15.0            # 只出 arm64（默认）
python tools/build-ipk.py 1.15.0 mipsle --upx           # 出 mipsel_24kc（MT7628/7620/7621）
```

`tools/build-ipk.py` 是纯 Python 实现的 `gzip(tar)` 打包（不依赖 OpenWrt SDK）——
中继是静态二进制，不需要 musl 工具链。支持的 `goarch`：`arm64`（默认）、`armv7`、`mipsle`；
打 mips 版记得 `GOARCH=mipsle GOMIPS=softfloat`，脚本会校验 ELF 头，架构对不上直接报错。
`--upx` 会把二进制压到 ~30%（mips：6.5 MB → 2.0 MB），16 MB flash 的机器靠它才装得下。
各机型的空间/内存门槛见 `docs/35-小型路由器运行条件.md`。

## 已知限制

| 项 | 说明 |
|---|---|
| 内置升级 | 中继页的「上传并升级」在 OpenWrt 上不可用（没有 fpk 那套 `current/` 目录），升级走 `opkg install --force-reinstall` |
| LuCI 集成 | 没做 LuCI 页面，中继自带网页（8898），够用 |
| 开机自启 | procd，`/etc/rc.d/S95elderrelay` |
| 数据备份 | 备份 `/etc/elderrelay` 就够了（config.json + points.jsonl） |
| 签名 | 包没 OpenWrt 官方签名，装的时候必须 `--force-signature` |
