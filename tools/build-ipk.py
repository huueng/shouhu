#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把中继打包成 OpenWrt / ImmortalWrt 的 .ipk

为什么不用 OpenWrt SDK：中继是**静态** Go 二进制（CGO_ENABLED=0），不依赖
musl/openssl，所以不需要整套 SDK 和交叉工具链，按 ipk 格式装进去即可。

## ipk 到底是什么格式（踩过的坑）

两种都不一样：
  老思路（Debian 风格）：ar 归档，成员 debian-binary / control.tar.gz / data.tar.gz
  现在 OpenWrt（23.05+ / 24.10 实测）：**gzip 包着的 tar**，成员依次是
      ./debian-binary
      ./data.tar.gz
      ./control.tar.gz

ImmortalWrt 24.10.6（opkg 2024-10-16 构建）只认后者：喂 ar 格式的包会先报
`pkg_init_from_file: Malformed package file`（它把 control 抽成了空流，于是
"没有 Package 字段"）。所以这里直接对齐官方产物结构，别再走 ar。
"""

import io
import os
import shutil
import subprocess
import sys
import tarfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELAY = os.path.join(ROOT, 'elder-guardian', 'relay')
OPENWRT = os.path.join(RELAY, 'openwrt')


def _entry(tar, name, data, mode=0o644, mtime=None):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = int(mtime if mtime is not None else time.time())
    info.uid = 0
    info.gid = 0
    info.uname = ''      # 官方包这两项是空的，别写 root，保持字节级一致
    info.gname = ''
    tar.addfile(info, io.BytesIO(data))


def _dir(tar, name, mode=0o755, mtime=None):
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    info.mode = mode
    info.mtime = int(mtime if mtime is not None else time.time())
    info.uid = 0
    info.gid = 0
    info.uname = ''
    info.gname = ''
    tar.addfile(info)


def tar_gz(entries):
    """entries: [('dir', 路径) | (路径, bytes, mode)] -> gzip 压缩的 tar"""
    import gzip
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w', format=tarfile.GNU_FORMAT) as tar:
        for item in entries:
            if item[0] == 'dir':
                _dir(tar, item[1])
            else:
                name, data, mode = item
                _entry(tar, name, data, mode)
    return gzip.compress(buf.getvalue(), 9, mtime=0)


def check_elf(data, goarch):
    """确认二进制真的是目标架构（免得把 arm64 打进 mipsel 包，装上去才 exec format error）"""
    if data[:4] != b'\x7fELF':
        return '不是 ELF 文件'
    ei_class = data[4]          # 1=32 位 2=64 位
    ei_data = data[5]           # 1=小端 2=大端
    e_machine = int.from_bytes(data[18:20], 'little' if ei_data == 1 else 'big')
    want = {
        'arm64': (2, 1, 183),
        'arm': (1, 1, 40),
        'mipsle': (1, 1, 8),
        'mips': (1, 2, 8),
    }.get(goarch)
    if want and (ei_class, ei_data, e_machine) != want:
        return 'ELF 头对不上 %s（class=%d endian=%d machine=%d）' % (
            goarch, ei_class, ei_data, e_machine)
    return ''


def upx_pack(bin_path, goarch, level='--best'):
    """UPX 压缩（找不到 UPX 就返回 None，调用方退回原二进制）"""
    candidates = [
        os.path.join(ROOT, '.toolchain', 'upx-4.2.4-win64', 'upx.exe'),
        os.path.join(ROOT, '.toolchain', 'upx', 'upx.exe'),
    ]
    exe = next((c for c in candidates if os.path.exists(c)), None)
    if not exe:
        exe = shutil.which('upx')      # Linux / CI：装在 PATH 里就用
    if not exe:
        print('  （没找到 upx，跳过压缩）')
        return None
    out = bin_path + '.upx'
    shutil.copyfile(bin_path, out)
    r = subprocess.run([exe, level, out], capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(out):
        print('  （upx 压缩失败，用原二进制）')
        return None
    return out


def find_binary(goarch):
    """本地叫 relay-armv7，CI 里 GOARCH=arm 会产出 relay-arm —— 两个名字都认"""
    names = [goarch]
    if goarch == 'armv7':
        names.append('arm')
    for n in names:
        p = os.path.join(ROOT, '.build', 'relay-%s' % n)
        if os.path.exists(p):
            return p
    return os.path.join(ROOT, '.build', 'relay-%s' % goarch)


def build(version, arch, goarch, out_path, use_upx=False):
    bin_path = find_binary(goarch)
    if not os.path.exists(bin_path):
        sys.exit('缺少编译好的二进制：%s' % bin_path)
    binary = open(bin_path, 'rb').read()
    err = check_elf(binary, 'arm' if goarch == 'armv7' else goarch)
    if err:
        sys.exit('%s 校验失败：%s' % (bin_path, err))
    raw_size = len(binary)

    packed = upx_pack(bin_path, goarch) if use_upx else None
    if packed:
        binary = open(packed, 'rb').read()
        print('  UPX：%d -> %d 字节（%.0f%%）' % (raw_size, len(binary),
                                                len(binary) * 100.0 / raw_size))

    init_sh = open(os.path.join(OPENWRT, 'files', 'elderrelay.init'), 'rb').read()
    uci_cfg = open(os.path.join(OPENWRT, 'files', 'elderrelay.config'), 'rb').read()

    postinst = '''#!/bin/sh
[ -n "${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/elderrelay enable >/dev/null 2>&1
/etc/init.d/elderrelay restart >/dev/null 2>&1
echo "守护中继已启动，默认端口 8898，数据目录 /etc/elderrelay"
exit 0
'''.encode('utf-8')

    prerm = '''#!/bin/sh
[ -n "${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/elderrelay stop >/dev/null 2>&1
/etc/init.d/elderrelay disable >/dev/null 2>&1
exit 0
'''.encode('utf-8')

    # UCI 配置列进 conffiles：升级时别把用户改过的设置冲掉
    conffiles = '/etc/config/elderrelay\n'.encode('utf-8')

    installed_kb = (len(binary) + 1023) // 1024 + 16
    # 不能写空的 "Depends: "：ImmortalWrt 24.10 的 opkg 解析空依赖列表会直接段错误。
    # 没有依赖就整行不写。
    control = ('Package: elderrelay\n'
               'Version: %s\n'
               'Architecture: %s\n'
               'Installed-Size: %d\n'
               'Section: net\n'
               'Priority: optional\n'
               'Maintainer: elder-guardian\n'
               'Description: 守护中继（老人定位/围栏/远程命令中继服务）\n'
               ' 静态编译，无外部依赖；默认监听 8898，数据在 /etc/elderrelay。\n'
               % (version, arch, installed_kb)).encode('utf-8')

    control_tar = tar_gz([
        ('./control', control, 0o644),
        ('./conffiles', conffiles, 0o644),
        ('./postinst', postinst, 0o755),
        ('./prerm', prerm, 0o755),
    ])

    # 注意：不要往里塞 './' 这类目录项。实测 opkg 取 control 时按"第一个成员"读，
    # 碰到目录项就得到 0 字节的 control（报 Malformed）甚至段错误。
    # 目标目录（/usr/bin、/etc/init.d、/etc/config）本来就在系统里，解包时会自己建父目录。
    data_tar = tar_gz([
        ('./usr/bin/elderrelay', binary, 0o755),
        ('./etc/init.d/elderrelay', init_sh, 0o755),
        ('./etc/config/elderrelay', uci_cfg, 0o644),
    ])

    # 外层：gzip(tar)，顺序必须是 debian-binary -> data.tar.gz -> control.tar.gz
    ipk = tar_gz([
        ('./debian-binary', b'2.0\n', 0o644),
        ('./data.tar.gz', data_tar, 0o644),
        ('./control.tar.gz', control_tar, 0o644),
    ])

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'wb') as f:
        f.write(ipk)
    print('已生成 %s（%.1f MB，架构 %s）' % (out_path, len(ipk) / 1048576, arch))


# opkg 认的架构名（OpenWrt 23.05+/24.10）
ARCHES = {
    'arm64':  ['aarch64_cortex-a53'],                                  # 飞牛 / RAX3000M
    'armv7':  ['arm_cortex-a7_neon-vfpv4', 'arm_cortex-a9_vfpv3-d16'],
    'mipsle': ['mipsel_24kc'],                                         # MT7628 / MT7620 / MT7621
}


if __name__ == '__main__':
    args = [a for a in sys.argv[1:]]
    use_upx = '--upx' in args
    args = [a for a in args if a != '--upx']
    version = args[0] if args and not args[0] in ARCHES else '1.15.0'
    alias = {'arm': 'armv7'}   # 也接受 GOARCH 的叫法
    goarches = [alias.get(a, a) for a in args if a in ARCHES or a in alias] or ['arm64']

    for goarch in goarches:
        for arch in ARCHES[goarch]:
            build(version, arch, goarch,
                  os.path.join(ROOT, 'dist', 'elderrelay_%s_%s.ipk' % (version, arch)),
                  use_upx=use_upx)

    # Arch: all 兜底包（少数固件架构名不在列表里；只能给 arm64 用，别混）
    if 'arm64' in goarches:
        build(version, 'all', 'arm64',
              os.path.join(ROOT, 'dist', 'elderrelay_%s_all.ipk' % version),
              use_upx=use_upx)

