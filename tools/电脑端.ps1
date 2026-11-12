#requires -Version 5.1
<#
    老人守护 · 电脑端
    ---------------------------------------------------------------
    一个窗口把装机这件事做完：
      · 一键部署：装老人端 + 装家人端 + 激活设备所有者 + 一键授权 + 自检
      · 单独装某一边、单独授权、应急解锁、抓日志、推文件
      · 内置的安装包可以直接导出到电脑（不用再去翻 dist 目录）
      · 查老人机位置 / 体检（走中继或直连都行）

    三种用法：
      1) 打包好的 守护电脑端.exe —— 双击就用，不需要安装
      2) 在项目里：双击 tools\电脑端.bat
      3) powershell -ExecutionPolicy Bypass -File tools\电脑端.ps1

    自检（不弹窗，只验证界面能建起来）：
      powershell -File tools\电脑端.ps1 -SelfTest [-SelfTestFile 结果路径]
#>
param(
    [switch]$SelfTest,
    [string]$SelfTestFile = "",
    [string]$Snapshot = "",
    [string]$SnapshotApps = "",
    [string]$Serial = ""
)

# adb / provision.ps1 的输出都是 UTF-8，PowerShell 默认按本地代码页（GBK）解，
# 中文会变成「涓婃姤澶辫触」这种乱码。这一行必须在任何外部命令之前生效。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------------- 找安装包 ----------------
# 打包成 exe 时，文件被解到临时目录，都平铺在同一层；
# 在项目里跑时，包在 dist\ 下。两种都认。
# 注意：别用 -Filter "elder-guardian-*.apk" —— 家人端也叫 elder-guardian-family-*，
# 会被一起匹配到，然后按名字排序把家人端当成老人端装进去。所以这里用正则精确匹配。
function Find-Pkg {
    param([string]$Regex, [string[]]$ExtraDirs)
    $dirs = @($ScriptDir, (Join-Path $ScriptDir "apk"))
    foreach ($d in $ExtraDirs) { if ($d) { $dirs += $d } }
    foreach ($d in $dirs) {
        if (-not (Test-Path $d)) { continue }
        $f = Get-ChildItem -Path $d -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match $Regex } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($f) { return $f.FullName }
    }
    return ""
}

$RootGuess = Split-Path -Parent $ScriptDir
$ElderApk = Find-Pkg '^elder-guardian-\d.*\.apk$' @((Join-Path $RootGuess "dist"))
$FamilyApk = Find-Pkg '^elder-guardian-family-.*\.apk$' @((Join-Path $RootGuess "dist"))
$RelayPkg = Find-Pkg '^elderrelay-.*\.fpk$' @((Join-Path $RootGuess "dist"))
$Readme = Find-Pkg '^(readme|使用说明).*\.txt$' @((Join-Path $RootGuess "docs"))
$VersionFile = Find-Pkg '^versions\.txt$' @()

$Provision = ""
foreach ($c in @((Join-Path $ScriptDir "provision.ps1"),
        (Join-Path $ScriptDir "tools\provision.ps1"))) {
    if (Test-Path $c) { $Provision = $c; break }
}

$LogDir = Join-Path $env:TEMP "老人守护-日志"
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$ExportDirDefault = Join-Path ([Environment]::GetFolderPath("Desktop")) "老人守护安装包"

