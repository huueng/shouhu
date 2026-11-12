# DDNS 模块调研（Dynv6 / Cloudflare / 阿里云 / 腾讯云）

> 背景：老人机当服务器（方案 B）最大的不稳定因素是**地址会变**。  
> 手机 IPv6 用隐私扩展，地址会周期性轮换；家宽重播号前缀也会变。  
> DDNS 就是把这个变化同步到域名上，家人端只需要记住一个域名。

## 一、四家对比

| 项目         | Dynv6                                                 | Cloudflare                           | 阿里云解析                     | 腾讯云 DNSPod                 |
| ---------- | ----------------------------------------------------- | ------------------------------------ | ------------------------- | -------------------------- |
| 免费额度       | ✅ 完全免费（含 IPv6）                                        | ✅ 免费套餐够用                             | 有免费版（域名需备案解析）             | 有免费版                       |
| 认证方式       | HTTP Basic（用户名/密码）或 token                             | API Token（Bearer）                    | AccessKey + 签名（HMAC-SHA1） | SecretId/SecretKey + 签名 v3 |
| API 难度     | ★☆☆ 一个 GET 就行                                         | ★★☆ REST + JSON                      | ★★★★ 要自己算签名               | ★★★★ 要自己算签名                |
| 更新端点       | `https://dynv6.com/api/update?hostname=&token=&ipv6=` | `PATCH /zones/{id}/dns_records/{id}` | `UpdateDomainRecord`      | `ModifyDynamicDNS`         |
| IPv6(AAAA) | ✅ 原生支持                                                | ✅                                    | ✅                         | ✅                          |
| 频率限制       | 宽松                                                    | 1200 次/5 分钟（远超需要）                    | 有 QPS 限制                  | 有 QPS 限制                   |
| 适合谁        | 个人自用、无域名                                              | 已有域名、想要规范 API                        | 国内域名 + 备案                 | 国内域名                       |

**结论：先做 Dynv6（免费、最简单、专为动态 IP 设计），再做 Cloudflare（覆盖面广），国内两家放最后。**  
理由：阿里/腾讯的请求签名要自己实现 HMAC-SHA1/v3，代码量是 Dynv6 的十倍，而收益（国内解析更快）在"家人查个位置"这个场景里几乎感知不到。

## 二、调用示例

### Dynv6（推荐）

一次 GET 即可，无需算签名：

```
https://dynv6.com/api/update?hostname=elder.dynv6.net&token=XXXX&ipv6=2408:xxxx:...
```

返回 `addresses updated` 表示成功，`no such host` 表示域名没先建好。

### Cloudflare

两步：查到 record id → PATCH 更新。

```
GET  https://api.cloudflare.com/client/v4/zones/{zone_id}/dns_records?name=elder.example.com&type=AAAA
PATCH https://api.cloudflare.com/client/v4/zones/{zone_id}/dns_records/{record_id}
     Authorization: Bearer <API_TOKEN>
     {"type":"AAAA","name":"elder.example.com","content":"2408:...","ttl":120}
```

注意：Cloudflare 的橙色云（代理）**不支持任意端口**，我们的 8899 必须走 DNS only，否则连不上。

### 阿里云

```
POST https://alidns.aliyuncs.com/?Action=UpdateDomainRecord
     &RecordId=xxx&RR=elder&Type=AAAA&Value=2408:...
     &Format=JSON&Version=2015-01-09&AccessKeyId=...&Signature=...(HMAC-SHA1)
```

### 腾讯云 DNSPod

```
POST https://dnspod.tencentcloudapi.com/
     X-TC-Action: ModifyDynamicDNS
     X-TC-Version: 2021-03-23
     {"Domain":"example.com","SubDomain":"elder","RecordId":123,"RecordLine":"默认","Value":"2408:..."}
```

## 三、App 内模块设计（建议实现）

```
DdnsUpdater（前台服务，可关）
  每 5 分钟 / 网络变化时：
    1. 取本机当前公网 IPv6（枚举 wlan0/rmnet 的非 fe80 地址，优先 2408/2409/240e 等国内段）
    2. 与上次成功上报的地址比对，变了才发请求（省请求次数）
    3. 调 provider API（Dynv6 一条 GET；其他走统一接口）
    4. 结果写日志 + 自检页显示"最近更新时间 / 当前域名解析地址"
```

