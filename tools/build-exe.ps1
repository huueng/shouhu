# 把「电脑端 + 安装包 + 教程」打成一个免安装的单文件 EXE
#
# 原理：用 Windows 自带的 IExpress 做一个自解压包。
#   双击 → 解到临时目录 → 跑 launcher.cmd → 弹出电脑端的窗口
# 解压目录在程序退出后自动清掉，不往系统里写任何东西，也不用管理员权限。
#
# 用法（项目根目录下）：
#   powershell -ExecutionPolicy Bypass -File tools\build-exe.ps1
# 产物：
#   dist\老人守护电脑端.exe

param(
    [string]$ElderApk = "",
    [string]$FamilyApk = "",
    [string]$RelayPkg = "",
    [string]$OutName = "老人守护电脑端.exe"
)

$ErrorActionPreference = "Stop"

$ToolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $ToolsDir
$Dist = Join-Path $Root "dist"
$Stage = Join-Path $Root ".build\exe-stage"
$SedPath = Join-Path $Root ".build\iexpress.sed"
$AsciiOut = Join-Path $Root ".build\pc-package.exe"
$FinalOut = Join-Path $Dist $OutName

function Pick-Latest {
    param([string]$Pattern, [string]$Override)
    if ($Override) {
        if (-not (Test-Path $Override)) { throw "指定的文件不存在：$Override" }
        return (Resolve-Path $Override).Path
    }
    $f = Get-ChildItem -Path $Dist -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $Pattern } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $f) { throw "dist 里找不到匹配 $Pattern 的文件" }
    return $f.FullName
}

Write-Host "== 1/5 找最新的安装包" -ForegroundColor Cyan
$elder = Pick-Latest '^elder-guardian-\d.*\.apk$' $ElderApk
$family = Pick-Latest '^elder-guardian-family-.*\.apk$' $FamilyApk
$relay = Pick-Latest '^elderrelay-.*\.fpk$' $RelayPkg
Write-Host "   老人端 $(Split-Path $elder -Leaf)"
Write-Host "   家人端 $(Split-Path $family -Leaf)"
Write-Host "   中继   $(Split-Path $relay -Leaf)"

Write-Host "== 2/5 准备目录" -ForegroundColor Cyan
if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
New-Item -ItemType Directory -Path $Stage -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $SedPath -Parent) -Force | Out-Null

Copy-Item (Join-Path $ToolsDir "电脑端.ps1") (Join-Path $Stage "pc.ps1") -Force
Copy-Item (Join-Path $ToolsDir "provision.ps1") (Join-Path $Stage "provision.ps1") -Force
Copy-Item $elder (Join-Path $Stage (Split-Path $elder -Leaf)) -Force
Copy-Item $family (Join-Path $Stage (Split-Path $family -Leaf)) -Force
Copy-Item $relay (Join-Path $Stage (Split-Path $relay -Leaf)) -Force

# 教程：优先用 docs 里那份 txt，没有就给一份最短的
$readmeSrc = Join-Path $Root "docs\18-电脑端使用说明.txt"
if (-not (Test-Path $readmeSrc)) { $readmeSrc = Join-Path $Root "docs\readme.txt" }
if (Test-Path $readmeSrc) {
    Copy-Item $readmeSrc (Join-Path $Stage "readme.txt") -Force
} else {
    "老人守护 · 快速上手`r`n`r`n1. 插上手机，打开 USB 调试`r`n2. 双击本程序，点「一键部署」`r`n3. 手机上打开「守护」，按引导走" |
        Out-File (Join-Path $Stage "readme.txt") -Encoding UTF8
}

# 版本清单（窗口里会显示）
$verLines = @(
    "老人端 $(Split-Path $elder -Leaf)",
    "家人端 $(Split-Path $family -Leaf)",
    "中继 $(Split-Path $relay -Leaf)"
)
$verLines -join "；" | Out-File (Join-Path $Stage "versions.txt") -Encoding UTF8

