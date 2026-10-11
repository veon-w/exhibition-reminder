# 出更新版流程

## 先搞懂:有兩種「更新」

| 情況 | 誰做 | 你要做什麼 |
|---|---|---|
| **展覽資料更新** | GitHub Actions 每天台北時間 08:00 自動跑 | **什麼都不用做** |
| **App 本身改版**(改功能、修 bug) | 你 | 照下面的流程 |

平常只有第一種。這份文件講的是第二種。

---

## 方法 A:一鍵腳本(建議)

專案根目錄有一個 `release.ps1`,它會自動做完所有事:

```powershell
cd "D:\Veon\Projects\~Side Projects\exhibition-reminder"
.\release.ps1
```

它依序做這五件事:

1. `versionCode` +1(自動,不會忘記)
2. 建置 release APK,內建正確的資料網址
3. **驗證簽章**——確認不是不小心用 debug 金鑰簽的,是的話直接中止
4. 有連接手機就覆蓋安裝(追蹤清單保留)
5. commit 並 push 到 GitHub(被拒時自動 `pull --rebase` 重試)

### 常用參數

| 參數 | 用途 |
|---|---|
| `-VersionName 1.1` | 同時指定版本名稱 |
| `-SkipInstall` | 不裝到手機(只想產生 APK) |
| `-SkipPush` | 不推 GitHub(只想本機測試) |
| `-NoBump` | 不動版本號(重新建置時用) |
| `-Message "修好XX"` | 自訂 commit 訊息 |

例如只想重新建置、不碰版本與 GitHub:

```powershell
.\release.ps1 -NoBump -SkipInstall -SkipPush
```

> 如果 PowerShell 說「無法載入檔案,因為在這個系統上已停用指令碼執行」,改用:
> `powershell -ExecutionPolicy Bypass -File .\release.ps1`

---

## 方法 B:手動步驟

### 1. 改版本號(唯一需要手動編輯的地方)

開 `android\app\build.gradle.kts`,找到:

```kotlin
versionCode = 1        // 每次改版 +1,不可與舊版相同
versionName = "1.0"    // 給人看的版本,隨你編
```

⚠️ **`versionCode` 沒加的話,手機不會讓你覆蓋安裝**(會顯示「應用程式未安裝」)。這是最常卡住的地方。

### 2. 建置

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Veon\Projects\~Side Projects\exhibition-reminder\android"
.\gradlew.bat assembleRelease -PexpoJsonUrl=https://raw.githubusercontent.com/veon-w/exhibition-reminder/main/data/exhibitions.json
```

看到 `BUILD SUCCESSFUL` 就成功。APK 在
`android\app\build\outputs\apk\release\app-release.apk`。

### 3. 驗證簽章(建議做,10 秒)

```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs `
  "D:\Veon\Projects\~Side Projects\exhibition-reminder\android\app\build\outputs\apk\release\app-release.apk" |
  Select-String "Signer #1 certificate DN"
```

要看到 **`CN=ExpoReminder`**。若顯示 `CN=Android Debug`,代表簽章跑掉了,**不要安裝**,先檢查 `android\keystore.properties`。

### 4. 安裝

**接線(最快)**:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r `
  "D:\Veon\Projects\~Side Projects\exhibition-reminder\android\app\build\outputs\apk\release\app-release.apk"
```

`-r` 是覆蓋安裝,**追蹤清單與資料都會保留**。

**不接線**:把 APK 複製到手機(雲端硬碟、Line 傳給自己、隨身碟皆可),在手機上點它安裝。

### 5. 推上 GitHub

```powershell
cd "D:\Veon\Projects\~Side Projects\exhibition-reminder"
git add -A
git commit -m "release: v1.1"
git push
```

若 push 被拒(`non-fast-forward`),通常是 Actions 剛好也在更新資料:

```powershell
git pull --rebase --autostash
git push
```

---

## 三個一定要知道的地雷

### ① 忘記遞增 `versionCode`

症狀:安裝時顯示「應用程式未安裝」或「版本衝突」。
解法:`versionCode` 加 1 再重新建置。(用 `release.ps1` 就不會發生)

### ② 換電腦後少兩個本機檔案

這兩個檔案**刻意不進版控**(它們是本機專屬設定),換機器一定要自己重建:

| 檔案 | 內容 | 缺少的後果 |
|---|---|---|
| `android\local.properties` | `sdk.dir=C:/Users/你的帳號/AppData/Local/Android/Sdk` | Gradle 找不到 Android SDK |
| `android\keystore.properties` | 簽章金鑰路徑與密碼 | 改用 debug 金鑰簽,**無法覆蓋安裝** |

內容範例(`keystore.properties`):

```properties
storeFile=keystore/release.jks
storePassword=你的密碼
keyAlias=exporeminder
keyPassword=你的密碼
```

### ③ 弄丟 `release.jks`(最嚴重)

`android\keystore\release.jks` 是簽章金鑰,**弄丟就再也無法更新已安裝的 App**——只能解除安裝重裝,追蹤清單會消失。

👉 **現在就去備份這兩個檔案**(雲端硬碟或隨身碟):

```
android\keystore\release.jks
android\keystore.properties
```

這兩者在 `.gitignore` 裡,**永遠不會**被推上 GitHub。這是正確的——它們等於 App 的身分證加密碼。

---

## 出事了怎麼查

| 症狀 | 先檢查 |
|---|---|
| 手機資料不更新 | [Actions 頁面](https://github.com/veon-w/exhibition-reminder/actions) 有沒有紅燈 |
| App 顯示「資料超過 2 天未更新」 | 同上,代表 Actions 沒成功跑 |
| Actions 紅燈在 `pytest` 步驟 | 程式問題,依錯誤訊息修測試 |
| Actions 紅燈在 `Run scraper` 步驟 | 來源網站可能改版,看 `scraper/expo_scraper/sources/` 對應檔案 |
| Actions 紅燈在 `Report source status` | 有來源標 `stale`,看該來源的網站是否改版 |
| `git push` 被拒 | `git pull --rebase --autostash` 再 push |
| 建置報 `No Java compiler found` | `JAVA_HOME` 指到 JRE 了,要指向 Android Studio 的 `jbr` |
| 手機拒絕安裝(`User rejected permissions`) | 手機的 開發人員選項 → 開啟「USB 安裝」 |
| commit 被 hook 擋下 | git 身分被改掉了,照 hook 印出的指示修正 |

---

## 想改資料來源的類別

不用改程式,到 repo 的 **Settings → Secrets and variables → Actions → Variables** 設定:

| 變數 | 預設 | 說明 |
|---|---|---|
| `MOC_CATEGORIES` | `6,17` | 文化部類別。加古典/管弦音樂會用 `6,17,1` |
| `UDN_CATEGORIES` | `231,205` | udn 分類。加親子展用 `231,205,129` |

改完下次排程就會生效,不必重新建置 App。