# ---------------- adb 定位 ----------------
function Get-AdbPath {
    $cands = @(
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        "C:\Android\Sdk\platform-tools\adb.exe",
        (Join-Path $ScriptDir "adb.exe"),
        "C:\platform-tools\adb.exe",
        (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    )
    foreach ($c in $cands) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$script:Adb = Get-AdbPath
$script:Serial = $Serial

function Invoke-Adb {
    param([string[]]$Arguments, [int]$TimeoutMs = 15000)
    if (-not $script:Adb) { return "（找不到 adb，请安装 Android Platform-Tools 并加入 PATH）" }
    $full = @()
    if ($script:Serial) { $full += @("-s", $script:Serial) }
    $full += $Arguments
    try {
        $out = & $script:Adb @full 2>&1 | Out-String
        return $out.Trim()
    } catch {
        return "adb 执行失败：$($_.Exception.Message)"
    }
}

function Get-FirstDevice {
    if (-not $script:Adb) { return "" }
    $lines = & $script:Adb devices 2>&1
    foreach ($l in $lines) {
        if ($l -match "^\s*(\S+)\s+device\s*$") { return $matches[1] }
    }
    return ""
}

# =====================================================================
#  界面
# =====================================================================
$fontUI = New-Object System.Drawing.Font("Microsoft YaHei UI", 9.5)
$fontTitle = New-Object System.Drawing.Font("Microsoft YaHei UI", 15, [System.Drawing.FontStyle]::Bold)
$fontMono = New-Object System.Drawing.Font("Consolas", 9)
$fontCard = New-Object System.Drawing.Font("Microsoft YaHei UI", 10.5, [System.Drawing.FontStyle]::Bold)
$C_BG = [System.Drawing.Color]::FromArgb(245, 246, 248)
$C_INK = [System.Drawing.Color]::FromArgb(31, 41, 55)
$C_MUTED = [System.Drawing.Color]::FromArgb(120, 126, 134)
$C_BLUE = [System.Drawing.Color]::FromArgb(25, 118, 210)
$C_GREY = [System.Drawing.Color]::FromArgb(238, 242, 247)

$form = New-Object System.Windows.Forms.Form
$form.Text = "老人守护 · 电脑端"
$form.Size = New-Object System.Drawing.Size(1010, 880)
$form.MinimumSize = New-Object System.Drawing.Size(940, 800)
$form.StartPosition = "CenterScreen"
$form.BackColor = $C_BG
$form.Font = $fontUI

$lblTitle = New-Object System.Windows.Forms.Label
$lblTitle.Text = "老人守护 · 电脑端"
$lblTitle.Font = $fontTitle
$lblTitle.ForeColor = $C_BLUE
$lblTitle.Location = New-Object System.Drawing.Point(18, 12)
$lblTitle.AutoSize = $true
$form.Controls.Add($lblTitle)

$lblSub = New-Object System.Windows.Forms.Label
$lblSub.Text = "插上手机 → 点「一键部署」，其余不用管"
$lblSub.ForeColor = $C_MUTED
$lblSub.Location = New-Object System.Drawing.Point(20, 46)
$lblSub.AutoSize = $true
$form.Controls.Add($lblSub)

function New-Card {
    param($X, $Y, $W, $H, $Title)
    $p = New-Object System.Windows.Forms.Panel
    $p.BackColor = [System.Drawing.Color]::White
    $p.Location = New-Object System.Drawing.Point($X, $Y)
    $p.Size = New-Object System.Drawing.Size($W, $H)
    $p.Anchor = "Top,Left"
    if ($Title) {
        $l = New-Object System.Windows.Forms.Label
        $l.Text = $Title
        $l.Font = $fontCard
        $l.Location = New-Object System.Drawing.Point(14, 10)
        $l.AutoSize = $true
        $p.Controls.Add($l)
    }
    $form.Controls.Add($p)
    return $p
}

function New-Btn {
    param($Text, $X, $Y, $W = 130, $H = 34, $Primary = $false)
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $Text
    $b.Font = $fontUI
    $b.Location = New-Object System.Drawing.Point($X, $Y)
    $b.Size = New-Object System.Drawing.Size($W, $H)
    $b.FlatStyle = "Flat"
    $b.FlatAppearance.BorderSize = 0
    if ($Primary) {
        $b.BackColor = $C_BLUE
        $b.ForeColor = [System.Drawing.Color]::White
    } else {
        $b.BackColor = $C_GREY
        $b.ForeColor = $C_INK
    }
    return $b
}

# ---- 设备状态 ----
$cardDevice = New-Card 18 70 958 92 $null
$cardDevice.Anchor = "Top,Left,Right"
$lblDevice = New-Object System.Windows.Forms.Label
$lblDevice.Text = "正在读取设备…"
$lblDevice.Location = New-Object System.Drawing.Point(14, 10)
$lblDevice.Size = New-Object System.Drawing.Size(925, 74)
$lblDevice.Font = $fontUI
$lblDevice.ForeColor = $C_INK
$cardDevice.Controls.Add($lblDevice)

# ---- 部署 ----
$cardDeploy = New-Card 18 172 330 426 "部署"
$btnAllIn = New-Btn "一键部署（推荐）" 14 42 300 44 $true
$btnElder = New-Btn "只装老人端" 14 94 146 32
$btnFamily = New-Btn "只装家人端" 168 94 146 32
$btnOwner = New-Btn "激活设备所有者" 14 130 146 32
$btnPerm = New-Btn "一键授权" 168 130 146 32
$btnCheck = New-Btn "守护自检" 14 166 146 32
$btnLog = New-Btn "抓日志" 168 166 146 32
$btnPush = New-Btn "推文件到手机" 14 202 146 32
$btnReboot = New-Btn "重启手机" 168 202 146 32
$btnOtaBlock = New-Btn "屏蔽系统更新" 14 238 146 32
$btnOtaUnblock = New-Btn "解除更新屏蔽" 168 238 146 32
$btnUnlock = New-Btn "应急解锁" 14 276 146 32
$btnUninstall = New-Btn "解锁并卸载守护" 168 276 146 32
$btnApps = New-Btn "应用管理（批量装卸软件）" 14 316 300 34
foreach ($b in @($btnAllIn, $btnElder, $btnFamily, $btnOwner, $btnPerm,
        $btnCheck, $btnLog, $btnPush, $btnReboot, $btnOtaBlock, $btnOtaUnblock,
        $btnUnlock, $btnUninstall, $btnApps)) {
    $cardDeploy.Controls.Add($b)
}
$lblDeployNote = New-Object System.Windows.Forms.Label
$lblDeployNote.Text = "锁定：设置 → 常用 → 锁定任务模式`r`n装不上多是纯净模式弹窗没人点"
$lblDeployNote.Location = New-Object System.Drawing.Point(14, 360)
$lblDeployNote.Size = New-Object System.Drawing.Size(300, 52)
$lblDeployNote.ForeColor = $C_MUTED
$cardDeploy.Controls.Add($lblDeployNote)

# ---- 内置安装包 ----
$cardPkg = New-Card 356 172 300 426 "内置安装包"
$lblPkg = New-Object System.Windows.Forms.Label
$lblPkg.Location = New-Object System.Drawing.Point(14, 40)
$lblPkg.Size = New-Object System.Drawing.Size(272, 200)
$lblPkg.ForeColor = $C_INK
$cardPkg.Controls.Add($lblPkg)

$btnExport = New-Btn "导出到电脑" 14 250 130 34 $true
$btnReadme = New-Btn "看教程" 152 250 130 34
$cardPkg.Controls.Add($btnExport)
$cardPkg.Controls.Add($btnReadme)
$lblExportTip = New-Object System.Windows.Forms.Label
$lblExportTip.Text = "导出后可以拷到别的电脑、用微信发给家人"
$lblExportTip.Location = New-Object System.Drawing.Point(14, 292)
$lblExportTip.Size = New-Object System.Drawing.Size(272, 40)
$lblExportTip.ForeColor = $C_MUTED
$cardPkg.Controls.Add($lblExportTip)

# ---- 定位 ----
$cardLoc = New-Card 664 172 312 426 "查老人机"
$lblHost = New-Object System.Windows.Forms.Label
$lblHost.Text = "地址（中继域名，或老人机的 IPv6）"
$lblHost.Location = New-Object System.Drawing.Point(14, 38)
$lblHost.Size = New-Object System.Drawing.Size(280, 20)
$lblHost.ForeColor = $C_MUTED
$cardLoc.Controls.Add($lblHost)

$txtHost = New-Object System.Windows.Forms.TextBox
$txtHost.Location = New-Object System.Drawing.Point(14, 60)
$txtHost.Size = New-Object System.Drawing.Size(280, 26)
$cardLoc.Controls.Add($txtHost)

$lblPort = New-Object System.Windows.Forms.Label
$lblPort.Text = "端口"
$lblPort.Location = New-Object System.Drawing.Point(14, 92)
$lblPort.AutoSize = $true
$lblPort.ForeColor = $C_MUTED
$cardLoc.Controls.Add($lblPort)

$txtPort = New-Object System.Windows.Forms.TextBox
$txtPort.Location = New-Object System.Drawing.Point(58, 88)
$txtPort.Size = New-Object System.Drawing.Size(66, 26)
$cardLoc.Controls.Add($txtPort)

$lblKey = New-Object System.Windows.Forms.Label
$lblKey.Text = "令牌/密钥"
$lblKey.Location = New-Object System.Drawing.Point(134, 92)
$lblKey.AutoSize = $true
$lblKey.ForeColor = $C_MUTED
$cardLoc.Controls.Add($lblKey)

$txtKey = New-Object System.Windows.Forms.TextBox
$txtKey.Location = New-Object System.Drawing.Point(206, 88)
$txtKey.Size = New-Object System.Drawing.Size(88, 26)
$cardLoc.Controls.Add($txtKey)

$btnRelayTest = New-Btn "测中继" 14 126 88 32
$btnWhere = New-Btn "查位置" 108 126 92 32 $true
$btnStatus = New-Btn "体检" 206 126 88 32
$cardLoc.Controls.Add($btnRelayTest)
$cardLoc.Controls.Add($btnWhere)
$cardLoc.Controls.Add($btnStatus)

$lblResult = New-Object System.Windows.Forms.Label
$lblResult.Text = "还没查询。"
$lblResult.Location = New-Object System.Drawing.Point(14, 168)
$lblResult.Size = New-Object System.Drawing.Size(284, 150)
$lblResult.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 9.5)
$lblResult.ForeColor = $C_INK
$cardLoc.Controls.Add($lblResult)

# ---- 日志 ----
$txtLog = New-Object System.Windows.Forms.TextBox
$txtLog.Multiline = $true
$txtLog.ScrollBars = "Vertical"
$txtLog.ReadOnly = $true
$txtLog.Font = $fontMono
$txtLog.BackColor = [System.Drawing.Color]::FromArgb(30, 33, 37)
$txtLog.ForeColor = [System.Drawing.Color]::FromArgb(220, 224, 228)
$txtLog.Location = New-Object System.Drawing.Point(18, 612)
$txtLog.Size = New-Object System.Drawing.Size(958, 218)
$txtLog.Anchor = "Top,Bottom,Left,Right"
$form.Controls.Add($txtLog)

function Write-Log {
    param([string]$Text)
    $stamp = (Get-Date).ToString("HH:mm:ss")
    $txtLog.AppendText("[$stamp] $Text`r`n")
    [System.Windows.Forms.Application]::DoEvents()
}

# =====================================================================
#  内置包信息
# =====================================================================
function Get-PkgLine {
    param([string]$Path, [string]$Label)
    if (-not $Path -or -not (Test-Path $Path)) {
        return "$Label`r`n   没有找到"
    }
    $f = Get-Item $Path
    $mb = [math]::Round($f.Length / 1MB, 1)
    # 一行一个信息：文件名太长会自己折行，这里先断开更整齐
    return "$Label`r`n   $($f.Name)`r`n   $mb MB"
}

function Update-PkgPanel {
    $lines = @()
    $lines += Get-PkgLine $ElderApk "老人端"
    $lines += ""
    $lines += Get-PkgLine $FamilyApk "家人端"
    $lines += ""
    $lines += Get-PkgLine $RelayPkg "中继（飞牛）"
    if ($VersionFile -and (Test-Path $VersionFile)) {
        $lines += ""
        $lines += "版本：" + ((Get-Content $VersionFile -Encoding UTF8 | Where-Object { $_.Trim() }) -join "；")
    }
    $lblPkg.Text = ($lines -join "`r`n")
}

# =====================================================================
#  设备信息
# =====================================================================
function Update-DeviceInfo {
    if (-not $script:Adb) {
        $lblDevice.Text = "找不到 adb。`r`n装机类功能用不了，但「导出安装包」和「查位置」照样能用。`r`n要装机就把 Platform-Tools 解压到本程序旁边，或加进 PATH。"
        return
    }
    $dev = Get-FirstDevice
    if (-not $dev) {
        $script:Serial = ""
        $lblDevice.Text = "没有检测到手机。`r`n1) 用数据线连电脑；2) 手机上选「传输文件」；3) 打开「开发者选项 → USB 调试」。"
        return
    }
    $script:Serial = $dev
    $model = (Invoke-Adb @("shell", "getprop", "ro.product.model"))
    $android = (Invoke-Adb @("shell", "getprop", "ro.build.version.release"))
    $pkg = (Invoke-Adb @("shell", "dumpsys", "package", "com.elder.launcher"))
    $ver = ""
    if ($pkg -match "versionName=(\S+)") { $ver = $matches[1] }
    $famPkg = (Invoke-Adb @("shell", "dumpsys", "package", "com.elder.family"))
    $famVer = ""
    if ($famPkg -match "versionName=(\S+)") { $famVer = $matches[1] }
    $lock = (Invoke-Adb @("shell", "dumpsys", "activity"))
    $lockState = "未知"
    if ($lock -match "mLockTaskModeState=(\w+)") { $lockState = $matches[1] }
    $pol = (Invoke-Adb @("shell", "dumpsys", "device_policy"))
    $isOwner = "否"
    if ($pol -match "Device Owner:[\s\S]{0,200}?com\.elder\.launcher") { $isOwner = "是" }
    $batt = (Invoke-Adb @("shell", "dumpsys", "battery"))
    $battPct = ""
    if ($batt -match "level:\s*(\d+)") { $battPct = $matches[1] + "%" }

    $lockText = switch ($lockState) {
        "LOCKED" { "已锁定" }
        "LOCK_TASK_MODE_LOCKED" { "已锁定" }
        "NONE" { "未锁定" }
        default { $lockState }
    }

    $lblDevice.Text = @(
        "设备：$model    Android $android    序列号 $dev    电量 $battPct",
        "老人端：$(if ($ver) { $ver } else { "未安装" })    家人端：$(if ($famVer) { $famVer } else { "未安装" })",
        "Device Owner：$isOwner    锁定任务：$lockText    adb：$($script:Adb)"
    ) -join "`r`n"
}

# =====================================================================
#  异步任务
# =====================================================================
$script:Job = $null
$script:PendingTip = ""

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 500
$timer.Add_Tick({
        if (-not $script:Job) { return }
        if ($script:Job.State -eq "Running") { return }
        $out = Receive-Job -Job $script:Job -ErrorAction SilentlyContinue 2>&1 | Out-String
        Remove-Job -Job $script:Job -Force
        $script:Job = $null
        if ($out -and $out.Trim()) { Write-Log $out.Trim() }
        if ($script:PendingTip) { Write-Log $script:PendingTip; $script:PendingTip = "" }
        Update-DeviceInfo
        $form.Cursor = [System.Windows.Forms.Cursors]::Default
    })
$timer.Start()

function Start-Task {
    param([string]$Name, [scriptblock]$Body, [string]$DoneTip = "")
    if ($script:Job) { Write-Log "还有任务在跑，等它结束再说。"; return }
    Write-Log "▶ $Name"
    $form.Cursor = [System.Windows.Forms.Cursors]::WaitCursor
    $script:PendingTip = $DoneTip
    # InitializationScript 在 Job 自己的进程里先跑：不设这一句，
    # Job 会用 GBK 去解 provision.ps1 / adb 的 UTF-8 输出 → 日志整段乱码
    $script:Job = Start-Job -ScriptBlock $Body -ArgumentList `
        $script:Adb, $script:Serial, $ElderApk, $FamilyApk, $Provision `
        -InitializationScript {
            try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
            try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
        }
}

# =====================================================================
#  按钮行为
# =====================================================================
$btnRefresh = New-Btn "刷新" 898 12 78 30
$btnRefresh.Anchor = "Top,Right"
$form.Controls.Add($btnRefresh)
$btnRefresh.Add_Click({ Update-DeviceInfo; Write-Log "已刷新设备信息" })

$btnAllIn.Add_Click({
    if (-not $script:Adb) { Write-Log "没有 adb，装不了。先装 Platform-Tools。"; return }
    if (-not (Test-Path $ElderApk) -and -not (Test-Path $FamilyApk)) {
        Write-Log "没找到内置安装包，先点「导出到电脑」看看文件在不在。"
        return
    }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
function A { param([string[]]$x) if ($DevSerial) { & $AdbPath -s $DevSerial @x 2>&1 | Out-String } else { & $AdbPath @x 2>&1 | Out-String } }
"== 1/6 安装老人端（手机若开着「禁止安装」，会自动临时放开再收回）"
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Install -Apk $ElderApk -Serial $DevSerial 2>&1 | Out-String
"== 2/6 安装家人端"
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Install -Apk $FamilyApk -Serial $DevSerial 2>&1 | Out-String
if (Test-Path $ProvisionPath) {
  "== 3/6 激活设备所有者（不是全新机会失败，可以跳过）"
  & powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Owner -Serial $DevSerial -Apk $ElderApk 2>&1 | Out-String
  "== 4/6 一键授权"
  & powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Permissions -Serial $DevSerial 2>&1 | Out-String
  "== 5/6 屏蔽系统更新（防止升级打乱锁定与授权）"
  & powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -OtaBlock -Serial $DevSerial 2>&1 | Out-String
  "== 6/6 自检"
  & powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Check -Serial $DevSerial 2>&1 | Out-String
} else {
  "没找到 provision.ps1，跳过了授权、更新屏蔽和自检。"
}
'@)
    Start-Task "一键部署" $body "部署走完了。看上面的输出，红色 [X] 的那几行才是要处理的。"
})

$btnElder.Add_Click({
    if (-not (Test-Path $ElderApk)) { Write-Log "没找到老人端安装包。"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
function A { param([string[]]$x) if ($DevSerial) { & $AdbPath -s $DevSerial @x 2>&1 | Out-String } else { & $AdbPath @x 2>&1 | Out-String } }
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Install -Apk $ElderApk -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "安装老人端" $body "装完了。华为/荣耀如果弹「纯净模式」，选「仍然安装」。"
})

$btnFamily.Add_Click({
    if (-not (Test-Path $FamilyApk)) { Write-Log "没找到家人端安装包。"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
function A { param([string[]]$x) if ($DevSerial) { & $AdbPath -s $DevSerial @x 2>&1 | Out-String } else { & $AdbPath @x 2>&1 | Out-String } }
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Install -Apk $FamilyApk -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "安装家人端" $body "装完了。家人手机上也这样装，或者把安装包发给他自己点。"
})

$btnOwner.Add_Click({
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Owner -Serial $DevSerial -Apk $ElderApk 2>&1 | Out-String
'@)
    Start-Task "激活设备所有者" $body "设备所有者是锁定任务、静默授权的前提，只能在新机上做。"
})

$btnPerm.Add_Click({
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Permissions -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "一键授权" $body "含定位 / 短信 / 无障碍 / 通知使用权 / 电池白名单。"
})

$btnCheck.Add_Click({
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Check -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "守护自检" $body ""
})

$btnReboot.Add_Click({
    $r = [System.Windows.Forms.MessageBox]::Show(
        "重启手机？装完新版本、改过锁定配置后需要重启才生效。`r`n重启期间会断开约 30 秒。", "重启手机",
        [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($r -ne "Yes") { return }
    Invoke-Adb @("reboot") | Out-Null
    Write-Log "已发送重启命令，等 30 秒后点「刷新」。"
})

$btnUnlock.Add_Click({
    $r = [System.Windows.Forms.MessageBox]::Show(
        "会做这些事：`r`n· 解除锁定任务 / 屏幕固定`r`n· 恢复安装、卸载`r`n· 解除本应用防卸载`r`n· 默认桌面换回系统桌面`r`n· 移除 Device Owner（不可逆，之后只能恢复出厂才能重装守护）`r`n`r`n有些状态（默认桌面、屏幕固定）要重启手机才彻底生效。`r`n`r`n确认继续？", "应急解锁",
        [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Warning)
    if ($r -ne "Yes") { return }
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -Unlock -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "应急解锁" $body "解锁做完了。桌面上还停在守护界面就重启一次手机，别只看界面没变以为没解开。"
})

$btnUninstall.Add_Click({
    $r = [System.Windows.Forms.MessageBox]::Show(
        "把守护整个卸掉？顺序是：放开限制 → 换回系统桌面 → 移除 Device Owner → 卸载。`r`n`r`n会丢掉手机上的守护配置（锁定开关、桌面卡片、上报地址都在里面），`r`n而且 Device Owner 移除后必须恢复出厂才能重新激活。`r`n`r`n确认继续？", "解锁并卸载守护",
        [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Warning)
    if ($r -ne "Yes") { return }
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -UninstallElder -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "解锁并卸载守护" $body "卸载流程走完了。强烈建议现在重启手机，让默认桌面和限制彻底恢复。"
})

$btnApps.Add_Click({ Show-AppManager })

# =====================================================================
#  应用管理（批量卸载 / 批量安装第三方应用）
# =====================================================================

# 调 provision.ps1 但不弹黑框（GUI 里直接 & powershell 会闪控制台）
function Invoke-ProvisionQuiet {
    param([string]$ArgLine)
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = "powershell.exe"
        $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Provision`" $ArgLine"
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        # 子进程写 UTF-8，这里必须显式指定，否则默认按 GBK 解 → 乱码
        $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
        $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        # 先异步读再等退出，否则输出多会堵住管道
        $so = $p.StandardOutput.ReadToEndAsync()
        $se = $p.StandardError.ReadToEndAsync()
        $p.WaitForExit()
        return (($so.Result + "`n" + $se.Result) -as [string]).Trim()
    } catch {
        return "ERROR: " + $_.Exception.Message
    }
}

function Show-AppManager {
    param([switch]$Preview)
    if (-not $script:Adb) { Write-Log "没有 adb，用不了应用管理。"; return }
    if (-not $Provision -or -not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }

    $dlg = New-Object System.Windows.Forms.Form
    $dlg.Text = "应用管理 · 批量装卸第三方软件"
    $dlg.Size = New-Object System.Drawing.Size(780, 620)
    $dlg.MinimumSize = New-Object System.Drawing.Size(660, 520)
    $dlg.StartPosition = "CenterParent"
    $dlg.BackColor = $C_BG
    $dlg.Font = $fontUI

    $lblTip = New-Object System.Windows.Forms.Label
    $lblTip.Text = "正在读取…"
    $lblTip.Location = New-Object System.Drawing.Point(16, 14)
    $lblTip.Size = New-Object System.Drawing.Size(730, 24)
    $lblTip.ForeColor = $C_MUTED
    $dlg.Controls.Add($lblTip)

    $lv = New-Object System.Windows.Forms.ListView
    $lv.CheckBoxes = $true
    $lv.View = "Details"
    $lv.FullRowSelect = $true
    $lv.HideSelection = $false
    $lv.Location = New-Object System.Drawing.Point(14, 44)
    $lv.Size = New-Object System.Drawing.Size(736, 448)
    $lv.Anchor = "Top,Bottom,Left,Right"
    [void]$lv.Columns.Add("包名", 430)
    [void]$lv.Columns.Add("版本", 130)
    [void]$lv.Columns.Add("备注", 150)
    $dlg.Controls.Add($lv)

    $btnReload = New-Btn "刷新列表" 14 506 112 32
    $btnDel = New-Btn "卸载选中" 134 506 112 32
    $btnAddApk = New-Btn "安装 APK…" 254 506 112 32
    $btnAll = New-Btn "全选" 374 506 84 32
    $btnNone = New-Btn "全不选" 464 506 84 32
    $btnCloseDlg = New-Btn "关闭" 640 506 110 32 $true
    foreach ($b in @($btnReload, $btnDel, $btnAddApk, $btnAll, $btnNone, $btnCloseDlg)) {
        $b.Anchor = "Bottom,Left"
        $dlg.Controls.Add($b)
    }
    $btnCloseDlg.Anchor = "Bottom,Right"

    $lblFoot = New-Object System.Windows.Forms.Label
    $lblFoot.Text = "只列第三方应用（不含系统预装）。守护自己请在主界面用「解锁并卸载守护」。"
    $lblFoot.Location = New-Object System.Drawing.Point(16, 546)
    $lblFoot.Size = New-Object System.Drawing.Size(600, 22)
    $lblFoot.ForeColor = $C_MUTED
    $lblFoot.Anchor = "Bottom,Left"
    $dlg.Controls.Add($lblFoot)

    # 读列表：provision -ListApps 输出 "APP|包名|版本"
    $fill = {
        $lv.Items.Clear()
        $lblTip.Text = "正在读取第三方应用（大约几秒）…"
        [System.Windows.Forms.Application]::DoEvents()

        $argLine = "-ListApps"
        if ($script:Serial) { $argLine += " -Serial $($script:Serial)" }
        $out = Invoke-ProvisionQuiet $argLine

        $n = 0
        foreach ($line in ($out -split "`r?`n")) {
            if ($line -match "^APP\|([^|]+)\|(.*)$") {
                $pkg = $Matches[1].Trim()
                $ver = $Matches[2].Trim()
                $note = ""
                if ($pkg -eq "com.elder.launcher") { $note = "守护自己" }
                elseif ($pkg -eq "com.elder.family") { $note = "家人端" }
                $it = New-Object System.Windows.Forms.ListViewItem($pkg)
                [void]$it.SubItems.Add($ver)
                [void]$it.SubItems.Add($note)
                [void]$lv.Items.Add($it)
                $n++
            }
        }
        if ($n -eq 0) {
            $lblTip.Text = "没读到应用（手机没连上？看主窗口的设备信息）"
        } else {
            $lblTip.Text = "共 $n 个第三方应用。勾选后点「卸载选中」。"
        }
    }

    $btnReload.Add_Click({ & $fill })

    $btnAll.Add_Click({
        foreach ($it in $lv.Items) { $it.Checked = $true }
    })
    $btnNone.Add_Click({
        foreach ($it in $lv.Items) { $it.Checked = $false }
    })

    $btnDel.Add_Click({
        $sel = @()
        foreach ($it in $lv.CheckedItems) { $sel += $it.Text }
        if ($sel.Count -eq 0) {
            [System.Windows.Forms.MessageBox]::Show("先勾选要卸载的应用。", "批量卸载",
                [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information)
            return
        }
        $preview = ($sel | Select-Object -First 6) -join "`r`n"
        if ($sel.Count -gt 6) { $preview += "`r`n…等 $($sel.Count) 个" }
        $r = [System.Windows.Forms.MessageBox]::Show(
            "卸载这些应用？数据会一起删掉。`r`n`r`n$preview", "确认卸载",
            [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Warning)
        if ($r -ne "Yes") { return }

        $lblTip.Text = "正在卸载…"
        [System.Windows.Forms.Application]::DoEvents()
        $argLine = "-Uninstall `"$($sel -join ',')`""
        if ($script:Serial) { $argLine += " -Serial $($script:Serial)" }
        $out = Invoke-ProvisionQuiet $argLine
        Write-Log $out
        & $fill
    })

    $btnAddApk.Add_Click({
        $ofd = New-Object System.Windows.Forms.OpenFileDialog
        $ofd.Multiselect = $true
        $ofd.Title = "选一个或多个 APK"
        $ofd.Filter = "APK 安装包|*.apk|所有文件|*.*"
        if ($ofd.ShowDialog() -ne "OK") { return }

        $lblTip.Text = "正在安装 $($ofd.FileNames.Count) 个包…"
        [System.Windows.Forms.Application]::DoEvents()
        $argLine = "-InstallApks `"$($ofd.FileNames -join ',')`""
        if ($script:Serial) { $argLine += " -Serial $($script:Serial)" }
        $out = Invoke-ProvisionQuiet $argLine
        Write-Log $out
        & $fill
    })

    $btnCloseDlg.Add_Click({ $dlg.Close() })

    if (-not $Preview) { $dlg.Add_Shown({ & $fill }) }

    if ($Preview) {
        # 只给截图/自检用：不进模态循环，显式同步填一次再返回
        $dlg.Show()
        & $fill
        return $dlg
    }
    [void]$dlg.ShowDialog()
    $dlg.Dispose()
}


$btnLog.Add_Click({
    $file = Join-Path $LogDir ("logcat-" + (Get-Date).ToString("MMdd-HHmmss") + ".txt")
    $raw = Invoke-Adb @("logcat", "-d", "-t", "3000")
    $raw | Out-File -FilePath $file -Encoding utf8
    Write-Log "日志已保存：$file"
    try { Start-Process "notepad.exe" $file } catch { }
})

$btnPush.Add_Click({
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    if ($dlg.ShowDialog() -ne "OK") { return }
    $file = $dlg.FileName
    Invoke-Adb @("shell", "mkdir", "-p", "/sdcard/Download/老人守护") | Out-Null
    $out = Invoke-Adb @("push", $file, "/sdcard/Download/老人守护/")
    Write-Log $out
    Write-Log "已推到手机「下载 / 老人守护」目录。"
})

# ---- 系统更新（OTA）----
# 老人机最怕"半夜自己升级完"：锁定任务白名单、无障碍/通知授权都可能被重置，
# 第二天开机回到系统桌面，人直接被弹出来。所以要做成两步都能点。
$btnOtaBlock.Add_Click({
    if (-not $script:Adb) { Write-Log "没有 adb，做不了。"; return }
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $r = [System.Windows.Forms.MessageBox]::Show(
        "关掉这台手机的系统更新？`r`n`r`n为什么：升级后锁定任务、无障碍、通知使用权可能被重置，`r`n老人会被弹出守护界面。稳定版就别升了。`r`n`r`n随时可以点「解除更新屏蔽」恢复。", "屏蔽系统更新",
        [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($r -ne "Yes") { return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -OtaBlock -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "屏蔽系统更新" $body "屏蔽完记得隔段时间看一次自检，个别机型重启后会自己装回来。"
})

$btnOtaUnblock.Add_Click({
    if (-not $script:Adb) { Write-Log "没有 adb，做不了。"; return }
    if (-not (Test-Path $Provision)) { Write-Log "找不到 provision.ps1"; return }
    $body = [scriptblock]::Create(@'
param($AdbPath, $DevSerial, $ElderApk, $FamilyApk, $ProvisionPath)
& powershell -NoProfile -ExecutionPolicy Bypass -File $ProvisionPath -OtaUnblock -Serial $DevSerial 2>&1 | Out-String
'@)
    Start-Task "解除更新屏蔽" $body "已恢复。手机下次联网会重新检查更新。"
})

# ---- 导出 ----
$btnExport.Add_Click({
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "选一个文件夹放安装包"
    if (Test-Path $ExportDirDefault) { $dlg.SelectedPath = $ExportDirDefault }
    else { $dlg.SelectedPath = [Environment]::GetFolderPath("Desktop") }
    if ($dlg.ShowDialog() -ne "OK") { return }

    $target = Join-Path $dlg.SelectedPath "老人守护安装包"
    if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target -Force | Out-Null }

    $n = 0
    foreach ($p in @($ElderApk, $FamilyApk, $RelayPkg, $Readme, $VersionFile)) {
        if ($p -and (Test-Path $p)) {
            Copy-Item $p -Destination $target -Force
            $n++
        }
    }
    Write-Log "已导出 $n 个文件到 $target"
    try { Start-Process "explorer.exe" $target } catch { }
})

$btnReadme.Add_Click({
    if ($Readme -and (Test-Path $Readme)) {
        try { Start-Process "notepad.exe" $Readme } catch { }
        Write-Log "已打开教程：$Readme"
    } else {
        Write-Log "没找到教程文件（readme.txt）。"
        [System.Windows.Forms.MessageBox]::Show(
            "这个包里的教程文件没找到。`r`n`r`n简单说：插手机 → 点「一键部署」→ 手机上扫中继的二维码配好地址即可。",
            "教程", [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information)
    }
})

# ---- 定位 / 中继 ----
function Get-LocUrl {
    param([string]$Host2, [string]$Port, [string]$Key, [string]$Path = "loc", [string]$Scheme = "http")
    $h = $Host2.Trim()
    $h = $h -replace "^https?://", ""
    $h = $h.TrimEnd("/")
    if ($h.Contains(":") -and -not $h.StartsWith("[")) { $h = "[$h]" }
    $p = if ($Port) { ":" + $Port } else { "" }
    "$Scheme`://$h$p/$Path`?key=$([uri]::EscapeDataString($Key))"
}

$btnRelayTest.Add_Click({
    $base = $txtHost.Text.Trim()
    if (-not $base) { Write-Log "先填中继地址"; return }
    $scheme = "http"
    if ($base -match "^https://") { $scheme = "https" }
    $bare = ($base -replace "^https?://", "").TrimEnd("/")
    if ($bare.Contains(":") -and -not $bare.StartsWith("[")) { $bare = "[$bare]" }
    $p = if ($txtPort.Text.Trim()) { ":" + $txtPort.Text.Trim() } else { "" }
    $url = "$scheme`://$bare$p/api/health"
    Write-Log "正在测：$url"
    $lblResult.Text = "正在联系中继…"
    [System.Windows.Forms.Application]::DoEvents()
    try {
        $r = Invoke-RestMethod -Uri $url -TimeoutSec 10
        $lblResult.Text = "中继在跑。`r`n版本 $($r.version)`r`n`r`n接下来：用浏览器打开同一个地址，`r`n点进去看某位老人的二维码。"
        Write-Log "中继正常，版本 $($r.version)"
        $script:RelayBase = $base
    } catch {
        $lblResult.Text = "连不上中继。`r`n常见原因：`r`n· 地址/端口不对`r`n· 反代没配好`r`n· 手机电脑这条路没有 IPv6`r`n`r`n$($_.Exception.Message)"
        Write-Log "中继测试失败：$($_.Exception.Message)"
    }
})

$btnWhere.Add_Click({
    if (-not $txtKey.Text.Trim()) { Write-Log "查位置要填令牌（或老人机上的密钥）"; return }
    $url = Get-LocUrl $txtHost.Text $txtPort.Text $txtKey.Text "loc"
    $lblResult.Text = "正在联系老人机…"
    [System.Windows.Forms.Application]::DoEvents()
    try {
        $r = Invoke-RestMethod -Uri $url -TimeoutSec 12
        if (-not $r.ok) {
            $lblResult.Text = if ($r.error -eq "key") { "密钥不对。对照老人机「定位和传输」里显示的密钥。" } else { "查询失败：$($r.error)" }
            return
        }
        $t = (Get-Date).ToString("MM-dd HH:mm:ss")
        $lblResult.Text = @(
            "已定位  $t",
            "坐标：$($r.lat), $($r.lng)",
            "精度约 $([math]::Round($r.acc)) 米",
            "电量：$($r.battery)%"
        ) -join "`r`n"
        $script:LastLink = $r.link
        Write-Log "定位成功：$($r.lat), $($r.lng)"
    } catch {
        $lblResult.Text = "连不上老人机。`r`n检查：① 手机定位服务开着吗 ② 地址对不对`r`n③ 路由器放行端口了吗 ④ 有没有公网 IPv6`r`n`r`n$($_.Exception.Message)"
        Write-Log "定位失败：$($_.Exception.Message)"
    }
})

$btnStatus.Add_Click({
    $url = Get-LocUrl $txtHost.Text $txtPort.Text $txtKey.Text "status"
    $lblResult.Text = "正在体检…"
    [System.Windows.Forms.Application]::DoEvents()
    try {
        $s = Invoke-RestMethod -Uri $url -TimeoutSec 12
        if (-not $s.ok) { $lblResult.Text = "读取失败：$($s.error)"; return }
        $vol = if ($null -ne $s.ringVolume) { "$($s.ringVolume)/$($s.ringMax)" } else { "未知" }
        $net = if ($s.wifi) { "WiFi $($s.wifiName)" } elseif ($s.mobile) { "移动数据" } else { "离线" }
        $lines = @(
            "电量 $($s.battery)%  $(if ($s.charging) { '充电中' } else { '未充电' })",
            "网络：$net",
            "铃声音量：$vol $(if ($s.muted) { '（静音了！）' } else { '' })",
            "锁定任务：$(if ($s.lockTask) { '开' } else { '关' })    定位服务：$(if ($s.locateOn) { '开' } else { '关' })",
            "版本：$($s.version)"
        )
        $lblResult.Text = $lines -join "`r`n"
        Write-Log "体检完成：电量 $($s.battery)%，$net"
    } catch {
        $lblResult.Text = "体检失败：$($_.Exception.Message)"
        Write-Log "体检失败：$($_.Exception.Message)"
    }
})

# =====================================================================
#  启动
# =====================================================================
Update-PkgPanel
Update-DeviceInfo

Write-Log "准备就绪。先插上线，再点「一键部署」。"
if (-not $script:Adb) { Write-Log "提示：没找到 adb，装机功能不可用；导出安装包和查位置仍可用。" }
if (-not $ElderApk) { Write-Log "提示：没找到老人端 APK，导出会少文件。" }

if ($SelfTest) {
    $ok = ($form.Controls.Count -gt 0) -and ($cardDeploy.Controls.Count -ge 12) -and ($cardPkg.Controls.Count -ge 4)
    $msg = "SelfTest: " + $(if ($ok) { "OK" } else { "FAIL" }) + `
        " controls=" + $form.Controls.Count + `
        " deploy=" + $cardDeploy.Controls.Count + `
        " pkg=" + $cardPkg.Controls.Count + `
        " elder=" + $(if ($ElderApk) { Split-Path $ElderApk -Leaf } else { "-" }) + `
        " family=" + $(if ($FamilyApk) { Split-Path $FamilyApk -Leaf } else { "-" }) + `
        " relay=" + $(if ($RelayPkg) { Split-Path $RelayPkg -Leaf } else { "-" }) + `
        " prov=" + [bool]$Provision
    if ($SelfTestFile) {
        try { $msg | Out-File -FilePath $SelfTestFile -Encoding utf8 } catch { }
    }
    Write-Output $msg
    $form.Dispose()
    return
}

if ($SnapshotApps) {
    $form.Show()
    [System.Windows.Forms.Application]::DoEvents()
    $appDlg = Show-AppManager -Preview
    # 列表是后台调 provision 拉的，等它填完（最多 20 秒）
    for ($i = 0; $i -lt 200; $i++) {
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 100
        if ($appDlg -and $appDlg.Visible -and $appDlg.Controls.Count -gt 0) {
            $lvTmp = $appDlg.Controls | Where-Object { $_ -is [System.Windows.Forms.ListView] } | Select-Object -First 1
            if ($lvTmp -and $lvTmp.Items.Count -gt 0) { break }
        }
    }
    $bmp = New-Object System.Drawing.Bitmap($appDlg.Width, $appDlg.Height)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $appDlg.Width, $appDlg.Height)
    $appDlg.DrawToBitmap($bmp, $rect)
    $bmp.Save($SnapshotApps, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $appDlg.Close()
    $form.Close()
    Write-Output "SnapshotApps: $SnapshotApps"
    return
}

if ($Snapshot) {
    $form.Show()
    [System.Windows.Forms.Application]::DoEvents()
    Start-Sleep -Milliseconds 900
    [System.Windows.Forms.Application]::DoEvents()
    $bmp = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $form.Width, $form.Height)
    $form.DrawToBitmap($bmp, $rect)
    $bmp.Save($Snapshot, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $form.Close()
    Write-Output "Snapshot: $Snapshot"
    return
}

# 为什么要这一手：
# 打包成 exe 后，IExpress 用 SW_HIDE 的 STARTUPINFO 去启动 launcher.cmd，
# 子进程继承了「隐藏」这个状态，Windows 会把窗体第一次 ShowWindow 强制当隐藏，
# 结果进程活着、窗口却看不见。切一下 TopMost 会走 SetWindowPos(SWP_SHOWWINDOW)，
# 相当于强制再显示一次，纯托管代码，不用 P/Invoke。
$form.Add_Shown({
        try {
            $form.TopMost = $true
            $form.Activate()
            $form.TopMost = $false
        } catch { }
        # 留一行启动记录，出问题时能判断"到底有没有跑起来"
        try {
            $line = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss") +
                "  目录=" + $ScriptDir + "`r`n"
            Add-Content -Path (Join-Path $LogDir "start.log") -Value $line -Encoding UTF8
        } catch { }
    })

[void]$form.ShowDialog()