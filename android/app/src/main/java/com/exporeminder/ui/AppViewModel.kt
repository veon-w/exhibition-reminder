package com.exporeminder.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exporeminder.data.SettingsStore
import com.exporeminder.data.SyncRepository
import com.exporeminder.data.db.AppDatabase
import com.exporeminder.data.db.ExhibitionEntity
import com.exporeminder.data.db.FollowEntity
import com.exporeminder.sync.SyncWorker
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Tab { ALL, FOLLOWED }

/** 同人場來源:開拓動漫(FF/PF/RF/駁二動漫祭)與 CWT 台灣同人誌販售會。 */
val DOUJIN_SOURCES = setOf("ff", "cwt")

/** 類別 chips:全部 / 藝文 / 特展 / 商展 / 同人展 / 演唱會 */
enum class Kind(val label: String) {
    ALL("全部"),
    ART("藝文"),        // 文化部(演唱會除外)
    EXPO("特展"),       // udn 售票特展
    TRADE("商展"),      // 世貿/南港
    DOUJIN("同人展"),   // 開拓動漫 / CWT(演唱會除外)
    CONCERT("演唱會");  // 任一來源 category=演唱會

    fun matches(ex: ExhibitionEntity): Boolean = when (this) {
        ALL -> true
        ART -> ex.source == "moc" && ex.category != "演唱會"
        EXPO -> ex.source == "udn" && ex.category != "演唱會"
        TRADE -> ex.source == "twtc"
        DOUJIN -> ex.source in DOUJIN_SOURCES && ex.category != "演唱會"
        CONCERT -> ex.category == "演唱會"
    }

    companion object {
        /** 預設顯示順序;新增類別要一起加進來,parsePrefs 會靠它補上舊設定沒有的類別。 */
        val ORDER: List<Kind> = listOf(ART, EXPO, TRADE, DOUJIN, CONCERT)

        val DEFAULT_PREFS: List<Pair<Kind, Boolean>> = ORDER.map { it to true }

        /** 解析 "ART:1,EXPO:0,…"(順序=顯示順序,1=顯示)。爛字串回預設。 */
        fun parsePrefs(s: String): List<Pair<Kind, Boolean>> {
            if (s.isBlank()) return DEFAULT_PREFS
            val parsed = s.split(",").mapNotNull { part ->
                val (name, flag) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                val kind = entries.firstOrNull { it.name == name && it != ALL } ?: return@mapNotNull null
                kind to (flag == "1")
            }
            val missing = ORDER.filter { k -> parsed.none { it.first == k } }
            val all = parsed + missing.map { it to true }
            return if (all.isEmpty()) DEFAULT_PREFS else all
        }

        fun encodePrefs(prefs: List<Pair<Kind, Boolean>>): String =
            prefs.joinToString(",") { "${it.first.name}:${if (it.second) 1 else 0}" }
    }
}

/** 縣市顯示順序(北到南),「未標地區」固定最後,篩選值用 "?" 表示。 */
private val CITY_ORDER = listOf(
    "臺北市", "新北市", "基隆市", "桃園市", "新竹市", "新竹縣", "苗栗縣",
    "臺中市", "彰化縣", "南投縣", "雲林縣", "嘉義市", "嘉義縣", "臺南市",
    "高雄市", "屏東縣", "宜蘭縣", "花蓮縣", "臺東縣", "澎湖縣", "金門縣", "連江縣",
)
const val CITY_UNKNOWN = "?"

/** 免費入場推斷:票價寫免費,或文化部展覽沒票價也沒售票連結(藝文展常態)。 */
fun isFreeEntry(ex: ExhibitionEntity): Boolean =
    ex.priceText.contains("免費") ||
        (ex.source == "moc" && ex.priceText.isEmpty() && ex.ticketUrl.isEmpty())

