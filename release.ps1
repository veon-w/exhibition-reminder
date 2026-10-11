<#
    release.ps1 — 台灣展覽提醒:出更新版的一鍵流程

    做這些事:
      1. 把 app/build.gradle.kts 的 versionCode +1(versionName 可用參數指定)
      2. 建置 release APK,內建正確的資料網址
      3. 驗證簽章確實是 release 金鑰(不是不小心用 debug 金鑰簽的)
      4. 有連接手機就覆蓋安裝
      5. commit 並 push 到 GitHub

    用法:
      .\release.ps1                       # versionCode +1,自動安裝、自動 push
      .\release.ps1 -VersionName 1.1      # 同時指定版本名稱
      .\release.ps1 -SkipInstall          # 不裝到手機
      .\release.ps1 -SkipPush             # 不推 GitHub
      .\release.ps1 -NoBump               # 不動版本號(只想重新建置時用)

    注意:versionCode 一定要遞增,否則手機無法覆蓋安裝。

    實作備註:gradle/adb/git 把進度寫到 stderr 是正常的,所以這裡刻意不用
    $ErrorActionPreference='Stop',而是在每個關鍵步驟明確檢查 $LASTEXITCODE。
    在 PowerShell 5.1 用 Stop 會把那些正常輸出誤判成終止錯誤。
#>
[CmdletBinding()]
param(
    [string]$VersionName,
    [string]$JsonUrl = "https://raw.githubusercontent.com/veon-w/exhibition-reminder/main/data/exhibitions.json",
    [string]$Message,
    [switch]$SkipInstall,
    [switch]$SkipPush,
    [switch]$NoBump
)

$ProgressPreference = 'SilentlyContinue'

function Step($n, $text) { Write-Host "`n[$n] $text" -ForegroundColor Cyan }
function Ok($text)        { Write-Host "    OK  $text" -ForegroundColor Green }
function Warn($text)      { Write-Host "    !!  $text" -ForegroundColor Yellow }
function Detail($text)    { Write-Host "        $text" -ForegroundColor DarkGray }
function Die($text)       { Write-Host "`n失敗: $text" -ForegroundColor Red; exit 1 }

$repoRoot   = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidDir = Join-Path $repoRoot 'android'
$gradleFile = Join-Path $androidDir 'app\build.gradle.kts'
$apkPath    = Join-Path $androidDir 'app\build\outputs\apk\release\app-release.apk'
$gradlew    = Join-Path $androidDir 'gradlew.bat'

if (-not (Test-Path $gradleFile)) { Die "找不到 $gradleFile,請確認腳本放在專案根目錄" }

# ---------- 1. 版本號 ----------
Step 1 '版本號'
$content   = Get-Content $gradleFile -Raw -Encoding UTF8
$codeMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
$nameMatch = [regex]::Match($content, 'versionName\s*=\s*"([^"]*)"')
if (-not $codeMatch.Success) { Die 'build.gradle.kts 裡找不到 versionCode' }

$oldCode = [int]$codeMatch.Groups[1].Value
$oldName = if ($nameMatch.Success) { $nameMatch.Groups[1].Value } else { '(未設定)' }

if ($NoBump) {
    $newCode = $oldCode
    Warn "-NoBump:版本號維持 $newCode(手機可能拒絕覆蓋安裝)"
} else {
    $newCode = $oldCode + 1
    $i   = $codeMatch.Groups[1].Index
    $len = $codeMatch.Groups[1].Length
    $content = $content.Remove($i, $len).Insert($i, "$newCode")
    Ok "versionCode $oldCode -> $newCode"
}

$newName = if ($VersionName) { $VersionName } else { $oldName }
if ($VersionName -and $nameMatch.Success) {
    $i   = $nameMatch.Groups[1].Index
    $len = $nameMatch.Groups[1].Length
    $content = $content.Remove($i, $len).Insert($i, $VersionName)
    Ok "versionName $oldName -> $VersionName"
} else {
    Ok "versionName $newName"
}

if (-not $NoBump -or $VersionName) {
    [System.IO.File]::WriteAllText($gradleFile, $content, (New-Object System.Text.UTF8Encoding $false))
}

# ---------- 2. 建置 ----------
Step 2 '建置 release APK'
$studioJbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path $studioJbr) {
    $env:JAVA_HOME = $studioJbr
    Ok "JAVA_HOME = Android Studio jbr"
} else {
    Warn "找不到 Android Studio 的 jbr,沿用目前的 JAVA_HOME = $env:JAVA_HOME"
}
Detail 'gradlew assembleRelease ...'

