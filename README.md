# 台灣展覽提醒(Exhibition Reminder)

追蹤你有興趣的台灣展覽,自動提醒你買票和把握展期。

- **資料源(五個,每日自動更新)**
  - 文化部 iCulture 開放資料:全台博物館/美術館/文化局展覽 + 演唱會(免金鑰官方 API)
  - udn 售票網:商業售票特展(IP 特展、沉浸展)+ 演唱會分類
  - 世貿/南港展覽館檔期表:B2B/消費商展(漫博、動漫節、電玩展都在這裡)
  - 開拓動漫(f-2.com.tw):FF 開拓動漫祭、PF、RF 模型創作祭、駁二動漫祭 + 動漫歌手來台公演
  - CWT(comicworld.com.tw):台灣同人誌販售會台北/台中/高雄場
- **提醒(Android app 本地通知,不需要任何伺服器帳號)**
  - 開展日提醒
  - 閉展前 14 天、3 天提醒(天數可調)
  - 開賣提醒:追蹤的展在 udn 出現同名檔期就通知;有開賣時間的檔期開賣前 N 天預告(可調)
  - 每週日摘要:本週新開展 + 兩週內閉展
  - 提醒只針對按 ★ 追蹤的展覽;單檔可靜音
  - 可靠性:每日 09:00 精確鬧鐘(非純背景任務)+ 首啟引導加入省電白名單,對抗三星/小米激進省電
- **其他功能**:類別排序與顯示設定、地區複選篩選、免費入場篩選、**漫展/ACG 篩選**、離我近排序、
  一鍵加入行事曆、通知點擊直達展覽詳情、追蹤清單匯出/匯入(換手機)、亮/暗主題

「漫展/ACG」是跨來源篩選:同人場來源(ff/cwt)整包算,其餘比對展名關鍵字
(動漫/漫畫/電玩/電競/cosplay/潮流玩具…)。可以和類別、地區疊加,例如「漫展/ACG + 臺北市」。
關鍵字在 `AppViewModel.kt` 的 `ACG_KEYWORDS`,規則與誤判案例見 `AcgFilterTest.kt`
(「玩具」單獨當關鍵字會掃進軍事玩具槍展和婦幼展,只收複合詞;描述欄不比對)。

同人場的展名很多不含 ACG 字眼(CWT 只有場次代號),所以判定改看來源,不靠關鍵字。
仍抓不到的:各百貨/咖啡廳的期間限定聯名、快閃店,以及拓元/KKTIX 售票的大型演唱會。

## 架構

```
GitHub Actions(每天 08:00)
  └─ scraper/scrape.py 抓五個來源
       └─ commit → data/exhibitions.json(單一靜態檔)
                        ↑
Android app 每日同步這個檔案 → 本地資料庫 → 勾選追蹤 → 本地通知
```

沒有伺服器、沒有資料庫服務、全部免費。爬蟲壞了只要改 scraper 重跑,app 不用更新。

## 部署步驟(第一次,約 15 分鐘)

### 1. 把這個專案放上你自己的 GitHub

```bash
# 在 GitHub 網頁上建立一個新 repo(公開私有皆可,公開的話 raw 網址不用 token)
git init
git add .
git commit -m "init"
git remote add origin https://github.com/<你的帳號>/<repo名>.git
git push -u origin main
```

### 2. 啟用自動排程

- Repo 頁面 → **Actions** 分頁 → 若出現「I understand my workflows…」按鈕就按下啟用
- 左側選 **scrape-exhibitions** → 右側 **Run workflow** 手動跑第一次
- 跑完後 `data/exhibitions.json` 會出現/更新,之後每天台北時間早上 8 點自動跑
- 注意:GitHub 對 60 天沒有 commit 的 repo 會自動停用排程,Actions 頁面會寄通知信,點一下重新啟用即可

### 3. 取得資料網址

公開 repo 的資料網址格式:

```
https://raw.githubusercontent.com/<你的帳號>/<repo名>/main/data/exhibitions.json
```

私有 repo 需要在網址後帶 token,不建議;直接用公開 repo 最省事(資料本來就是公開資訊)。

### 4. (建議)把資料網址內建進 app

把步驟 3 的網址填進 `android/app/src/main/java/com/exporeminder/data/SettingsStore.kt`
最上面的 `DEFAULT_JSON_URL`:

```kotlin
const val DEFAULT_JSON_URL = "https://raw.githubusercontent.com/<帳號>/<repo>/main/data/exhibitions.json"
```

填了之後 app 一裝好開啟就自動同步、直接看到展覽,不必手動貼網址 —— 要把 APK 傳給朋友時特別有差。
留空則維持原行為:第一次開啟強制停在設定頁要求貼網址。使用者若在設定頁存過自己的網址,以他存的為準。

### 5. 安裝 Android app

建置前先確認兩件事(Windows 上最常卡在這兩個):

1. **要有 JDK,不能只有 JRE**。Gradle 需要 `javac`,只裝 JRE 會報
   `No Java compiler found`。用 Android Studio 內建的 JDK 或裝 Temurin JDK 21,
   然後把 `JAVA_HOME` 指過去。
2. **`android/local.properties` 要指向這台機器的 SDK**。這個檔是本機設定、不進版控,
   換機器一定要改。路徑用正斜線最省事(反斜線在 .properties 裡是跳脫字元,
   寫 `C:\Users\…` 會被吃掉變成錯誤路徑):

   ```properties
   sdk.dir=C:/Users/你的帳號/AppData/Local/Android/Sdk
   ```

   Linux/macOS 則是 `sdk.dir=/home/你的帳號/Android/Sdk`。

