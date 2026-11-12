# 飞牛 APP 里打不开应用？因为走的是「统一网关」，不是 IP+端口

版本：中继 **1.12.0**

---

## 一、你看到的现象，本质是两种访问模型

飞牛里的应用有两种被打开的方式：

| 模型 | URL 长什么样 | 谁校验登录 | 适用 |
|---|---|---|---|
| **端口服务**（我们之前用的） | `http://<NAS>:8898/` | 应用自己（我们的访问密码 / ?k=令牌） | 局域网、反代、Lucky |
| **统一网关**（你看到的那些） | `https://<NAS 域名>:<端口>/app/<appname>/` | **飞牛先校验 NAS 登录态**，再把请求转给应用 | 飞牛 APP、桌面图标、外网也能用 |

你看到的 `https://office.app.5ddd.com:端口/app/应用名` 就是第二种：
域名是飞牛的，路径是 `/app/<应用名>`，**跟 IPv6、跟 8898 都没有关系**。

所以"手机飞牛 APP 里打不开"不是网络问题 —— 是之前那个 fpk 只声明了端口入口，
飞牛 APP 只会按端口去连 `http://<某个地址>:8898`，而 APP 里有自己的网络上下文
（局域网/公网切换、FN Connect），自然就连不上。

官方文档：
- 应用入口：https://developer.fnnas.com/docs/core-concepts/app-entry/
- 统一网关：https://developer.fnnas.com/docs/core-concepts/gateway-registration/
- Manifest：https://developer.fnnas.com/docs/core-concepts/manifest/

---

## 二、网关的工作方式（官方原文提炼）

1. 应用在 `app/ui/config` 里声明 `gatewayPrefix`（公开路径）和 `gatewaySocket`（Socket 文件名）
2. 应用服务**在 `target/` 下建一个 Unix Socket**（一般是 `app.sock`）
3. 用户打开 `/app/elderrelay`
4. 飞牛校验会话，然后把请求转发到 `/var/apps/elderrelay/target/app.sock`
5. 转发时会带上 `X-Trim-Userid` / `X-Trim-Isadmin` / `X-Trim-Username`

两个环境变量是飞牛注入的：

```
FNNAS_GATEWAY_SOCKET=/var/apps/elderrelay/target/app.sock
FNNAS_GATEWAY_PREFIX=/app/elderrelay
```

`protocol` 和 `port` 在网关入口下**会被忽略**，不参与路由。

---

## 三、这次改了什么

### 1. 同时监听 Socket 和端口

老人端还在往 `8898`（或者你的 Lucky / CF 隧道）上报，这条不能断。所以是**两个监听、一份数据**：

```go
// TCP：老人端上报、Lucky 反代、局域网直连
ln, _ := net.Listen("tcp", addr)
go srv.Serve(ln)

// 飞牛网关：同一个 Server 再挂一个 Unix Socket
if sp := gatewaySocketPath(); sp != "" {
    gln, _ := listenGatewaySocket(sp) // chmod 0770，启动前清掉死 socket
    go srv.Serve(gln)
}
```

端口起不来时不再直接退出：只要网关 Socket 在，应用照样能用（日志里会写清楚）。

### 2. 剥前缀

网关进来的是 `/app/elderrelay/api/status`，路由只认 `/api/status`：

```go
if strings.HasPrefix(path, gatewayPrefix) { path = strings.TrimPrefix(path, gatewayPrefix) }
```

前缀记进 request context —— 不能用一个全局布尔，同一个进程同时服务 Socket 和 TCP，
必须按请求区分"这次是不是从网关来的"。

### 3. 页面改成相对路径 + 注入 `<base>`

这是最容易踩的坑：页面里原来写的是 `fetch('/api/status')`，在网关下会打到
`https://域名/api/status`（根上什么都没有，404）。

现在的做法：

- 页面里的接口地址全部改成相对路径（`fetch('api/status')`）
- 每个页面注入 `<base href="/app/elderrelay/">`（直连时是 `<base href="/">`）

于是同一份 HTML 在两种入口下都对：

```
网关：/app/elderrelay/ + api/status  → /app/elderrelay/api/status ✓
直连：/                 + api/status  → /api/status ✓
```

家人页的"设备地址"（`base`）也跟着带上前缀，页面里那些 `base + '/api/...'` 才不会跑偏。

### 4. 网关入口免二次密码

网关已经校验过 NAS 登录态了；在飞牛 APP 的窗口里再弹一次浏览器密码框，体验很差，
而且没增加安全性（Socket 只有网关能连）。所以带前缀进来的请求跳过 Basic 校验，
**直连 IP:8898 时照旧要密码**。

### 5. 桌面入口改成网关

```json
{
  ".url": {
    "elderrelay.Application": {
      "title": "守护中继",
      "icon": "images/icon_{0}.png",
      "type": "iframe",
      "protocol": "",
      "gatewayPrefix": "/app/elderrelay",
      "gatewaySocket": "app.sock",
      "url": "/app/elderrelay",
      "allUsers": true
    }
  }
}
```

