# 老人守护 · 电脑端一键部署 / 自检 / 解锁
#
# 用法（项目根目录下执行）：
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Check      # 只看状态
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -All        # 安装 + 授权 + 设为桌面
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Owner      # 仅激活 Device Owner（要求全新机）
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -SetHome    # 仅设为默认桌面
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Unlock     # 应急：解除全部限制
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Wifi       # 开无线 ADB（5555，仅内网）
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -OtaBlock   # 屏蔽系统更新（按厂商挑包名）
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -OtaUnblock # 解除系统更新屏蔽
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Push x.apk # 推包并安装
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -ListApps   # 列第三方应用
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Uninstall "a.b,c.d"       # 批量卸载
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -InstallApks "a.apk,b.apk" # 批量安装
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -UninstallElder            # 解锁并卸载守护
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -InstallFix                # 只放开安装限制
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -SetUrl "http://x:8898/api/report?k=令牌"
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Intranet on|off   # 切内网 WIFI 模式
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Setup            # 重跑初始设置
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Report           # 立刻上报一次看结果
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Info             # 看上报地址/待补发/当前WIFI
#
# 说明：
# - Device Owner 只能在「未登录任何账号、无锁屏密码」的手机上激活，且一旦激活无法撤销（只能恢复出厂）。
# - 华为 / 荣耀 EMUI 上 addPersistentPreferredActivity 无效，默认桌面必须用 cmd package set-home-activity。
# - 所有命令都是幂等的，可以反复执行。

param(
    [switch]$Check,
    [switch]$Install,
    [switch]$Owner,
    [switch]$SetHome,
    [switch]$Permissions,
    [switch]$All,
    [switch]$Unlock,
    [switch]$Wifi,
    [switch]$OtaBlock,
    [switch]$OtaUnblock,
    [string]$Push = "",
    [string]$Apk = "",
    [string]$Serial = "",
    # 安装前先放开"禁止安装未知来源"（Device Owner 自己下发的限制只能由它自己清）
    [switch]$InstallFix,
    # 解锁并卸载守护本身（危险：Device Owner 会一起移除）
    [switch]$UninstallElder,
    # 列出第三方应用（包名 + versionCode），给电脑端界面用
    [switch]$ListApps,
    # 批量卸载：包名逗号分隔
    [string]$Uninstall = "",
    # 批量安装：APK 路径逗号分隔
    [string]$InstallApks = "",
    # 直接写上报地址（IPv6 字面量太长，让人手打不现实）
    [string]$SetUrl = "",
    # 远程切「内网 WIFI 模式」：on / off
    [string]$Intranet = "",
    # 重跑一次初始设置（警告页 → 选形态 → 向导）
    [switch]$Setup,
    # 立刻上报一次并打印结果
    [switch]$Report,
    # 打印设备侧状态：上报地址 / 开关 / 间隔 / 待补发 / 内网模式 / 当前 WIFI
    [switch]$Info
)

# adb 的输出是 UTF-8，PowerShell 默认按本地代码页（GBK）解，中文会变成乱码。
# 这一行必须在任何 adb 调用之前生效。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$ErrorActionPreference = "Continue"
$Pkg = "com.elder.launcher"
$Admin = "com.elder.launcher/com.elder.launcher.ElderDeviceAdminReceiver"
$HomeAct = "com.elder.launcher/com.elder.launcher.MainLauncherActivity"
$A11y = "com.elder.launcher/com.elder.launcher.ElderAccessibilityService"
$Notif = "com.elder.launcher/com.elder.launcher.ElderNotificationListener"

function Write-Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "  [OK] $text" -ForegroundColor Green }
function Write-Note($text) { Write-Host "  [!] $text" -ForegroundColor Yellow }
function Write-Bad($text) { Write-Host "  [X] $text" -ForegroundColor Red }

# ---------- adb 准备 ----------
# 显式解析成绝对路径：双击运行 / -NoProfile 启动时 PATH 可能和交互式终端不同
$adb = $null
$cmdInfo = Get-Command adb -ErrorAction SilentlyContinue
if ($cmdInfo) { $adb = $cmdInfo.Source }
if (-not $adb -and (Test-Path "C:\Android\Sdk\platform-tools\adb.exe")) {
    $adb = "C:\Android\Sdk\platform-tools\adb.exe"
}
if (-not $adb) {
    Write-Bad "找不到 adb，请安装 Android Platform-Tools 并加入 PATH"
    exit 1
}

# 参数统一走数组，避免 "-c" "-a" 这类开关被 PowerShell 当成函数参数
function Adb {
    param([string[]]$Cmd)
    $all = @()
    if ($Serial -ne "") { $all += @("-s", $Serial) }
    $all += $Cmd
    $out = & $adb $all 2>&1
    return ($out | Out-String).Trim()
}

# 静默执行（用于那些必然成功的写操作，避免把 adb 的原始报错刷屏）
function AdbQuiet {
    param([string[]]$Cmd)
    $all = @()
    if ($Serial -ne "") { $all += @("-s", $Serial) }
    $all += $Cmd
    & $adb $all 2>$null | Out-Null
}