配置项（设置 → 远程与联网 → DDNS）：

| 项    | 说明                         |
| ---- | -------------------------- |
| 启用   | 开关                         |
| 服务商  | Dynv6 / Cloudflare / 自定义   |
| 域名   | `elder.dynv6.net`          |
| 凭据   | Dynv6 token / CF API Token |
| 记录类型 | AAAA（默认）/ A / 双栈           |
| 间隔   | 5 / 10 / 30 分钟             |

**自定义 Webhook** 这一项值得单独留：URL 模板 `https://my.host/update?ip={ip}`，  
这样任何小众服务商或你自己的脚本都能接进来，不用为每家写适配。

## 四、必须提前知道的坑

1. **手机 IPv6 会轮换（隐私扩展）**，通常几小时到一天。DDNS 更新再快也会有"刚换、还没更新"的空窗期。
   - 缓解：间隔压到 5 分钟；或让家人端在连不上时自动重试（家人端已经会提示）。
   - 更稳的做法：**家里路由器做 DDNS**，手机在家走局域网，出门再靠短信兜底。
2. **运营商可能封入站端口**。IPv6 虽然公网可达，但部分省份对移动数据入站有限制，实测为准。
3. **iOS/某些浏览器对 http://[IPv6] 不友好**，家人端 App 用原生 HTTP 请求（不受浏览器限制），网页版可能受限——这也是把家人端做成 App 的另一个理由。
4. **安全**：域名一旦挂到公网，扫描器会来敲。所以 `/loc` 必须带密钥（已实现），并建议：
   - 只开 8899，不要复用其他端口
   - 密钥 8 位以上、定期更换（设置页可一键换）
   - 服务只在需要时开（现在就是手动开关）

## 五、优先级建议

| 顺序 | 事项                       | 说明                  |
| -- | ------------------------ | ------------------- |
| 1  | Dynv6 更新器                | 半天，覆盖最主流的免费场景       |
| 2  | 自定义 Webhook              | 1 小时，覆盖所有小众服务商      |
| 3  | Cloudflare               | 半天，给已有域名的人用         |
| 4  | 阿里 / 腾讯                  | 1–2 天（签名麻烦），有明确需求再做 |
| 5  | 自检页加 "域名解析地址 vs 本机地址" 比对 | 半小时，排障神器            |



> 现阶段手机没插卡，公网 IPv6 还得等实测。建议等有卡之后先手动验证一遍  
> 「移动数据下 IPv6 能不能被外部访问」，再决定要不要投入 DDNS 模块——  
> 如果运营商封了入站，DDNS 做得再漂亮也没用。


---

## 六、已实现（2026-10-25）

按上面的结论先落了 Dynv6，代码在 `app/src/main/java/com/elder/launcher/DdnsUpdater.java` + `DdnsService.java`。

**配置**（已写入默认值，设置页可改）：

| 项 | 值 |
|---|---|
| 域名 | `elder.dynv6.net` |
| Token | 见 `DdnsUpdater.DEFAULT_TOKEN`（⚠️ 私人凭据，开源前务必清空） |
| 检查间隔 | 5 分钟 + 网络切换立即触发 |
| 记录类型 | AAAA（IPv6） |

**已实测通过**：

```
GET https://dynv6.com/api/update?hostname=elder.dynv6.net&token=***&ipv6=2408:8220:...:e97f
→ addresses updated

nslookup -type=AAAA elder.dynv6.net 223.5.5.5
→ 2408:8220:7f17:c0a1:c69f:4cff:fe83:e97f
```

应用内日志：`ElderDdns: 更新 elder.dynv6.net -> 2408:...:e97f ：成功：addresses updated`

### 顺带修掉的一个真 bug：定位服务其实只监听了 IPv4

排查远程访问时发现：从外部用 IPv6 连 8899 会被 RST（`Connection refused`），IPv4 正常。

根因：`new InetSocketAddress(PORT)` 里的地址是 `Inet4Address`，Linux 上会建 **AF_INET** 套接字，
即使系统有公网 IPv6、域名也解析对了，家人端照样连不上。

