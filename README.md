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
  - 閉展前 14 天、3 天提醒(天數可調;順序填反會自動交換,不會靜默失效)
  - 開賣提醒:追蹤的展在 udn 出現同名檔期就通知;有開賣時間的檔期開賣前 N 天預告(可調)
  - 每週日摘要:本週新開展 + 兩週內閉展
  - 提醒只針對按 ★ 追蹤的展覽;單檔可靜音
  - 可靠性:每日 09:00 鬧鐘(非純背景任務)+ 首啟引導加入省電白名單,對抗三星/小米激進省電;
    Android 12/12L 若系統未允許「鬧鐘與提醒」,設定頁會提示並提供一鍵前往開啟
- **其他功能**:類別排序與顯示設定、地區複選篩選、免費入場篩選、**漫展/ACG 篩選**、離我近排序、
  一鍵加入行事曆、通知點擊直達展覽詳情、追蹤清單匯出/匯入(換手機)、亮/暗主題

「離我近」按下去會**主動取一次目前位置**(不是只看上次快取)。取不到時會說明原因
(定位關閉、沒有權限、暫時拿不到),不再像以前那樣按了完全沒反應。只用約略定位
(`ACCESS_COARSE_LOCATION`),排序夠用且較省電。只在使用者按下該篩選時才要求定位。

「漫展/ACG」是跨來源篩選:同人場來源(ff/cwt)整包算,其餘比對展名關鍵字
(動漫/漫畫/電玩/電競/cosplay/潮流玩具…)。可以和類別、地區疊加,例如「漫展/ACG + 臺北市」。
關鍵字在 `AppViewModel.kt` 的 `ACG_KEYWORDS`,規則與誤判案例見 `AcgFilterTest.kt`
(「玩具」單獨當關鍵字會掃進軍事玩具槍展和婦幼展,只收複合詞;描述欄不比對)。

清單頁的篩選晶片順序是:`全部` → `漫展/ACG` → `同人展` → `藝文` → `特展` → `商展` → `演唱會`
→ 地區/免費/離我近。前兩個是刻意排前面的(最常用),`Kind.ORDER` 的預設順序也以同人展為首;
使用者在設定頁仍可自行調整類別順序,存過就以他的為準。

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

建置時用 Gradle 屬性帶入即可,**不需要改任何程式碼**:

```powershell
cd android
.\gradlew.bat assembleRelease -PexpoJsonUrl=https://raw.githubusercontent.com/<帳號>/<repo>/main/data/exhibitions.json
```

```bash
cd android
./gradlew assembleRelease -PexpoJsonUrl=https://raw.githubusercontent.com/<帳號>/<repo>/main/data/exhibitions.json
```

沒帶 `-PexpoJsonUrl` 時預設是空字串。填了之後 app 一裝好開啟就自動同步、直接看到展覽,
不必手動貼網址 —— 要把 APK 傳給朋友時特別有差。留空則維持原行為:第一次開啟強制停在
設定頁要求貼網址。使用者若在設定頁存過自己的網址,以他存的為準。

這個值會被寫進 `BuildConfig.DEFAULT_JSON_URL`(`app/build.gradle.kts` 的 `expoJsonUrl`),
所以本機測試也可以指向自己的資料源,例如
`-PexpoJsonUrl=http://127.0.0.1:8765/exhibitions.json`。

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

> **要上架 Google Play 的話先看這段。** manifest 宣告了 `USE_EXACT_ALARM`,這是
> **受政策限制**的權限,只給「核心功能是鬧鐘/計時器,或行事曆/提醒」的 app,上架時要在
> Play Console 另外填用途宣告。展覽追蹤算不算「提醒類 app」由審查員認定,有被退的風險。
> 自己簽 APK 直接裝(本 README 的主要用法)完全不受影響。
> 若哪天要上架而被打回,替代做法是拿掉 `USE_EXACT_ALARM`、改用
> `SCHEDULE_EXACT_ALARM` + 引導使用者開啟,或接受不精確鬧鐘(設定頁會如實提示)。

Android 12/12L(API 31–32)的 `SCHEDULE_EXACT_ALARM` 預設是關的,沒開會靜默降級成不精確鬧鐘。
app 會在設定頁顯示提示與「開啟鬧鐘與提醒權限」按鈕(API 33+ 由 `USE_EXACT_ALARM` 自動授予,不會看到這個提示)。

### 6. App 首次設定

1. 開啟 app → 允許通知權限,並依提示把 app 加入省電白名單(否則每日提醒會被系統擋掉)
2. 步驟 4 有填 `DEFAULT_JSON_URL` 的話這步跳過,開啟就會自動同步;
   沒填就到設定頁貼上步驟 3 的資料網址 → 按「立即同步」(主題亮/暗色也在設定頁選)
3. 在清單勾選你有興趣的展覽(可用類別 chips、「地區」展開複選縣市、關鍵字搜尋過濾),完成

## 日常維運

