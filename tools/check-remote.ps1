# 远程访问自检：一条命令定位"家人端为什么连不上"
#
# 用法（项目根目录）：
#   powershell -ExecutionPolicy Bypass -File tools\check-remote.ps1 -Key <设备令牌>
#   powershell -ExecutionPolicy Bypass -File tools\check-remote.ps1 -Key <设备令牌> -Domain elder.example.com -Port 8899
#
# 它会依次检查：DNS 有没有 AAAA → 这个 TCP 端口通不通 → 带着密钥能不能取到位置，
# 每一步失败都直接告诉你是哪一环的问题。

param(
    [string]$Domain = "",
    [int]$Port = 8899,
    [string]$Key = "",
    [int]$TimeoutMs = 5000
)

$ErrorActionPreference = "Continue"

# 没有默认域名，避免把某个人的部署地址写死在仓库里
if (-not $Domain) {
    Write-Host "用法: check-remote.ps1 -Domain <对外地址> -Key <设备令牌> [-Port 8898]" -ForegroundColor Yellow
    Write-Host "  例: check-remote.ps1 -Domain shouhu.example.com -Key a1b2c3d4e5f6 -Port 812"
    exit 2
}

function Write-Step($t) { Write-Host ""; Write-Host "== $t" -ForegroundColor Cyan }
function Write-Ok($t) { Write-Host "  [OK] $t" -ForegroundColor Green }
function Write-Bad($t) { Write-Host "  [X] $t" -ForegroundColor Red }
function Write-Note($t) { Write-Host "  [!] $t" -ForegroundColor Yellow }

# ---------- 1. DNS ----------
Write-Step "1/3 解析域名 $Domain"
$v6 = @()
$v4 = @()
try {
    $addrs = [System.Net.Dns]::GetHostAddresses($Domain)
    foreach ($a in $addrs) {
        if ($a.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6) { $v6 += $a }
        elseif ($a.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) { $v4 += $a }
    }
} catch {
    Write-Bad "解析失败：$($_.Exception.Message)"
    Write-Note "域名拼错 / 网络不通 / DNS 被劫持，先换个网络试试"
    exit 1
}

if ($v6.Count -eq 0) {
    Write-Bad "没有 AAAA 记录 —— 域名还没指向 IPv6"
    Write-Note "说明手机上的 DDNS 没跑起来，或者从来没更新成功过。"
    Write-Note "去手机「设置 → 远程与联网 → DDNS 域名」看「上次结果」。"
    exit 1
}
foreach ($a in $v6) { Write-Ok ("IPv6 " + $a.IPAddressToString) }
foreach ($a in $v4) { Write-Note ("另外还有 IPv4 " + $a.IPAddressToString) }

# ---------- 2. TCP ----------
Write-Step "2/3 连接 TCP 端口 $Port"
$target = $v6[0].IPAddressToString
$client = New-Object System.Net.Sockets.TcpClient([System.Net.Sockets.AddressFamily]::InterNetworkV6)
try {
    $ip = [System.Net.IPAddress]::Parse($target)
    $task = $client.ConnectAsync($ip, $Port)
    if (-not $task.Wait($TimeoutMs)) {
        throw [System.TimeoutException]::new("连接超时（$TimeoutMs ms）")
    }
    Write-Ok "端口可达"
} catch {
    $ex = $_.Exception
    while ($ex.InnerException -ne $null) { $ex = $ex.InnerException }
    $err = ""
    if ($ex -is [System.Net.Sockets.SocketException]) { $err = $ex.SocketErrorCode.ToString() }
    $msg = "$($ex.Message) $err"

    if ($err -eq "ConnectionRefused" -or $msg -match "refused|拒绝") {
        Write-Bad "连接被拒绝（RST）：$target`:$Port"
        Write-Note "包已经到了手机，但手机上没程序在 IPv6 的 $Port 端口监听。"
        Write-Note "最常见原因：装的是旧版 APK（只监听 IPv4）。装上 dist\elder-guardian-2.0-release.apk 再试。"
    } elseif ($ex -is [System.TimeoutException] -or $err -eq "TimedOut" -or $err -eq "HostUnreachable") {
        Write-Bad "连不上（超时 / 不可达）：$target`:$Port"
        Write-Note "包根本没到手机。按顺序排查："
        Write-Note "  1) 路由器 IPv6 防火墙：放行 TCP $Port 入站（不少路由器要整条关掉 IPv6 防火墙）"
        Write-Note "  2) 运营商是否封了移动数据入站"
        Write-Note "  3) 域名指向的地址是不是手机当前地址（手机上重开一次「DDNS → 立即更新」）"
    } else {
        Write-Bad ("连接失败：" + $msg)
    }
    $client.Dispose()
    exit 1
}

# ---------- 3. 取位置 ----------
if ([string]::IsNullOrWhiteSpace($Key)) {
    Write-Note "没给 -Key，跳过业务校验（端口已经通了）"
    $client.Dispose()
    exit 0
}

Write-Step "3/3 带密钥请求 /loc"
try {
    $stream = $client.GetStream()
    $stream.ReadTimeout = $TimeoutMs
    $path = "/loc?key=" + [System.Uri]::EscapeDataString($Key)
    $req = "GET $path HTTP/1.1`r`nHost: [$target]`r`nConnection: close`r`n`r`n"
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($req)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()

    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    $raw = $reader.ReadToEnd()
    $reader.Dispose()

    $body = $raw
    $idx = $raw.IndexOf("`r`n`r`n")
    if ($idx -ge 0) { $body = $raw.Substring($idx + 4) }

    if ($raw -match "HTTP/1\.1 403") {
        Write-Bad "密钥不对（403）"
        Write-Note "手机上的密钥和你填的不一致：去「设置 → 定位服务」看那一行，或点「改密钥」重设。"
        exit 1
    }
    try {
        $json = $body | ConvertFrom-Json
    } catch {
        Write-Bad "返回内容不是 JSON：$body"
        exit 1
    }
    if (-not $json.ok) {
        Write-Bad ("查询失败：" + $json.error)
        exit 1
    }
    Write-Ok "取到位置"
    Write-Host ("    经纬度 " + $json.lat + ", " + $json.lng + "  精度 " + [math]::Round($json.acc) + " 米  电量 " + $json.battery + "%") -ForegroundColor Gray
    Write-Host ("    地图 " + $json.link) -ForegroundColor Gray
    Write-Host ""
    Write-Host "全链路通了：家人端填 $Domain / 端口 $Port / 密钥 ******" -ForegroundColor Green
} catch {
    Write-Bad "请求失败：$($_.Exception.Message)"
} finally {
    $client.Dispose()
}
