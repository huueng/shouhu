#!/bin/sh
# 构建多架构中继镜像（amd64 / arm64 / armv7）
#
# 需要 docker buildx（Docker Desktop 自带）：
#   docker buildx create --use --name shouhu-builder
#
# 用法：
#   deploy/docker/build.sh 1.14.1                 # 只构建到本地
#   deploy/docker/build.sh 1.14.1 --push          # 推到 $IMAGE_PREFIX
#
# 推镜像前先登录：docker login ghcr.io
set -eu

VER=${1:?用法: $0 <版本号> [--push]}
PUSH=${2:-}
IMAGE_PREFIX=${IMAGE_PREFIX:-ghcr.io/your-name/shouhu-relay}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CONTEXT="$ROOT/elder-guardian/relay"

if [ "$PUSH" = "--push" ]; then
    OUT="--push"
else
    OUT="--load"
fi

# arm/v7 对应斐讯 K2P B1 那种 ARMv7 设备；arm64 是飞牛/树莓派；amd64 是普通服务器
PLATFORMS="linux/amd64,linux/arm64,linux/arm/v7"

echo "==> 架构: $PLATFORMS"
echo "==> 版本: $VER"
echo "==> 镜像: $IMAGE_PREFIX:$VER"

# 注意：--load 不支持多平台，本地验证时改成单平台：
if [ "$PUSH" != "--push" ]; then
    docker buildx build \
        --platform linux/amd64 \
        --build-arg VERSION="$VER" \
        -t "$IMAGE_PREFIX:$VER" -t "$IMAGE_PREFIX:latest" \
        --load "$CONTEXT"
    echo "已构建本地镜像 $IMAGE_PREFIX:$VER（单平台 amd64）"
    exit 0
fi

docker buildx build \
    --platform "$PLATFORMS" \
    --build-arg VERSION="$VER" \
    -t "$IMAGE_PREFIX:$VER" -t "$IMAGE_PREFIX:latest" \
    --push "$CONTEXT"

echo "已推送 $IMAGE_PREFIX:$VER（多平台）"
