# 参与开发

这个项目的规模不大，但有几条规矩能让改动不把别人（和未来的自己）坑到。

## 三条铁律

**1. 本地改，本地验证，再推送。**
不要直接在 GitHub 网页上改代码。三端（老人端 / 家人端 / 中继）经常要一起动，
而且中继能编译、能跑测试，推上去 CI 还会再跑一遍——网页改等于跳过所有检查。

唯一例外：README、文档里的错别字，网页上改更快，改完记得确认 Actions 没红。

**2. 协议只加不改。**
老人端可能几个月不更新，家人端也常常一装不动。
中继的接口、二维码内容、上报字段：只能加，不能改语义、不能删。
改协议前先读 [docs/37-版本兼容策略.md](docs/37-版本兼容策略.md)。

**3. 不提交产物和个人数据。**
`dist/`、`.build/`、`.toolchain/`、`logs/`、`资料/`、`*.exe` 都在 `.gitignore` 里。
产物走 Release，不要塞进仓库历史（二进制进了 git 就删不掉了）。

## 本地构建

### 中继（Go 1.22）

```bash
cd elder-guardian/relay
go vet ./... && go test ./...                    # 改完必跑
go build -trimpath -ldflags "-s -w -X main.Version=1.15.1" -o ../../.build/relay-arm64 .
```

交叉编译：

| 目标 | 环境变量 |
|---|---|
| aarch64 | `GOOS=linux GOARCH=arm64 CGO_ENABLED=0` |
| armv7 | `GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0` |
| mipsel | `GOOS=linux GOARCH=mipsle GOMIPS=softfloat CGO_ENABLED=0` |
| amd64 | `GOOS=linux GOARCH=amd64 CGO_ENABLED=0` |

### 打包

```bash
python tools/build-ipk.py 1.15.1                  # OpenWrt，默认只出 arm64
python tools/build-ipk.py 1.15.1 mipsle --upx     # mipsel_24kc，UPX 压到 ~30%
python tools/build-ipk.py 1.15.1 armv7 --upx      # arm_cortex-a7 / a9
bash  tools/build-fpk.sh 1.15.1                   # 飞牛 fpk（会自己 cp 家人页）
```

`build-ipk.py` 会校验 ELF 头，架构对不上直接报错——别把 arm64 打进 mipsel 包。

### 安卓端（Java 8 / compileSdk 29）

```bash
cd elder-guardian
JAVA_HOME=C:\Android\JDK\jdk-11.0.25+9 ./gradlew.bat :family:assembleRelease --offline
JAVA_HOME=C:\Android\JDK\jdk-11.0.25+9 ./gradlew.bat :app:assembleRelease    --offline
```

两个包都要 **release**（带签名配置）。debug 包会被华为「纯净模式」拦（`testOnly`）。

家人端是 WebView 壳，界面全在 `elder-guardian/family/src/main/assets/index.html`；
中继的网页版用的是同一份文件（`build-fpk.sh` 会自动复制），改一处别只改一半。

## 版本与发版

- 加功能 → 次版本号；修 bug → 修订号；破坏性改动 → 主版本（目前承诺 1.x 内不出现）
- 打 tag 才发版：

```bash
git tag -a relay-v1.15.1 -m "说明"
git push origin relay-v1.15.1
```

CI 会自己出 4 个架构的二进制 + 5 个 ipk 挂到 Release 上，并构建多架构 Docker 镜像推到 GHCR。

## 提交信息

中文，说清**为什么**改。只写“修复 bug”没有意义，写“为什么以前会错、现在怎么对的”。

```
中继 1.15.1：二维码加大到 400px，点击可全屏放大

家人端那个码内容更长（协议头 + 转义地址 + 称呼），点阵更密，
原来 320px 在手机上留给每个模块的像素不够。现在点开全屏是原来的一倍。
```

## CI 做什么

| 工作流 | 触发 | 内容 |
|---|---|---|
| `relay` | push / PR | `go vet` + `go test` + 四架构交叉编译；打 tag 时再打 ipk 并发布 Release |
| `docker` | 打 tag | buildx 三平台（amd64/arm64/armv7）推到 GHCR |

本机没有 Docker，容器镜像是 CI 构建的；改 Dockerfile 后看那次运行的结果，别凭感觉。

## 已知的坑（都写进 docs 了，别重复踩）

- OpenWrt 的 ipk 必须是 `gzip(tar)`，不能是 ar 归档；control 里不能有空 `Depends:`；
  tar 里不能带 `./` 目录项 → [docs/33](docs/33-OpenWrt插件.md)
- Windows 上给路由器传文件，`scp` 必须加 `-O`（dropbear 没装 sftp-server）
- `go test` 里改了坐标之类的示例值，注意别连带改掉 `HOME_POINT` 这种真正的常量
- 家人页的二维码和上报码是两个码，别混 → [docs/38](docs/38-零基础入门.md) 第 12 节

## 提交 PR

1. 一个 PR 只做一件事；
2. 说明动机（遇到什么问题），别只贴 diff；
3. 涉及协议的先在 issue 里对一下，别写完再改。
