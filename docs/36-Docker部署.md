# Docker 部署中继

## 三种部署方式怎么选

| 方式 | 适合 | 难度 | 升级 |
|---|---|---|---|
| **Docker**（本文） | 有 NAS / 软路由 / 服务器，想要一条命令起 | 低 | 换镜像 tag |
| 飞牛 fpk | 飞牛 NAS，图形界面装 | 低 | 内置「上传并升级」 |
| OpenWrt ipk | 路由器直接跑（见 `35-小型路由器运行条件.md`） | 中 | 重装 ipk |

中继是**单个静态二进制**，容器里只多了一层 alpine（ca-certificates + tzdata），
镜像约 15 MB，常驻内存 ~10 MB。

## 一条命令跑起来

```bash
docker run -d \
  --name shouhu-relay \
  --restart unless-stopped \
  -p 8898:8898 \
  -v $PWD/relay-data:/data \
  -e TZ=Asia/Shanghai \
  ghcr.io/your-name/shouhu-relay:latest
```

然后浏览器打开 `http://<服务器IP>:8898`：

1. 首页是家人页；
2. 点「中继页」进管理台，**先设访问密码**；
3. 设备页「二维码地址」填你能从外网访问到的地址（域名或公网 IP + 端口），
   不填的话二维码里写的是局域网地址，出门就用不了。

## docker compose（推荐）

仓库根目录有 `docker-compose.yml`：

```bash
docker compose up -d          # 首次会自己构建镜像
docker compose logs -f        # 看日志
docker compose down           # 停止（数据在 ./relay-data，不会丢）
```

改端口就改 `ports` 左边：`"8080:8898"`。

## 数据与备份

| 路径 | 内容 |
|---|---|
| `/data/config.json` | 端口、设备列表、令牌、访问密码、对外地址 |
| `/data/points.jsonl` | 轨迹点，约 8.4 KB/天（10 分钟一报），默认保留 90 天 |
| `/data/state.json` | 最近一次上报的状态快照 |

备份就是打包这个目录：

```bash
docker run --rm -v $PWD/relay-data:/data -v $PWD:/backup alpine \
  tar czf /backup/relay-$(date +%F).tar.gz -C /data .
```

**迁移**：把目录拷到新机器、起容器即可，设备令牌不变，老人端不用重新扫码。

## 升级

```bash
docker compose pull && docker compose up -d
```

或者管理台点「检查新版本」，看有没有新版（见下）。

> 中继的版本兼容策略：**新中继永远兼容旧版老人端和家人端**。
> 协议只加字段、不改语义，旧端连上来照常工作。所以升级顺序无所谓，
> 家人端装不了新版也不影响。

## 反代 / 只暴露一个端口

中继自己就是 HTTP 服务，可以直接给 Nginx / Caddy / Traefik：

```nginx
location / {
    proxy_pass http://127.0.0.1:8898;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;          # /api/wait 是长轮询，别缓冲
    proxy_read_timeout 90s;
}
```

**长轮询那条 `/api/wait` 必须关 buffering，超时要大于 25 秒**，否则家人端会一直"离线"。

反代后如果二维码地址不对，去设备页把「二维码地址」手动填成对外地址（写进 `config.json` 的 `public_base`）。

## 健康检查

```bash
curl http://127.0.0.1:8898/api/health
{"ok":true,"version":"1.15.0","time":1762900000000}
```

镜像自带 `HEALTHCHECK`，`docker ps` 里能看到 `healthy`。

## 常见问题

| 现象 | 原因 |
|---|---|
| 家人端一直"离线" | `/api/wait` 被反代缓冲了，或超时太短 |
| 老人端上报 401 | 令牌不对：中继是新实例，老人端要重新扫码 |
| 二维码扫出来是 192.168.x.x | 没配「二维码地址」（`public_base`） |
| 容器起来就退出 | 挂载目录不可写（容器内是 uid 1000 的 app 用户），`chown -R 1000:1000 relay-data` |
| 中文乱码 | `TZ` 设了但没装 tzdata —— 官方镜像已带 |

## 自己构建

```bash
# 单平台（本地验证）
docker build -t shouhu-relay:1.15.0 --build-arg VERSION=1.15.0 elder-guardian/relay

# 多平台（amd64 / arm64 / armv7）并推送
docker login ghcr.io
IMAGE_PREFIX=ghcr.io/your-name/shouhu-relay deploy/docker/build.sh 1.15.0 --push
```
