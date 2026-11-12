# 守护中继 · 容器镜像
#
#   docker build -t shouhu-relay:1.14.1 --build-arg VERSION=1.14.1 .
#   docker run -d -p 8898:8898 -v $PWD/data:/data --name shouhu-relay shouhu-relay:1.14.1
#
# 多架构（amd64 / arm64 / armv7）见 deploy/docker/build.sh。

# ---------- 构建 ----------
FROM golang:1.22-alpine AS build
WORKDIR /src

# 先只拷 go.mod/go.sum，依赖没变时这层能复用缓存
COPY go.mod go.sum ./
RUN go mod download

COPY . .
ARG VERSION=dev
# 静态链接：运行时镜像里没有 libc，也不需要
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath \
        -ldflags "-s -w -X main.Version=${VERSION}" -o /out/elderrelay .

# ---------- 运行 ----------
FROM alpine:3.20

# ca-certificates：中继要访问 GitHub 检查新版本；tzdata：日志/时间用本地时区
RUN apk add --no-cache ca-certificates tzdata \
 && addgroup -S -g 1000 app \
 && adduser -S -u 1000 -G app app \
 && mkdir -p /data \
 && chown app:app /data

COPY --from=build /out/elderrelay /usr/local/bin/elderrelay

ENV ELDER_DATA=/data \
    TZ=Asia/Shanghai

VOLUME ["/data"]
EXPOSE 8898
USER app

# /api/health 是公开接口（不需要令牌），适合做健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8898/api/health >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/elderrelay"]