修法（`LocateServer.startLoop`）：分别绑 `[::]`（IPv6 通配，v6only=0 时同时服务 IPv4）、
`0.0.0.0`（IPv4 通配，若上一步已是双栈会 EADDRINUSE，忽略），再兜底绑本机具体 IPv6 地址。
每个套接字一个 accept 线程。

### 地址选择：优先"稳定地址"

手机上有两个公网 IPv6：临时地址（隐私扩展，几小时换一次）和基于 MAC 的稳定地址（含 `ff:fe`）。
DDNS 必须记稳定的那个，否则域名会跟着临时地址漂。`DdnsUpdater.pickIpv6()` 已按
「公网段 + 含 ff:fe」打分取最高。

### 还需要注意

- **路由器 IPv6 防火墙**默认会拦入站，需要在路由器上放行 8899（或关闭 IPv6 防火墙）。
- 部分运营商对移动数据入站有限制，实测为准。


---

## 七、换用 Cloudflare + us.kg（已支持，含橙色云的坑）

程序里已经做了**服务商切换**，设置页「远程与联网 → DDNS → 修改域名/Token」第一个下拉框可选
`Dynv6` / `Cloudflare`，换域名不用重新装机。

### 代码怎么工作的

```
Cloudflare 流程：
1. 找 zone id：先看你填的「区域名」，没有就按域名的各级后缀依次问
   （elder.foo.us.kg → foo.us.kg → us.kg）
2. 查这条 AAAA 记录（GET /zones/{id}/dns_records?type=AAAA&name=...）
3. 有 → PATCH 更新 content；没有 → POST 新建
4. 每条都强制 proxied=false（灰云）
```

### ⚠️ 橙色云（代理）为什么不能用

Cloudflare 免费版代理只转发固定端口：**80 / 443 / 2053 / 2083 / 2087 / 2096 / 8443**。
我们的定位服务是 **8899**，不在列表里。

更致命的是：开了橙色云，`AAAA` 会被换成 Cloudflare 自己的地址（104.x / 172.x），
家人端拿到的根本不是老人机的地址，**永远连不上**。

所以这条记录必须是 **灰色云朵（DNS only）**。程序每次 PATCH 都会把 `proxied` 写回 `false`，
就算你手抖点成橙色，也会被纠正过来。

### 从 Dynv6 换到 us.kg + Cloudflare 的步骤

1. **在 us.kg 申请域名**（例如 `myname.us.kg`），把它的 NS 指到 Cloudflare 给的两个地址
2. **Cloudflare 添加站点**，等状态变成 Active（几分钟到几小时）
3. **建 API Token**：My Profile → API Tokens → Create Token
   - 权限：`Zone → Zone → Read` + `Zone → DNS → Edit`
   - Zone Resources：Include → 指定这个域名
4. **手机上改配置**：设置 → 远程与联网 → DDNS → 修改域名/Token
   - 服务商：Cloudflare
   - 域名：`elder.myname.us.kg`（要同步的那条完整记录名）
   - Token：上一步拿到的
   - 区域名：留空让它自动识别，或填 `myname.us.kg`
5. **点「立即更新一次」**，看到「成功：Cloudflare 已新建 AAAA（灰云）」就成了
6. **验证**：

```
powershell -ExecutionPolicy Bypass -File tools\check-remote.ps1 -Key 666666 -Domain elder.myname.us.kg
```

第一步能解析出 IPv6、第二步端口可达，就说明换域名成功。

### 建议：留备用通道

dynv6 偶尔不稳是真的。稳妥的是四级冗余：

| 层级 | 手段 | 依赖 |
|---|---|---|
| 1 | DDNS 域名（dynv6 或 CF） | DNS 正常 |
| 2 | 直接填 IPv6 地址（家人端支持） | 地址没变 |
| 3 | 短信「位置」查询 | 完全独立 |
| 4 | 响铃挂断触发定位 | 完全独立 |

域名只是"好记"，**真正的兜底是短信**，域名挂了不用慌。

> 说明：Cloudflare 分支已完成并编译通过，但**还没有用真实 token 跑过**（等你申请 us.kg 后再实测）。
> 接口地址与返回结构已用错误 token 验证过；失败时会把 Cloudflare 的原始错误信息显示出来，方便排查。