# 启动器是一个小的 Go 程序（tools\launcher），不是 .cmd。
#
# 原因：.cmd / powershell 都会带出黑色控制台窗口，而用 -WindowStyle Hidden 去藏它，
# 会把"共用的那个控制台"一起藏掉（从别的终端启动时误伤终端），
# 并且 STARTUPINFO 的 SW_HIDE 会让 WinForms 窗体的第一次显示被系统忽略。
# 原生 GUI 程序 + CREATE_NO_WINDOW 才是最干净的解法：一个黑窗口都不会有。
$launcherSrc = Join-Path $ToolsDir "launcher"
$launcherExe = Join-Path $Root ".build\guard-launcher.exe"
$goBin = Join-Path $Root ".toolchain\go\bin\go.exe"
if (-not (Test-Path $goBin)) { $goBin = "go" }

Write-Host "   编译启动器…"
Push-Location $launcherSrc
try {
    $env:GOOS = "windows"
    $env:GOARCH = "amd64"
    $env:CGO_ENABLED = "0"
    & $goBin build -trimpath -ldflags "-s -w -H windowsgui" -o $launcherExe .
    if (-not (Test-Path $launcherExe)) { throw "启动器编译失败" }
} finally {
    Pop-Location
}
Copy-Item $launcherExe (Join-Path $Stage "guard-launcher.exe") -Force

Write-Host "== 3/5 生成 IExpress 配置" -ForegroundColor Cyan
$files = Get-ChildItem -Path $Stage -File | Sort-Object Name
$strings = New-Object System.Text.StringBuilder
$entries = New-Object System.Text.StringBuilder
$i = 0
foreach ($f in $files) {
    [void]$strings.AppendLine("FILE$i=`"$($f.Name)`"")
    [void]$entries.AppendLine("%FILE$i%=")
    $i++
}

# SED 只放 ASCII，避免中文在 ANSI 里被转坏；中文名最后再改
$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$AsciiOut
FriendlyName=GuardianPC
AppLaunched=guard-launcher.exe
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
SourceFiles=SourceFiles
[Strings]
$($strings.ToString())
[SourceFiles]
SourceFiles0=$Stage\
[SourceFiles0]
$($entries.ToString())
"@

# UTF-16 LE 带 BOM 是 IExpress 自己写出来的格式，最稳
[System.IO.File]::WriteAllText($SedPath, $sed, [System.Text.Encoding]::Unicode)

Write-Host "== 4/5 打包（IExpress）" -ForegroundColor Cyan
if (Test-Path $AsciiOut) { Remove-Item $AsciiOut -Force }
$iexpress = Join-Path $env:SystemRoot "System32\iexpress.exe"
if (-not (Test-Path $iexpress)) { throw "找不到 iexpress.exe（Windows 自带，可能是精简系统）" }

# 直接调用，不走 Start-Process：
# 某些环境里同时存在 HTTP_PROXY / http_proxy 两个大小写不同的变量，
# Start-Process 合并环境时会因为"字典键重复"报错。
& $iexpress /N /Q $SedPath | Out-Null
$code = $LASTEXITCODE
if (-not (Test-Path $AsciiOut)) {
    throw "打包失败：IExpress 退出码 $code，没有生成 $AsciiOut"
}

Write-Host "== 5/5 输出" -ForegroundColor Cyan
if (Test-Path $FinalOut) { Remove-Item $FinalOut -Force }
Move-Item $AsciiOut $FinalOut -Force

$mb = [math]::Round((Get-Item $FinalOut).Length / 1MB, 2)
Write-Host ""
Write-Host "完成：$FinalOut  ($mb MB)" -ForegroundColor Green

# 把包里有什么打出来，方便核对
Write-Host ""
Write-Host "-- 包内文件 --" -ForegroundColor Gray
$files | ForEach-Object { Write-Host ("   {0,-42} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