$devices = Adb @("devices")
if ($devices -notmatch "device") {
    Write-Bad "没有检测到已授权的设备。请插好数据线，手机上点「允许 USB 调试」。"
    Write-Host $devices
    exit 1
}

if ($Apk -eq "") {
    $root = Split-Path -Parent $PSScriptRoot
    $candidates = @(
        (Join-Path $root "elder-guardian\app\build\outputs\apk\release\app-release.apk"),
        (Join-Path $root "elder-guardian\app\build\outputs\apk\debug\app-debug.apk")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { $Apk = $c; break } }
}

$model = Adb @("shell", "getprop", "ro.product.model")
$sdk = Adb @("shell", "getprop", "ro.build.version.sdk")
Write-Host "设备：$model（Android SDK $sdk）" -ForegroundColor Gray

# ---------- 状态检查 ----------
function Show-Check {
    Write-Step "当前状态"

    $ver = Adb @("shell", "dumpsys", "package", $Pkg)
    if ($ver -match "versionName=([0-9.]+)") {
        Write-Ok "已安装 $Pkg（v$($Matches[1])）"
    } else {
        Write-Bad "未安装 $Pkg"
    }

    $dp = Adb @("shell", "dumpsys", "device_policy")
    if ($dp -match "Device Owner" -and $dp -match "com\.elder\.launcher") {
        Write-Ok "Device Owner 已激活"
    } else {
        Write-Bad "Device Owner 未激活（锁定任务 / 禁装禁卸不可用）"
    }

    $homeOut = Adb @("shell", "cmd", "package", "resolve-activity",
        "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
    if ($homeOut -match "com\.elder\.launcher") {
        Write-Ok "默认桌面 = 老人守护"
    } else {
        $line = ($homeOut -split "`n" | Select-String "packageName=" | Select-Object -First 1)
        Write-Bad "默认桌面不是老人守护（当前：$($line -replace '\s+', ' ')）"
    }

    $overlay = Adb @("shell", "cmd", "appops", "get", $Pkg, "SYSTEM_ALERT_WINDOW")
    if ($overlay -match "allow") { Write-Ok "悬浮窗已授权" } else { Write-Bad "悬浮窗未授权" }

    $a11y = Adb @("shell", "settings", "get", "secure", "enabled_accessibility_services")
    if ($a11y -match "elder") { Write-Ok "无障碍服务已开启" } else { Write-Bad "无障碍服务未开启" }

    $notif = Adb @("shell", "settings", "get", "secure", "enabled_notification_listeners")
    if ($notif -match "elder") { Write-Ok "通知使用权已开启" } else { Write-Bad "通知使用权未开启" }

    $usage = Adb @("shell", "cmd", "appops", "get", $Pkg, "GET_USAGE_STATS")
    if ($usage -match "allow") { Write-Ok "使用情况访问已授权" } else { Write-Bad "使用情况访问未授权" }

    $batt = Adb @("shell", "dumpsys", "deviceidle", "whitelist")
    if ($batt -match "elder") { Write-Ok "已在电池优化白名单" } else { Write-Note "不在电池优化白名单（锁屏久了定时任务可能失效）" }

    # 系统更新：被禁用的算"已屏蔽"，还装着没禁的算"没屏蔽"
    $disabledSet = Get-DisabledPackages
    $installedSet = Get-InstalledPackages
    $blocked = @()
    $present = @()
    foreach ($item in $OtaPackages) {
        if ($disabledSet.ContainsKey($item.Pkg)) { $blocked += $item.Brand }
        elseif ($installedSet.ContainsKey($item.Pkg)) { $present += $item.Brand }
    }
    if ($blocked.Count -gt 0) {
        Write-Ok "系统更新已屏蔽（$($blocked -join '、')）"
    } elseif ($present.Count -gt 0) {
        Write-Note "系统更新仍开着（$($present -join '、')）——建议点「屏蔽系统更新」，升级会重置锁定与授权"
    } else {
        Write-Note "没识别出更新包（冷门 ROM），需要手动确认是否还在自动升级"
    }
}

# ---------- 与手机应用通讯（Device Owner 自己才能清限制）----------
# Device Owner 下发 DISALLOW_INSTALL_UNKNOWN_SOURCES 之后，adb install 会被系统拒绝，
# 而且这条限制**只能由 Device Owner 自己清**。应用里留了 AdminCommandReceiver 做这个口子。
$AdminAction = "com.elder.launcher.ADMIN"

function Send-AdminCmd {
    param([string]$Cmd)
    # 支持 "cmd|arg" 写法：装包时用不上，但 seturl / intranet 需要带参数
    $real = $Cmd
    $extra = @()
    if ($Cmd -match "^([^|]+)\|(.*)$") {
        $real = $Matches[1]
        $extra = @("--es", "arg", $Matches[2])
    }
    $out = Adb @("shell", "am", "broadcast", "-a", $AdminAction,
        "--es", "cmd", $real, "--es", "k", "elder-adb") + $extra + @("--include-stopped-packages")
    # receiver 用 setResultData 回一句话，am broadcast 会打在 "data=" 后面
    if ($out -match 'data="?([^"\r\n]*)') { return $Matches[1].Trim() }
    return ""
}

# 带超时的安装：华为/荣耀纯净模式会弹"禁止安装/继续安装"，
# 没人点的话 adb install 会一直等 —— 这就是"一键部署卡在那"的第二种情况。
function Invoke-AdbInstall {
    param([string]$Path, [int]$TimeoutSec = 180)

    # 必须转绝对路径：ProcessStartInfo 起的新进程 cwd 不是脚本目录，
    # 传相对路径会得到 "failed to stat xxx: No such file or directory"
    $full = $null
    try { $full = (Resolve-Path $Path -ErrorAction Stop).Path } catch { return "NOFILE" }

    $argList = @()
    if ($Serial -ne "") { $argList += @("-s", $Serial) }
    $argList += @("install", "-r", $full)
    $argLine = ($argList | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' '

    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $adb
        $psi.Arguments = $argLine
        $psi.WorkingDirectory = (Get-Location).Path
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        # 先挂异步读取再 WaitForExit，否则输出长了会把管道堵死
        $so = $proc.StandardOutput.ReadToEndAsync()
        $se = $proc.StandardError.ReadToEndAsync()
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            try { $proc.Kill() } catch { }
            return "TIMEOUT"
        }
        return (($so.Result + "`n" + $se.Result) -as [string]).Trim()
    } catch {
        return "ERROR: " + $_.Exception.Message
    }
}