- **不用做任何事**。Actions 每天自動跑,app 每天自動同步
- 爬蟲壞了(來源網站改版)會**亮紅燈**:workflow 最後一步會讀 `sourceStatus`,只要有來源不是 `ok`
  就讓該次執行失敗(部分失敗會先把檔案 commit 上去,才判紅燈,所以不會白跑一天)。
  修 `scraper/expo_scraper/sources/` 下對應檔案後 push 即可,app 不用重裝
- `sourceStatus` 會標 `stale`,但**app 不會為單一來源失敗跳警示** —— app 的「資料超過 2 天未更新」
  看的是 `generatedAt`,來源掛掉時它照樣每天更新,所以那個警示不會亮。來源狀態只在設定頁以原始
  字串顯示,單源失敗要靠 Actions 紅燈或自己去設定頁看
- 想多收類別:到 repo 的 **Settings → Variables** 設 `MOC_CATEGORIES` / `UDN_CATEGORIES`
  (workflow 會把它們當參數傳,預設 `6,17` = 展覽+演唱會、`231,205` = 展覽+演唱會)。
  想加古典/管弦音樂會設 `6,17,1`,udn 親子展加 `129`
- 大型流行演唱會(拓元/KKTIX 售票的小巨蛋等級)不在資料源內,文化部收錄的以中小型為主
- 同人場(ff/cwt)兩個官網只掛「未來場次」,過去的場次會從頁面消失。reconcile 會把消失的
  標 `removedAt` 保留 60 天,不會直接刪掉,所以追蹤中的展不會無聲蒸發

### 爬蟲的退出碼

| 代碼 | 意義 | 檔案 | Actions |
|---|---|---|---|
| 0 | 五個來源都成功 | 寫出 | 綠燈 |
| 2 | 部分來源失敗(該源沿用前次、標 `stale`) | **照樣寫出** | 紅燈 |
| 1 | 全部來源失敗 | **不覆寫**(保留舊檔) | 紅燈 |

排程偶爾會撞到別的 commit,所以 commit 前有 `git pull --rebase --autostash` 再 push,失敗會重試一次。

### 前次資料檔讀壞了會怎樣

改 schema(欄位改名、新增必填欄位)時,舊的 `exhibitions.json` 會跟不上新程式碼。處理方式是:

- **舊檔某一筆多了不認識的欄位** → 忽略該欄位,其餘照用
- **某一筆少了必要欄位** → 丟掉那一筆並印警告,其他筆照常保留(不會因為一筆壞掉就整支中斷)
- **`removedAt` 不是合法日期** → 無法判斷保留期,選擇**保留**該筆(少刪勝過誤刪使用者的追蹤對象)
- **整個檔案壞掉(JSON 不完整)** → 備份成 `exhibitions.json.bad` 後從零開始,今天照樣有資料;
  備份留著讓你可以事後檢查

以前這些都會讓爬蟲在**開始抓之前**就中斷,整個檔案從此停止更新。

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
    "sourceId": "5b3dd5…",      // 該來源自己的 id(重抓/對帳用)
    "title": "…", "startDate": "2026-08-04", "endDate": "2028-12-31",
    "venueName": "…", "address": "…", "city": "臺中市", "lat": 24.15, "lng": 120.66,
    "organizer": "…", "category": "展覽|商展|同人展|演唱會",
    "priceText": "全票100元…", "onSales": "Y|N|UNKNOWN",
    "saleStartAt": null,        // 開賣時間(ISO,只有 udn 有);開賣前 N 天提醒靠這個
    "saleStartText": "",        // 開賣時間的原文(無法解析成時間時保留顯示用)
    "openTime": "09:00", "closeTime": "17:00",   // 開放時間,閉展當天提醒會顯示
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

> **要出更新版(改功能後重新打包 APK)看 [RELEASE.md](RELEASE.md)**,那裡有完整流程與地雷清單。
> 一鍵版本:`.\release.ps1`(自動遞增版號、建置、驗簽章、安裝、推送)。

scraper 跨平台,Windows / Linux / macOS 指令相同(Windows 若 `python` 指到 Store 版
可改用 `py -3`):

```bash
cd scraper
pip install -r requirements.txt
python -m pytest tests/          # fixture 為真實網站回應
python scrape.py --out ../data/exhibitions.json   # 本機實跑(會真的連五個網站)
```

測試會在 Actions 每次跑爬蟲**之前**執行,沒過就不跑爬蟲。

⚠️ **寫測試時不要寫死日期**。`reconcile` 會用 `today_taipei()` 讀系統日期來決定保留期
(閉展超過 30 天、消失超過 60 天的項目會被移出),所以固定日期的樣本會在日子過去之後
無聲地開始失敗。用 `tests/test_scraper.py` 裡的 `_day(offset)` / `_NOW_ISO` 相對日期helper。
另外 `removedAt` 會被當成「移除時間」再算 60 天保留,傳過去的 `now_iso` 會讓項目一進來
就被判過期而直接消失 —— 這兩個坑都各害過一次測試。

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