/**
 * 漫展/ACG 關鍵字,只比對展名。
 * 描述欄一起比對過:多撈到的全是誤判(常設展介紹順口提到「公仔」「特攝」),真陽性 0,故不納入。
 * 「玩具」「遊戲」「插畫」「動畫」「布袋戲」單獨當關鍵字會把婦幼展、繪本展、傳藝展全掃進來,只收複合詞。
 * 同人場(FF/CWT/PF)三個來源目前都沒收錄,關鍵字先備著,資料源補上就會自動生效。
 */
private val ACG_KEYWORDS = listOf(
    "動漫", "漫畫", "漫展", "同人", "ACG", "二次元", "御宅", "輕小說",
    "聲優", "VTuber", "虛擬偶像", "特攝", "動畫特展",
    "電玩", "電競", "電子遊戲",
    "cosplay", "角色扮演", "公仔", "潮流玩具", "玩具設計", "玩具創作", "電影玩具",
    "WirForce", "巴哈姆特",   // 展名裡沒有類別字的具名活動
)

/**
 * 漫展/ACG 篩選:跨來源的內容篩選,和類別 chips、地區等條件疊加使用。
 * 同人場來源整包算 ACG —— CWT 的展名只有場次代號(「CWT-74 …(台北場)」),關鍵字掃不到。
 */
fun isAcgEvent(ex: ExhibitionEntity): Boolean =
    ex.source in DOUJIN_SOURCES || ACG_KEYWORDS.any { ex.title.contains(it, ignoreCase = true) }

data class ExhibitionRow(
    val ex: ExhibitionEntity,
    val followed: Boolean,
    val muted: Boolean,
    val daysLeft: Long?,
    val notStarted: Boolean,
    val daysToStart: Long?,
    val udnHint: String?,
    val saleDisplay: String?,
    val distanceKm: Double?,   // 距離排序開啟且有座標才有
)

data class UiState(
    val rows: List<ExhibitionRow> = emptyList(),
    val tab: Tab = Tab.ALL,
    val query: String = "",
    val kind: Kind = Kind.ALL,
    val kindPrefs: List<Pair<Kind, Boolean>> = Kind.DEFAULT_PREFS,
    val selectedCities: Set<String> = emptySet(),
    val cityOptions: List<Pair<String, Int>> = emptyList(),
    val freeOnly: Boolean = false,
    val acgOnly: Boolean = false,
    val distanceMode: Boolean = false,
    val hasLocation: Boolean = false,
    val detailRow: ExhibitionRow? = null,   // 通知點進來要開的詳情
    val settings: SettingsStore.Settings? = null,
    val dataStale: Boolean = false,
)