function Test-InstallBlocked {
    $st = Send-AdminCmd "status"
    return ($st -match "blockInstall=True")
}

# 装一个包：先看有没有被本应用限制，装不上再针对性处理
function Install-One {
    param([string]$Path, [switch]$Quiet)

    if (-not $Path -or -not (Test-Path $Path)) {
        if (-not $Quiet) { Write-Bad "文件不存在：$Path" }
        return $false
    }

    $wasBlocked = Test-InstallBlocked
    if ($wasBlocked) {
        if (-not $Quiet) { Write-Note "检测到手机开着「禁止安装未知来源」，先临时放开…" }
        Send-AdminCmd "allow_install" | Out-Null
        Start-Sleep -Milliseconds 800
    }

    $r = Invoke-AdbInstall $Path
    $ok = ($r -match "Success")

    if (-not $ok -and $r -match "INSTALL_FAILED_USER_RESTRICTED|not allowed|Unknown sources|USER_RESTRICTED") {
        # 兜底：可能是别的 admin 或状态没刷新，再放开一次重试
        if (-not $Quiet) { Write-Note "被系统拒绝（安装限制），再放开一次重试…" }
        Send-AdminCmd "allow_install" | Out-Null
        Start-Sleep -Milliseconds 1200
        $r = Invoke-AdbInstall $Path
        $ok = ($r -match "Success")
    }

    if (-not $ok -and $r -eq "TIMEOUT") {
        if (-not $Quiet) {
            Write-Bad "安装超时（3 分钟没返回）"
            Write-Note "多半是手机弹了「纯净模式 / 是否允许安装」，没人点它就一直等。"
            Write-Note "去手机上关掉纯净模式（设置 → 安全 → 纯净模式），或在弹窗里点「仍然安装」，然后重试。"
        }
    } elseif ($ok) {
        if (-not $Quiet) { Write-Ok "安装成功：$(Split-Path $Path -Leaf)" }
    } else {
        if (-not $Quiet) {
            Write-Bad "安装失败：$r"
            if ($r -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
                Write-Note "签名不一致：这台机器装的是别的证书签的包。先卸载旧版再装（会丢配置）。"
            } elseif ($r -match "INSTALL_FAILED_VERSION_DOWNGRADE") {
                Write-Note "版本比已装的旧：先卸载旧版，或用 adb install -r -d 强制降级。"
            } elseif ($r -match "INSTALL_FAILED_INSUFFICIENT_STORAGE") {
                Write-Note "手机存储满了，先清理一些空间。"
            } else {
                Write-Note "华为/荣耀纯净模式会拦：设置 → 安全 → 纯净模式 → 关闭，然后重试。"
            }
        }
    }

    # 恢复原来的严格状态（家人配置本来是"禁止安装"就别给留口子）
    if ($wasBlocked) { Send-AdminCmd "restore_install" | Out-Null }
    return $ok
}

# ---------- 安装 ----------
function Do-Install {
    Write-Step "安装 APK"
    if (-not (Test-Path $Apk)) { Write-Bad "找不到 APK：$Apk"; return }
    Write-Host "  来源：$Apk" -ForegroundColor Gray

    $st = Send-AdminCmd "status"
    if ($st) { Write-Note "手机当前状态：$st" }
    if ($st -match "blockInstall=True") {
        Write-Note "这台手机开着「禁止安装未知来源」，装之前会自动临时放开，装完再收回。"
    }

    if (-not (Install-One $Apk)) {
        Write-Note "装不上别急着重插线：先看上面那条原因。最常见是华为纯净模式弹窗没人点。"
    }
}