# Gradle 必須在 android 目錄執行(settings.gradle.kts 在那裡)
Push-Location $androidDir
try {
    $buildRaw  = (& $gradlew assembleRelease "-PexpoJsonUrl=$JsonUrl" --console=plain 2>&1 | Out-String)
    $buildCode = $LASTEXITCODE
} finally {
    Pop-Location
}
($buildRaw -split "`r?`n") | Where-Object { $_ -match '^e: |FAILED|BUILD SUCCESSFUL' } | ForEach-Object { Detail $_.Trim() }
if ($buildCode -ne 0) {
    Write-Host ''
    ($buildRaw -split "`r?`n") | Select-Object -Last 20 | ForEach-Object { Detail $_ }
    Die "建置失敗(exit $buildCode)"
}
if (-not (Test-Path $apkPath)) { Die "建置回報成功但找不到 APK:$apkPath" }
$apkMb = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
Ok "APK 產出 $apkMb MB"

# ---------- 3. 簽章 ----------
Step 3 '驗證簽章'
$sdkRoot = $env:ANDROID_SDK_ROOT
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$apksigner = Get-ChildItem (Join-Path $sdkRoot 'build-tools\*\apksigner.bat') -ErrorAction SilentlyContinue |
             Sort-Object FullName -Descending | Select-Object -First 1
if ($apksigner) {
    $sig    = (& $apksigner.FullName verify --print-certs $apkPath 2>&1 | Out-String)
    $dnLine = ($sig -split "`r?`n") | Where-Object { $_ -match 'Signer #1 certificate DN' } | Select-Object -First 1
    if (-not $dnLine) {
        Warn 'apksigner 沒有回報簽章資訊'
        ($sig -split "`r?`n") | Where-Object { $_.Trim() } | Select-Object -First 3 | ForEach-Object { Detail $_ }
    } elseif ($dnLine -match 'CN=Android Debug') {
        Die 'APK 是用 debug 金鑰簽的,不能拿去更新已安裝的 App。請檢查 android\keystore.properties 是否存在且內容正確'
    } else {
        Ok $dnLine.Trim()
    }
} else {
    Warn "找不到 apksigner,跳過簽章驗證($sdkRoot)"
}

# ---------- 4. 安裝 ----------
Step 4 '安裝到手機'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if ($SkipInstall) {
    Warn '-SkipInstall,跳過'
} elseif (-not (Test-Path $adb)) {
    Warn '找不到 adb,請手動把 APK 傳到手機安裝:'
    Detail $apkPath
} else {
    $device = ((& $adb devices 2>&1 | Out-String) -split "`r?`n") |
              Where-Object { $_ -match '^\S+\s+device$' } | Select-Object -First 1
    if (-not $device) {
        Warn '沒有連接的手機,請手動把 APK 傳到手機安裝:'
        Detail $apkPath
    } else {
        $serial = ($device -split '\s+')[0].Trim()
        Ok "裝置 $serial"
        $installRaw = (& $adb -s $serial install -r $apkPath 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            ($installRaw -split "`r?`n") | Where-Object { $_.Trim() } | ForEach-Object { Detail $_ }
            Warn '安裝失敗。若訊息是 User rejected permissions,請到手機的 開發人員選項 開啟「USB 安裝」'
        } else {
            Ok '已覆蓋安裝(追蹤清單保留)'
        }
    }
}

# ---------- 5. push ----------
Step 5 '推送到 GitHub'
if ($SkipPush) {
    Warn '-SkipPush,跳過'
} else {
    & git -C $repoRoot add -A 2>&1 | Out-Null
    & git -C $repoRoot diff --cached --quiet 2>&1 | Out-Null
    $hasChanges = ($LASTEXITCODE -ne 0)      # 1 = 有變更,0 = 沒有

    if (-not $hasChanges) {
        Warn '沒有需要 commit 的變更'
    } else {
        if (-not $Message) { $Message = "release: v$newName (versionCode $newCode)" }
        $commitRaw = (& git -C $repoRoot commit -q -m $Message 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            ($commitRaw -split "`r?`n") | Where-Object { $_.Trim() } | ForEach-Object { Detail $_ }
            Die 'commit 失敗。若被 pre-commit hook 擋下,請照它印出的指示修正 git 身分'
        }
        Ok "commit:$Message"

        $pushRaw = (& git -C $repoRoot push 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            Warn 'push 被拒(可能 Actions 剛好也更新了資料),改用 rebase 重試'
            & git -C $repoRoot pull --rebase --autostash 2>&1 | Out-Null
            $pushRaw = (& git -C $repoRoot push 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) {
                ($pushRaw -split "`r?`n") | Where-Object { $_.Trim() } | ForEach-Object { Detail $_ }
                Die 'push 仍然失敗,請手動處理'
            }
        }
        Ok '已推上 GitHub'
    }
}

Write-Host "`n完成: v$newName (versionCode $newCode)" -ForegroundColor Green
Detail "APK: $apkPath"