private data class Filters(
    val tab: Tab, val query: String, val kind: Kind, val cities: Set<String>,
    val freeOnly: Boolean, val acgOnly: Boolean, val distanceMode: Boolean,
    val location: Pair<Double, Double>?, val detailId: String?,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val settingsStore = SettingsStore(app)

    private val tab = MutableStateFlow(Tab.ALL)
    private val query = MutableStateFlow("")
    private val kind = MutableStateFlow(Kind.ALL)
    private val cities = MutableStateFlow<Set<String>>(emptySet())
    private val freeOnly = MutableStateFlow(false)
    private val acgOnly = MutableStateFlow(false)
    private val distanceMode = MutableStateFlow(false)
    private val location = MutableStateFlow<Pair<Double, Double>?>(null)
    private val detailId = MutableStateFlow<String?>(null)

    init {
        // 有內建網址時,第一次開啟就先抓一次,不用等 24h 週期任務或使用者自己按「立即同步」
        viewModelScope.launch {
            val cfg = settingsStore.current()
            if (cfg.jsonUrl.isNotEmpty() && cfg.lastSyncAt.isEmpty()) syncNow()
        }
    }

    private val filters = combine(
        combine(tab, query, kind, cities, freeOnly) { t, q, k, c, f ->
            Filters(t, q, k, c, f, false, false, null, null)
        },
        distanceMode, location, detailId, acgOnly,
    ) { base, dm, loc, did, acg ->
        base.copy(distanceMode = dm, location = loc, detailId = did, acgOnly = acg)
    }

    val state = combine(
        db.exhibitionDao().observeAll(),
        db.followDao().observeAll(),
        db.suggestedLinkDao().observeAll(),
        filters,
        settingsStore.settings,
    ) { exhibitions, follows, links, f, cfg ->
        val today = LocalDate.now(SyncRepository.TAIPEI)
        val followById = follows.associateBy { it.exhibitionId }
        val byId = exhibitions.associateBy { it.id }

        fun futureSaleText(ex: ExhibitionEntity): String? = ex.saleStartAt
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?.takeIf { it.toLocalDate() >= today }
            ?.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

        val udnHint = mutableMapOf<String, String>()
        for (l in links) {
            val pair = listOf(l.idA, l.idB)
            val udn = pair.firstOrNull { it.startsWith("udn:") } ?: continue
            val other = pair.first { it != udn }
            byId[udn]?.let { udnEx ->
                udnHint[other] = futureSaleText(udnEx)
                    ?.let { "🎫 udn 同名檔期 $it 開賣" }
                    ?: "🎫 udn 有同名檔期售票中"
            }
        }

        fun toRow(ex: ExhibitionEntity): ExhibitionRow {
            val end = runCatching { LocalDate.parse(ex.endDate) }.getOrNull()
            val start = runCatching { LocalDate.parse(ex.startDate) }.getOrNull()
            val notStarted = start?.isAfter(today) == true
            val follow = followById[ex.id]
            val dist = if (f.location != null && ex.lat != null && ex.lng != null)
                haversineKm(f.location.first, f.location.second, ex.lat, ex.lng) else null
            return ExhibitionRow(
                ex = ex,
                followed = follow != null,
                muted = follow?.muted == true,
                daysLeft = end?.toEpochDay()?.minus(today.toEpochDay()),
                notStarted = notStarted,
                daysToStart = if (notStarted) start!!.toEpochDay() - today.toEpochDay() else null,
                udnHint = udnHint[ex.id],
                saleDisplay = futureSaleText(ex)?.let { "$it 開賣" },
                distanceKm = dist,
            )
        }

        val kindPrefs = Kind.parsePrefs(cfg.kindPrefs)
        val hiddenKinds = kindPrefs.filter { !it.second }.map { it.first }
        val candidates = exhibitions.asSequence()
            .filter { f.tab != Tab.FOLLOWED || it.id in followById }
            .filter { ex -> f.tab == Tab.FOLLOWED || hiddenKinds.none { it.matches(ex) } }
            .filter { f.kind.matches(it) }
            .filter { !f.freeOnly || isFreeEntry(it) }
            .filter { !f.acgOnly || isAcgEvent(it) }
            .filter {
                f.query.isEmpty() || it.title.contains(f.query, true) ||
                    it.venueName.contains(f.query, true) || it.city.contains(f.query, true)
            }
            .filter { ex ->
                runCatching { LocalDate.parse(ex.endDate) }.getOrNull()
                    ?.let { !it.isBefore(today) } ?: true
            }
            .toList()

        val counts = candidates.groupingBy { it.city.ifEmpty { CITY_UNKNOWN } }.eachCount()
        val cityOptions = (CITY_ORDER.filter { counts.containsKey(it) } +
            listOfNotNull(CITY_UNKNOWN.takeIf { counts.containsKey(it) }))
            .map { it to (counts[it] ?: 0) }

        val filtered = candidates.asSequence()
            .filter { f.cities.isEmpty() || it.city.ifEmpty { CITY_UNKNOWN } in f.cities }
            .map(::toRow)

        val rows = if (f.distanceMode && f.location != null) {
            // 距離模式:純按離我遠近,沒座標的沉到最後
            filtered.sortedWith(compareBy(nullsLast()) { it.distanceKm }).toList()
        } else {
            // 進行中在前、按結束日近的優先;未開展在後、按開展日早的優先
            filtered.sortedWith(compareBy(
                { it.notStarted },
                { if (it.notStarted) it.ex.startDate else it.ex.endDate },
            )).toList()
        }

        val stale = cfg.lastGeneratedAt.isNotEmpty() && runCatching {
            LocalDateTime.parse(
                cfg.lastGeneratedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME
            ).toLocalDate().isBefore(today.minusDays(2))
        }.getOrDefault(false)

        val detailRow = f.detailId?.let { byId[it] }?.let(::toRow)

        UiState(
            rows, f.tab, f.query, f.kind, kindPrefs, f.cities, cityOptions,
            f.freeOnly, f.acgOnly, f.distanceMode, f.location != null, detailRow, cfg, stale,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun setTab(t: Tab) { tab.value = t }
    fun setQuery(q: String) { query.value = q }
    fun setKind(k: Kind) { kind.value = k }
    fun setFreeOnly(v: Boolean) { freeOnly.value = v }
    fun setAcgOnly(v: Boolean) { acgOnly.value = v }
    fun setDistanceMode(v: Boolean) { distanceMode.value = v }
    fun setLocation(lat: Double, lng: Double) { location.value = lat to lng }

    fun openDetail(id: String) { detailId.value = id }
    fun closeDetail() { detailId.value = null }

    fun toggleCity(city: String) {
        cities.value = cities.value.let { if (city in it) it - city else it + city }
    }

    fun clearCities() { cities.value = emptySet() }

    fun toggleFollow(row: ExhibitionRow) = viewModelScope.launch {
        if (row.followed) db.followDao().remove(row.ex.id)
        else db.followDao().add(
            FollowEntity(row.ex.id, LocalDate.now(SyncRepository.TAIPEI).toString())
        )
    }

    fun toggleMute(row: ExhibitionRow) = viewModelScope.launch {
        db.followDao().setMuted(row.ex.id, !row.muted)
    }

    fun setTheme(theme: String) = viewModelScope.launch { settingsStore.setTheme(theme) }

    fun setKindPrefs(prefs: List<Pair<Kind, Boolean>>) = viewModelScope.launch {
        settingsStore.setKindPrefs(Kind.encodePrefs(prefs))
        val current = kind.value
        if (current != Kind.ALL && prefs.any { it.first == current && !it.second }) {
            kind.value = Kind.ALL
        }
    }

    fun saveSettings(url: String, first: Int, second: Int, saleDays: Int) = viewModelScope.launch {
        settingsStore.setJsonUrl(url)
        settingsStore.setClosingDays(first, second)
        settingsStore.setSaleNoticeDays(saleDays)
    }

    fun syncNow() = SyncWorker.syncNow(getApplication())

    /** 匯出追蹤清單成 JSON(換手機用)。回傳筆數,-1 = 失敗。 */
    fun exportFollows(uri: Uri, onDone: (Int) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val follows = db.followDao().all()
            val arr = JSONArray()
            for (fo in follows) {
                arr.put(JSONObject().apply {
                    put("id", fo.exhibitionId)
                    put("followedAt", fo.followedAt)
                    put("muted", fo.muted)
                })
            }
            val doc = JSONObject().put("version", 1).put("follows", arr)
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use {
                it.write(doc.toString(1).toByteArray())
            }
            follows.size
        }.getOrDefault(-1)
        onDone(result)
    }

    /** 匯入追蹤清單。回傳匯入筆數,-1 = 失敗。 */
    fun importFollows(uri: Uri, onDone: (Int) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val text = getApplication<Application>().contentResolver
                .openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            val arr = JSONObject(text).getJSONArray("follows")
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.followDao().add(FollowEntity(
                    exhibitionId = o.getString("id"),
                    followedAt = o.optString("followedAt",
                        LocalDate.now(SyncRepository.TAIPEI).toString()),
                    muted = o.optBoolean("muted", false),
                ))
                n++
            }
            n
        }.getOrDefault(-1)
        onDone(result)
    }
}

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}