# ---------- Device Owner ----------
function Do-Owner {
    Write-Step "激活 Device Owner"
    $dp = Adb @("shell", "dumpsys", "device_policy")
    if ($dp -match "Device Owner" -and $dp -match "com\.elder\.launcher") {
        Write-Ok "已经是 Device Owner，跳过"
        return
    }
    $r = Adb @("shell", "dpm", "set-device-owner", $Admin)
    if ($r -match "Success") {
        Write-Ok "Device Owner 激活成功"
    } else {
        Write-Bad "激活失败：$r"
        Write-Note "常见原因：手机已登录华为/谷歌账号，或设置过锁屏密码。"
        Write-Note "需要恢复出厂后跳过账号登录直接激活；或使用出厂预置方案。"
    }
}

# ---------- 特殊权限 ----------
function Do-Permissions {
    Write-Step "授予权限"

    $perms = @(
        "android.permission.CALL_PHONE",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_CALL_LOG",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.WRITE_EXTERNAL_STORAGE"
    )
    # POST_NOTIFICATIONS 是 Android 13 才有的权限，低版本强行授予会报 Unknown permission
    if ([int]$sdk -ge 33) { $perms += "android.permission.POST_NOTIFICATIONS" }

    foreach ($p in $perms) {
        $r = Adb @("shell", "pm", "grant", $Pkg, $p)
        if ($r -match "Exception|Error") { Write-Note "$p 未授予" }
    }
    Write-Ok "运行时权限已批量授予（个别系统不支持属正常）"

    AdbQuiet @("shell", "cmd", "appops", "set", $Pkg, "SYSTEM_ALERT_WINDOW", "allow")
    AdbQuiet @("shell", "cmd", "appops", "set", $Pkg, "GET_USAGE_STATS", "allow")
    Write-Ok "悬浮窗 / 使用情况访问已放行"

    $a11y = Adb @("shell", "settings", "get", "secure", "enabled_accessibility_services")
    if ($a11y -match $A11y) {
        Write-Ok "无障碍服务已在列表中"
    } elseif ($a11y -eq "null" -or $a11y -eq "") {
        AdbQuiet @("shell", "settings", "put", "secure", "enabled_accessibility_services", $A11y)
        AdbQuiet @("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        Write-Ok "无障碍服务已开启"
    } else {
        AdbQuiet @("shell", "settings", "put", "secure", "enabled_accessibility_services", "$a11y`:$A11y")
        Write-Ok "无障碍服务已追加到现有列表"
    }

    $notif = Adb @("shell", "settings", "get", "secure", "enabled_notification_listeners")
    if ($notif -match $Notif) {
        Write-Ok "通知使用权已在列表中"
    } else {
        AdbQuiet @("shell", "cmd", "notification", "allow_listener", $Notif)
        Write-Ok "通知使用权已开启"
    }

    AdbQuiet @("shell", "dumpsys", "deviceidle", "whitelist", "+$Pkg")
    Write-Ok "已加入电池优化白名单"
}

# ---------- 默认桌面 ----------
function Do-Home {
    Write-Step "设置默认桌面"
    $r = Adb @("shell", "cmd", "package", "set-home-activity", $HomeAct)
    if ($r -match "Error|Exception") {
        Write-Bad "设置失败：$r"
        Write-Note "华为/荣耀（EMUI）会拒绝第三方桌面：ADB 命令和系统「桌面」设置页都不列本应用。"
        Write-Note "这种情况不用纠结默认桌面，直接开「锁定任务模式」接管：HOME/后台键会被系统锁死，效果相同。"
    } else {
        Write-Ok "默认桌面已切换为老人守护（按 HOME 键即回到它）"
    }
}

# ---------- 无线 ADB / 直接推包 ----------
function Do-Wifi {
    Write-Step "开启无线 ADB（5555）"
    Write-Note "风险提示：5555 端口等于把手机控制权交给同网段的任何人。"
    Write-Note "只在内网使用，绝对不要做端口映射暴露到公网。"
    Write-Note "Android 10 上重启后会失效，需要重新执行一次（固化需要 root 或改 ROM）。"

    $r = Adb @("tcpip", "5555")
    if ($r -match "restarting in|success") {
        Write-Ok "已切到无线 ADB"
    } else {
        Write-Bad "切换失败：$r"
        return
    }
    Start-Sleep -Seconds 2
    $ip = (Adb @("shell", "ip", "-f", "inet", "addr", "show", "wlan0")) -split "`n" |
        Select-String "inet " | Select-Object -First 1
    if ($ip) {
        $addr = ($ip -replace ".*inet ", "") -replace "/.*", ""
        Write-Ok "手机地址：$addr"
        Write-Host "  电脑上这样连：adb connect ${addr}:5555" -ForegroundColor Gray
    } else {
        Write-Note "没读到 WiFi 地址，请确认手机已连 WiFi"
    }
}

function Do-Push {
    if ([string]::IsNullOrWhiteSpace($Push)) {
        Write-Bad "请用 -Push <APK路径> 指定要推送的安装包"
        return
    }
    $file = Resolve-Path $Push -ErrorAction SilentlyContinue
    if (-not $file) {
        Write-Bad "找不到文件：$Push"
        return
    }
    Write-Step "推送到手机并安装：$file"
    $r = Adb @("install", "-r", "$file")
    if ($r -match "Success") {
        Write-Ok "安装成功"
    } else {
        Write-Bad "安装失败：$r"
        Write-Note "华为纯净模式会拦非市场来源，先在手机上关闭纯净模式"
    }
}

# ---------- 解除限制 / 卸载 ----------

# 把默认桌面换回系统自带的那个。
# 解锁后如果桌面还指着本应用（或被禁用的包），老人会看到黑屏/进不去桌面，
# 让人以为"解不开"。所以这一步不能省。
function Restore-SystemHome {
    # 已经指向别的桌面（不是守护）就不用动
    $cur = Adb @("shell", "cmd", "package", "resolve-activity", "--brief",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    $curComp = ""
    foreach ($l in ($cur -split "`n")) {
        if ($l -match "([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)") { $curComp = $Matches[1] }
    }
    if ($curComp -and $curComp -notmatch "^com\.elder\.launcher/") { return $curComp }

    # 注意：query-activities 的输出是 name=全类名 和 packageName=包名 分两行，
    # 不是 "包/类" 格式，所以这里要配对拼出来（实测 EMUI 10 就是这样）。
    $raw = Adb @("shell", "cmd", "package", "query-activities",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    $comps = @()
    $pendingName = ""
    $inActivity = $false
    foreach ($l in ($raw -split "`n")) {
        # 只认 ActivityInfo 段：同一个包里还会列出 ApplicationInfo，
        # 直接抓 name= 会把 xxxApplication 也当成桌面（实测就踩到过）。
        if ($l -match "ActivityInfo:") { $inActivity = $true; continue }
        if (-not $inActivity) { continue }
        if ($l -match "^\s+name=([A-Za-z0-9_.$]+)") {
            $pendingName = $Matches[1]
        } elseif ($pendingName -and $l -match "packageName=([A-Za-z0-9_.]+)") {
            $pkg = $Matches[1]
            if ($pkg -ne "android" -and $pkg -ne $Pkg) {
                if ($pendingName.StartsWith($pkg)) {
                    $comps += "$pkg/" + $pendingName.Substring($pkg.Length)
                } else {
                    $comps += "$pkg/$pendingName"
                }
            }
            $pendingName = ""
            $inActivity = $false
        }
    }
    # FallbackHome 是开机未解锁时的临时桌面，设成默认会出问题
    $comps = $comps | Where-Object { $_ -notmatch "FallbackHome" } | Select-Object -Unique
    if (-not $comps -or $comps.Count -eq 0) { return "" }

    $pref = $comps | Where-Object { $_ -match "auncher|launcher" } | Select-Object -First 1
    if (-not $pref) { $pref = $comps | Select-Object -First 1 }

    $r = Adb @("shell", "cmd", "package", "set-home-activity", $pref)
    if ($r -match "Error|Exception|Failure") { return "" }
    return $pref
}

# 从"冒号分隔的组件列表"里去掉本应用，写回；空了就删掉这个键
function Remove-SelfFromSetting {
    param([string]$Key, [string]$Self)
    $cur = Adb @("shell", "settings", "get", "secure", $Key)
    if (-not $cur -or $cur -eq "null") { return }
    if ($cur -notmatch [regex]::Escape($Self)) { return }
    $keep = ($cur -split ":") | Where-Object { $_ -and ($_ -notmatch [regex]::Escape($Self)) }
    if ($keep -and $keep.Count -gt 0) {
        AdbQuiet @("shell", "settings", "put", "secure", $Key, ($keep -join ":"))
    } else {
        AdbQuiet @("shell", "settings", "delete", "secure", $Key)
    }
}

function Do-Unlock {
    Write-Step "解除锁定（应急）"
    Write-Note "先让应用自己放开限制：Device Owner 下发的限制，外部 adb 清不掉。"

    $r1 = Send-AdminCmd "unlock_all"
    if ($r1) { Write-Ok "应用回报：$r1" }
    else { Write-Note "应用没回应（可能已被停用/卸载）。限制多半已不存在，继续往下走。" }

    Adb @("shell", "am", "force-stop", $Pkg) | Out-Null
    Write-Ok "已停止应用（屏幕固定随之解除）"

    $home = Restore-SystemHome
    if ($home) { Write-Ok "默认桌面已换回系统桌面：$home" }
    else { Write-Note "没能自动换回桌面：请手动 设置 → 应用 → 默认应用 → 桌面 里改" }

    # Device Owner 只能自己清自己；adb 的 remove-active-admin 在 EMUI 上经常失败
    $co = Send-AdminCmd "clear_owner"
    if ($co -eq "owner-cleared") {
        Write-Ok "Device Owner 已由应用自己清除（此后要恢复出厂才能重新激活）"
    } else {
        $r = Adb @("shell", "dpm", "remove-active-admin", "--user", "0", $Admin)
        if ($r -match "Success" -or $r -eq "") { Write-Ok "Device Owner 已移除（此后要恢复出厂才能重新激活）" }
        else {
            Write-Note "Device Owner 还在：$(($r -split "`n")[0])"
            Write-Note "个别 ROM 只认应用自己清；如仍失败，请手动 设置 → 安全 → 设备管理器 取消勾选。"
        }
    }

    # 只清"本应用自己那一条"，以前整个 delete 会把别人的无障碍/通知监听一起删掉
    Remove-SelfFromSetting "enabled_accessibility_services" $A11y
    Remove-SelfFromSetting "enabled_notification_listeners" $Notif
    AdbQuiet @("shell", "cmd", "appops", "set", $Pkg, "SYSTEM_ALERT_WINDOW", "default")
    Write-Ok "无障碍 / 通知使用权 / 悬浮窗：只撤掉了本应用那一条"

    # 老版本这里会 pm disable-user —— 结果是"解锁后应用直接消失"，看着就像解锁失败。
    $e = Adb @("shell", "pm", "enable", $Pkg)
    if ($e -match "new state: enabled") { Write-Ok "应用已处于启用状态" }
    AdbQuiet @("shell", "pm", "unhide", "--user", "0", $Pkg)

    Write-Host ""
    Write-Note "有些状态要重启手机才彻底生效：默认桌面、屏幕固定、被隐藏的包、状态栏。"
    Write-Note "需要的话点「重启手机」，或长按电源键重启一次，别只看界面上没变就以为没解开。"
}

# 卸载守护：放手 → 换桌面 → 移除 DO → 卸载
function Do-UninstallElder {
    Write-Step "解锁并卸载守护"

    $r1 = Send-AdminCmd "unlock_all"
    if ($r1) { Write-Ok "应用回报：$r1" }
    Adb @("shell", "am", "force-stop", $Pkg) | Out-Null

    $home = Restore-SystemHome
    if ($home) { Write-Ok "默认桌面已换回：$home" }

    $co = Send-AdminCmd "clear_owner"
    if ($co -eq "owner-cleared") {
        Write-Ok "Device Owner 已由应用自己清除"
    } else {
        $r = Adb @("shell", "dpm", "remove-active-admin", "--user", "0", $Admin)
        if ($r -match "Success" -or $r -eq "") { Write-Ok "Device Owner 已移除" }
        else { Write-Note "移除 Device Owner：$(($r -split "`n")[0])" }
    }

    Start-Sleep -Milliseconds 800
    AdbQuiet @("shell", "pm", "enable", $Pkg)

    $u = Adb @("uninstall", $Pkg)
    if ($u -match "Success") {
        Write-Ok "已卸载 $Pkg"
    } else {
        Write-Bad "卸载失败：$(($u -split "`n")[0])"
        if ($u -match "DEVICE_POLICY_MANAGER") {
            Write-Note "Device Owner 还没移除干净。先重启手机，再执行一次；"
            Write-Note "或者手机 设置 → 安全 → 设备管理器 里取消勾选「老人守护」，然后手动卸载。"
        } else {
            Write-Note "手动兜底：设置 → 应用 → 老人守护 → 卸载。"
        }
    }

    Write-Host ""
    Write-Note "建议重启手机，让默认桌面、屏幕固定、限制状态彻底恢复。"
}

# ---------- 批量装卸第三方应用 ----------

function Get-ThirdPartyApps {
    $raw = Adb @("shell", "pm", "list", "packages", "-3")
    $pkgs = @()
    foreach ($l in ($raw -split "`n")) {
        if ($l -match "^\s*package:(\S+)") { $pkgs += $Matches[1] }
    }
    $out = @()
    foreach ($p in ($pkgs | Sort-Object)) {
        $ver = ""
        $d = Adb @("shell", "dumpsys", "package", $p)
        if ($d -match "versionName=(\S+)") { $ver = $Matches[1] }
        $out += [pscustomobject]@{ Pkg = $p; Ver = $ver }
    }
    return $out
}

function Do-ListApps {
    Write-Step "第三方应用"
    $apps = Get-ThirdPartyApps
    if (-not $apps -or $apps.Count -eq 0) { Write-Note "没读到第三方应用"; return }
    foreach ($a in $apps) {
        # 给电脑端界面解析用：APP|包名|版本
        Write-Host ("APP|{0}|{1}" -f $a.Pkg, $a.Ver)
    }
    Write-Host ("  共 {0} 个（不含系统预装）" -f $apps.Count) -ForegroundColor Cyan
}

function Do-UninstallApps {
    Write-Step "批量卸载"
    $list = @()
    foreach ($x in ($Uninstall -split ",")) {
        $t = $x.Trim()
        if ($t) { $list += $t }
    }
    if ($list.Count -eq 0) { Write-Bad "没有指定包名"; return }

    # 有"禁止卸载"限制时先放开，否则 pm uninstall 一律失败
    $st = Send-AdminCmd "status"
    if ($st -match "blockUninstall=True") {
        Write-Note "检测到「禁止卸载应用」，先临时放开…"
        Send-AdminCmd "unlock_all" | Out-Null
        Start-Sleep -Milliseconds 600
    }

    $okN = 0
    foreach ($p in $list) {
        if ($p -eq $Pkg) {
            Write-Note "跳过守护本身：要用「解锁并卸载守护」"
            continue
        }
        $r = Adb @("shell", "pm", "uninstall", "--user", "0", $p)
        if ($r -match "Success") {
            Write-Ok "已卸载 $p"
            $okN++
        } else {
            Write-Bad "$p 卸载失败：$(($r -split "`n") | Select-Object -First 1)"
        }
    }
    Write-Host ("  结果：成功 {0} / 共 {1}" -f $okN, $list.Count) -ForegroundColor Cyan
}

function Do-InstallApks {
    Write-Step "批量安装"
    $files = @()
    foreach ($x in ($InstallApks -split ",")) {
        $t = $x.Trim().Trim('"')
        if ($t) { $files += $t }
    }
    if ($files.Count -eq 0) { Write-Bad "没有指定 APK"; return }

    $okN = 0
    foreach ($f in $files) {
        Write-Host "  → $(Split-Path $f -Leaf)" -ForegroundColor Gray
        if (Install-One $f -Quiet) {
            Write-Ok "已安装 $(Split-Path $f -Leaf)"
            $okN++
        } else {
            Write-Bad "失败：$(Split-Path $f -Leaf)"
        }
    }
    Write-Host ("  结果：成功 {0} / 共 {1}" -f $okN, $files.Count) -ForegroundColor Cyan
}

# ---------- 系统更新（OTA）----------
#
# 为什么要管这件事：
# 老人机最怕的不是耗电，是"半夜自己升级完"。升级后可能发生三件事——
# 锁定任务白名单被重置、无障碍/通知使用权被收回、桌面被换回系统桌面。
# 表现就是老人第二天早上开机，发现手机"变回原来的样子"，人直接被弹出来。
#
# 做法：按厂商列出"系统更新"的包名，只禁用手机里确实存在的那些，不猜、不批量。
# 注意：个别机型升级/恢复出厂后会自己把包重新装回来，所以自检里会显示当前状态。

$OtaPackages = @(
    @{ Brand = "华为 EMUI"; Pkg = "com.huawei.android.hwouc" },
    @{ Brand = "荣耀"; Pkg = "com.hihonor.ouc" },
    @{ Brand = "荣耀（旧版）"; Pkg = "com.honor.ouc" },
    @{ Brand = "小米 / Redmi"; Pkg = "com.android.updater" },
    @{ Brand = "小米（新版）"; Pkg = "com.xiaomi.updater" },
    @{ Brand = "OPPO / 一加"; Pkg = "com.oplus.ota" },
    @{ Brand = "OPPO（旧版）"; Pkg = "com.oppo.ota" },
    @{ Brand = "OPPO ColorOS"; Pkg = "com.coloros.ota" },
    @{ Brand = "vivo / iQOO"; Pkg = "com.vivo.upgrade" },
    @{ Brand = "vivo（旧版）"; Pkg = "com.bbk.updater" },
    @{ Brand = "三星"; Pkg = "com.wssyncmldm" },
    @{ Brand = "三星（旧版）"; Pkg = "com.sec.android.soagent" }
)

function Get-InstalledPackages {
    $raw = Adb @("shell", "pm", "list", "packages")
    $set = @{}
    foreach ($l in ($raw -split "`n")) {
        if ($l -match "^\s*package:(.+)$") { $set[$Matches[1].Trim()] = $true }
    }
    return $set
}

function Get-DisabledPackages {
    $raw = Adb @("shell", "pm", "list", "packages", "-d")
    $set = @{}
    foreach ($l in ($raw -split "`n")) {
        if ($l -match "^\s*package:(.+)$") { $set[$Matches[1].Trim()] = $true }
    }
    return $set
}

function Do-OtaBlock {
    Write-Step "屏蔽系统更新（OTA）"
    Write-Note "升级后锁定任务白名单、无障碍、通知使用权都可能被重置，老人会被弹出守护界面。"
    Write-Note "稳定版就别升了；以后要恢复，点「解除更新屏蔽」或跑 -OtaUnblock。"

    $installed = Get-InstalledPackages
    $hit = 0
    $found = 0

    foreach ($item in $OtaPackages) {
        if (-not $installed.ContainsKey($item.Pkg)) { continue }
        $found++
        $r = Adb @("shell", "pm", "disable-user", "--user", "0", $item.Pkg)
        if ($r -match "disabled") {
            Write-Ok "$($item.Brand)：已禁用 $($item.Pkg)"
            $hit++
        } elseif ($r -match "Error|Exception|not allowed|SecurityException|denied") {
            # 部分 ROM 的包名带受保护前缀，disable-user 会被拒；
            # 有 Device Owner 时用 hide 兜底（效果等同"用户空间里隐藏"）
            $h = Adb @("shell", "pm", "hide", "--user", "0", $item.Pkg)
            if ($h -match "hidden=true") {
                Write-Ok "$($item.Brand)：已隐藏 $($item.Pkg)"
                $hit++
            } else {
                Write-Note "$($item.Brand)：$($item.Pkg) 禁不掉（$(($r -split "`n")[0])）"
            }
        } else {
            Write-Note "$($item.Brand)：$($item.Pkg) 返回未知结果：$(($r -split "`n")[0])"
        }
    }

    if ($found -eq 0) {
        Write-Note "这台手机没有匹配到已知的更新包名（可能是更冷门的 ROM）。"
        Write-Note "可以在手机上执行：adb shell pm list packages | findstr /i update"
        Write-Note "把出现的那一行发过来，我把它加进清单。"
    }

    # 老 AOSP 系 / 部分国产 ROM 认这几个开关，能关掉"自动下载"
    AdbQuiet @("shell", "settings", "put", "global", "ota_disable_automatic_update", "1")
    AdbQuiet @("shell", "settings", "put", "secure", "ota_disable_automatic_update", "1")
    Write-Ok "已关闭自动下载开关（部分系统认这个）"

    $state = if ($hit -gt 0) { "已屏蔽 $hit 个更新包" } else { "没有可屏蔽的更新包" }
    Write-Host ""
    Write-Host "  结果：$state" -ForegroundColor Cyan
    Write-Note "个别机型重启后会自己装回来，隔段时间看一次自检里的「系统更新」那一行。"
}

function Do-OtaUnblock {
    Write-Step "解除系统更新屏蔽"
    $installed = Get-InstalledPackages
    $hit = 0

    foreach ($item in $OtaPackages) {
        if (-not $installed.ContainsKey($item.Pkg)) { continue }
        $r = Adb @("shell", "pm", "enable", $item.Pkg)
        if ($r -match "enabled") {
            Write-Ok "$($item.Brand)：已恢复 $($item.Pkg)"
            $hit++
        }
        AdbQuiet @("shell", "pm", "unhide", "--user", "0", $item.Pkg)
    }

    AdbQuiet @("shell", "settings", "put", "global", "ota_disable_automatic_update", "0")
    AdbQuiet @("shell", "settings", "put", "secure", "ota_disable_automatic_update", "0")
    Write-Ok "自动下载开关已恢复"

    if ($hit -eq 0) { Write-Note "没有需要恢复的更新包（本来就没屏蔽，或者包名不在这份清单里）" }
}

# ---------- 主流程 ----------
if ($All) {
    $Install = $true
    $Owner = $true
    $Permissions = $true
    $SetHome = $true
    $OtaBlock = $true     # 一键部署顺手把系统更新关掉：升级会重置锁定任务与授权
}

$didSomething = $false
if ($Unlock) { Do-Unlock; $didSomething = $true }
if ($UninstallElder) { Do-UninstallElder; $didSomething = $true }
if ($ListApps) { Do-ListApps; $didSomething = $true }
if ($Uninstall -ne "") { Do-UninstallApps; $didSomething = $true }
if ($InstallApks -ne "") { Do-InstallApks; $didSomething = $true }
if ($SetUrl -ne "") {
    Write-Step "写入上报地址"
    $r = Send-AdminCmd ("seturl|" + $SetUrl)
    if ($r -match "url-set") { Write-Ok $r } else { Write-Bad "写入失败：$r" }
    $didSomething = $true
}
if ($Intranet -ne "") {
    Write-Step "切换内网 WIFI 模式：$Intranet"
    $r = Send-AdminCmd ("intranet|" + $Intranet)
    if ($r -match "intranet=") { Write-Ok $r } else { Write-Bad "切换失败：$r" }
    $didSomething = $true
}
if ($Setup) {
    Write-Step "重跑初始设置"
    $r = Send-AdminCmd "setup"
    if ($r -match "setup-started") { Write-Ok "已打开初始设置，请到手机上完成" } else { Write-Bad "打开失败：$r" }
    $didSomething = $true
}
if ($Info) {
    Write-Step "设备状态"
    $r = Send-AdminCmd "info"
    if ($r) { Write-Ok $r } else { Write-Bad "没读到（应用被停用？）" }
    $didSomething = $true
}
if ($Report) {
    Write-Step "立刻上报一次"
    $r = Send-AdminCmd "report"
    if ($r -match "report:ok") { Write-Ok $r } else { Write-Bad $r }
    $didSomething = $true
}
if ($InstallFix) { Write-Step "预检：放开安装限制"; Send-AdminCmd "allow_install" | Out-Null; Write-Ok "已放开安装限制"; $didSomething = $true }
if ($Install) { Do-Install; $didSomething = $true }
if ($Owner) { Do-Owner; $didSomething = $true }
if ($Permissions) { Do-Permissions; $didSomething = $true }
if ($SetHome) { Do-Home; $didSomething = $true }
if ($OtaBlock) { Do-OtaBlock; $didSomething = $true }
if ($OtaUnblock) { Do-OtaUnblock; $didSomething = $true }
if ($Wifi) { Do-Wifi; $didSomething = $true }
if ($Push -ne "") { Do-Push; $didSomething = $true }

if ($Check -or -not $didSomething) { Show-Check }

Write-Host ""
Write-Host "完成。建议在手机上打开「老人守护 → 标题连点 8 次 → 守护自检」再确认一遍。" -ForegroundColor Cyan