接著建置:

```powershell
# Windows PowerShell / cmd
cd android
.\gradlew.bat assembleRelease
```

```bash
# Linux / macOS / Git Bash
cd android
./gradlew assembleRelease
```

APK 在 `android/app/build/outputs/apk/release/app-release.apk`,傳到手機安裝。

正式簽名:第一次先產生自己的金鑰(之後更新都要用同一把,請備份。
換金鑰簽的 APK 無法直接覆蓋安裝,得先解除安裝、追蹤清單也會跟著消失):

```powershell
# Windows PowerShell(換行符號是反引號 `,不是反斜線)
keytool -genkeypair -keystore keystore/release.jks -alias exporeminder `
  -keyalg RSA -keysize 2048 -validity 10000
```

```bash
# Linux / macOS / Git Bash
keytool -genkeypair -keystore keystore/release.jks -alias exporeminder \
  -keyalg RSA -keysize 2048 -validity 10000
```

然後建立 `android/keystore.properties`(不會進 git):

```properties
storeFile=keystore/release.jks
storePassword=你的密碼
keyAlias=exporeminder
keyPassword=你的密碼
```

沒有金鑰檔時 `assembleRelease` 會退回用 debug 金鑰簽,可先測試。
或用 Android Studio 開啟 `android/` 資料夾直接 Run(它會自己處理 JDK 和 SDK 路徑)。

### 6. App 首次設定

1. 開啟 app → 允許通知權限,並依提示把 app 加入省電白名單(否則每日提醒會被系統擋掉)
2. 步驟 4 有填 `DEFAULT_JSON_URL` 的話這步跳過,開啟就會自動同步;
   沒填就到設定頁貼上步驟 3 的資料網址 → 按「立即同步」(主題亮/暗色也在設定頁選)
3. 在清單勾選你有興趣的展覽(可用類別 chips、「地區」展開複選縣市、關鍵字搜尋過濾),完成

## 日常維運

- **不用做任何事**。Actions 每天自動跑,app 每天自動同步
- 爬蟲壞了(來源網站改版):Actions 頁面會顯示紅色失敗;`sourceStatus` 會標 `stale`,app 內顯示「資料未更新」警示。修 `scraper/expo_scraper/sources/` 下對應檔案後 push 即可,app 不用重裝
- 想多收類別:改 workflow 的執行參數。預設 `--moc-categories 6,17`(展覽+演唱會)、`--udn-categories 231,205`(展覽+演唱會);想加古典/管弦音樂會用 `--moc-categories 6,17,1`,udn 親子展加 `129`
- 大型流行演唱會(拓元/KKTIX 售票的小巨蛋等級)不在資料源內,文化部收錄的以中小型為主
- 同人場(ff/cwt)兩個官網只掛「未來場次」,過去的場次會從頁面消失。reconcile 會把消失的
  標 `removedAt` 保留 60 天,不會直接刪掉,所以追蹤中的展不會無聲蒸發

## 資料格式(data/exhibitions.json)

```jsonc
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-05T08:00:00+08:00",
  // stale = 該源本次失敗、沿用前次
  "sourceStatus": {"moc": "ok", "twtc": "ok", "udn": "ok", "ff": "ok", "cwt": "ok"},
  "exhibitions": [{
    "id": "moc:5b3dd5…",        // 穩定識別碼,app 訂閱綁這個
    "source": "moc|twtc|udn|ff|cwt",
    "title": "…", "startDate": "2026-08-04", "endDate": "2028-12-31",
    "venueName": "…", "address": "…", "city": "臺中市", "lat": 24.15, "lng": 120.66,
    "organizer": "…", "category": "展覽|商展|同人展|演唱會",
    "priceText": "全票100元…", "onSales": "Y|N|UNKNOWN",
    "ticketUrl": "…", "officialUrl": "…", "imageUrl": "…",
    "descriptionShort": "…(≤200字)",
    "lastSeenAt": "…", "removedAt": null   // 從來源消失會標時間並保留 60 天,不直接刪
  }],
  "suggestedLinks": [                       // 跨源「可能是同一檔展」,只建議不合併
    {"idA": "moc:…", "idB": "udn:…", "confidence": 0.95}
  ]
}
```

## 開發

scraper 跨平台,Windows / Linux / macOS 指令相同(Windows 若 `python` 指到 Store 版
可改用 `py -3`):

```bash
cd scraper
pip install -r requirements.txt
python -m pytest tests/          # fixture 為真實網站回應
python scrape.py --out ../data/exhibitions.json   # 本機實跑(會真的連五個網站)
```

Android 單元測試:

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest     # Windows
./gradlew :app:testDebugUnitTest         # Linux / macOS / Git Bash
```

新增資料源:在 `scraper/expo_scraper/sources/` 加一個模組,實作
`fetch(session) -> list[Exhibition]`,再到 `scrape.py` 的 `jobs` 掛上去
(單源失敗只會讓該源標 stale,不影響其他源)。app 端若要有專屬類別 chip,
在 `AppViewModel.kt` 的 `Kind` 加一個並補進 `Kind.ORDER`
(`parsePrefs` 會自動把舊設定沒有的類別補上並預設開啟),
再到 `ExhibitionListScreen.kt` 的 `SOURCE_LABEL` 加對應標籤。