`type` 用 `iframe`：在飞牛桌面窗口里打开，不用另开标签页。

---

## 四、装完怎么验证

1. 装 `elderrelay-1.12.0.fpk`，启动应用
2. 看日志（应用数据目录 `app.log`）：

```
[relay] 飞牛网关：前缀="/app/elderrelay" socket="/var/apps/elderrelay/target/app.sock"
[relay] 飞牛网关 Socket 就绪：/var/apps/elderrelay/target/app.sock（前缀 /app/elderrelay）
[relay] 监听 :8898
```

三行都在，说明两条路都通了。

3. 浏览器里访问：

```
https://<你的飞牛域名>:<端口>/app/elderrelay/
```

应该直接看到家人页（不用输密码，飞牛已经登录过了）。

4. 飞牛 APP / 桌面里点「守护中继」图标，应该在窗口里打开同一个页面。

5. **老人端不受影响**：它还在往原来的上报地址（IPv6 / Lucky / CF）发，
   中继照旧在 8898 上收。可以在家人页「体检」里确认 `最后上报` 还在变。

---

## 五、兼容与回退

- **fnOS 版本太老、不认 gateway 字段**：图标可能打不开（白屏/404）。
  临时用 `http://<NAS>:8898` 直连，或把 `ui/config` 改回端口入口：

  ```json
  "type": "url", "protocol": "http", "port": "8898", "url": "/"
  ```

- **只想用网关、不想暴露端口**：把 `config.json` 的 `bind` 改成 `127.0.0.1:8898`，
  这样只有本机和反代能连，局域网连不进来（但 Lucky / CF 隧道照样工作）。

- **应用停了网关会 502**：网关连的是 Socket，进程不在就没有监听。
  这是预期的，飞牛应用中心里点「启动」即可。

---

## 六、为什么不用 index.cgi

飞牛还有一条 `index.cgi` 的路（`/cgi/ThirdParty/{appname}/index.cgi/`），
但它每次请求都要起一个进程，而且**不支持 WebSocket**。中继是常驻服务，
还要跑长轮询（`/api/wait`），所以选统一网关。

---

## 七、1.12.1：网关下点顶部标签会跳出应用

装上 1.12.0 之后，飞牛 APP 和电脑端都能打开家人页了，但：

- 点「家人页」→ 页面变成飞牛桌面（第三张截图）
- 点「中继页」→ 飞牛自己的 404「这里暂时没有装入页面」（第二张截图）

原因不在网关，在**标签的链接写死了绝对路径**：

```html
<!-- 之前 -->
<a href="/">家人页</a><a href="/admin">中继页</a>
```

在网关下页面挂在 `https://<域名>/app/elderrelay/`，所以：

| 点击 | 实际去了 | 结果 |
|---|---|---|
| 家人页 | `https://<域名>/` | 飞牛接管，显示桌面 |
| 中继页 | `https://<域名>/admin` | 飞牛不认识这个路径，回自己的 404 |

直连端口（`http://NAS:8898`）时这两条恰好是对的，所以只在这个入口暴露。

修法：标签链接带上本次访问的前缀（直连是空串）：

```html
<a href="/app/elderrelay/">家人页</a><a href="/app/elderrelay/admin">中继页</a>
```

顺带两处：

1. 标签原来是 JS 里 `bar.innerHTML="..."` 注入的，源码里是一堆 `\"`，测试和肉眼排查都难受 ——
   改成服务端直接插到 `<body>` 开头（管理台插在 `.wrap` 里面，才对得齐 `max-width`）
2. 家人页的接口地址改成在浏览器里按 `location.origin + 前缀` 计算。
   网关请求是从 Unix Socket 进来的，`r.TLS` 是空的、`X-Forwarded-Proto` 也不一定有，
   服务端很容易拼出 `http://` 交给一个 https 页面 —— 浏览器按混合内容拦掉，
   症状是"页面开着但数据全空"，比 404 更难查

### 验证

```bash
# 网关入口
curl "http://127.0.0.1:8898/app/elderrelay/"      # 200，标签指 /app/elderrelay/ 与 /app/elderrelay/admin
curl "http://127.0.0.1:8898/app/elderrelay/admin" # 200，同样带前缀
# 直连入口（不带前缀时仍是 / 和 /admin）
curl "http://127.0.0.1:8898/admin"
```

单测 `TestTabsKeepGatewayPrefix` 把这条钉住了：网关下不允许出现裸的 `href="/"` / `href="/admin"`，
直连下必须还是它们。
`TestFamilyBootUsesLocationOrigin` 钉住接口地址走 `location.origin`。

### 装完自检

1. 飞牛 APP / 桌面点「守护中继」→ 家人页
2. 点「中继页」→ 应该原地切到管理台（地址栏变成 `.../app/elderrelay/admin`）
3. 再点「家人页」→ 回到家人页
4. `https://<域名>/app/elderrelay/api/health` 应显示 `"version":"1.12.1"`
