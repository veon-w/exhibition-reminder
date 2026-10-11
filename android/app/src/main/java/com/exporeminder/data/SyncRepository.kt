package com.exporeminder.data

import android.content.Context
import com.exporeminder.data.db.AppDatabase
import com.exporeminder.data.db.NotificationLogEntity
import com.exporeminder.data.remote.RemoteDoc
import com.exporeminder.reminder.Notifier
import com.exporeminder.reminder.ReminderEngine
import java.time.LocalDate
import java.time.ZoneId

import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 同步 + 提醒流程:
 * 1. 抓 exhibitions.json(失敗就用本地快取繼續,提醒不能因為斷網停擺)
 * 2. 快照 diff 找「開賣」事件(追蹤中:onSales N→Y、票連結從無到有、新出現指向 udn 的同檔連結)
 * 3. 寫入 DB
 * 4. 提醒引擎全量評估,對照帳本發通知
 */
class SyncRepository(private val context: Context) {

    companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    }

    private val db = AppDatabase.get(context)
    private val settings = SettingsStore(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    suspend fun syncAndNotify(): Result<String> {
        val cfg = settings.current()
        var fetchError = ""
        var saleEvents = emptyList<ReminderEngine.SaleEvent>()

        android.util.Log.i("ExpoSync", "sync start, url=${cfg.jsonUrl}")
        if (cfg.jsonUrl.isNotEmpty()) {
            try {
                val doc = fetch(cfg.jsonUrl)
                android.util.Log.i("ExpoSync", "fetched ${doc.exhibitions.size} items, generatedAt=${doc.generatedAt}")
                require(doc.schemaVersion == 1) { "不支援的資料版本 ${doc.schemaVersion},請更新 app" }
                saleEvents = detectSaleEvents(doc)
                persist(doc)
                settings.recordSync(
                    syncAt = ZonedDateTime.now(TAIPEI).toLocalDateTime().withNano(0).toString(),
                    generatedAt = doc.generatedAt,
                    sourceStatus = doc.sourceStatus.entries.joinToString(", ") { "${it.key}=${it.value}" },
                    error = "",
                )
            } catch (e: Exception) {
                android.util.Log.e("ExpoSync", "sync failed", e)
                fetchError = (e.message ?: "").ifEmpty { e.javaClass.simpleName }
                settings.recordSync(
                    syncAt = ZonedDateTime.now(TAIPEI).toLocalDateTime().withNano(0).toString(),
                    generatedAt = "", sourceStatus = "", error = fetchError,
                )
            }
        }

        evaluateAndFire(saleEvents, cfg.closingFirstDays, cfg.closingSecondDays, cfg.saleNoticeDays)
        return if (fetchError.isEmpty()) Result.success("ok") else Result.failure(Exception(fetchError))
    }

    private fun fetch(url: String): RemoteDoc {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return json.decodeFromString<RemoteDoc>(resp.body!!.string())
        }
    }

    /** 快照 diff:必須在寫入新資料「之前」做,舊狀態才還在。 */
    private suspend fun detectSaleEvents(doc: RemoteDoc): List<ReminderEngine.SaleEvent> {
        val followedIds = db.followDao().unmutedIds().toSet()
        if (followedIds.isEmpty()) return emptyList()

        val old = db.exhibitionDao().all().associateBy { it.id }
        val oldLinks = db.suggestedLinkDao().all()
            .map { it.idA to it.idB }.toSet()
        val newById = doc.exhibitions.associateBy { it.id }
        val events = mutableListOf<ReminderEngine.SaleEvent>()

        for (id in followedIds) {
            val prev = old[id] ?: continue
            val cur = newById[id] ?: continue
            // 只認 N→Y:UNKNOWN(詳情頁抓失敗)恢復成 Y 不算開賣,避免誤報
            val turnedOnSale = prev.onSales == "N" && cur.onSales == "Y"
            val gotTicketUrl = prev.ticketUrl.isEmpty() && cur.ticketUrl.isNotEmpty()
            if (turnedOnSale || gotTicketUrl) {
                events += ReminderEngine.SaleEvent(
                    exhibition = cur.toEntity(),
                    signature = "onsale",
                    ticketUrl = cur.ticketUrl,
                )
            }
        }
        // 追蹤中的展出現「新的」指向 udn 的同檔建議連結 → 視為開賣
        for (link in doc.suggestedLinks) {
            if ((link.idA to link.idB) in oldLinks) continue
            val (mine, other) = when {
                link.idA in followedIds && link.idB.startsWith("udn:") -> link.idA to link.idB
                link.idB in followedIds && link.idA.startsWith("udn:") -> link.idB to link.idA
                else -> continue
            }
            val myEx = newById[mine] ?: continue
            val udnEx = newById[other] ?: continue
            // udn 那檔若還沒開賣,訊息要講「何時開賣」而不是「售票中」
            val saleHint = udnEx.saleStartAt
                ?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
                ?.takeIf { it.toLocalDate() >= LocalDate.now(TAIPEI) }
            events += ReminderEngine.SaleEvent(
                exhibition = myEx.toEntity(),
                signature = "link:$other",
                ticketUrl = udnEx.ticketUrl,
                viaTitle = udnEx.title,
                saleTimeText = saleHint?.format(
                    java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")),
            )
        }
        return events
    }

    private suspend fun persist(doc: RemoteDoc) {
        db.exhibitionDao().upsertAll(doc.exhibitions.map { it.toEntity() })
        db.exhibitionDao().deleteAbsentExcept(doc.exhibitions.map { it.id })
        db.suggestedLinkDao().replaceAll(doc.suggestedLinks.map { it.toEntity() })
    }

    private suspend fun evaluateAndFire(
        saleEvents: List<ReminderEngine.SaleEvent>,
        closingFirst: Int,
        closingSecond: Int,
        saleNoticeDays: Int,
    ) {
        val engine = ReminderEngine(
            closingFirst.toLong(), closingSecond.toLong(), saleNoticeDays.toLong())
        val today = LocalDate.now(TAIPEI)
        val followed = db.exhibitionDao().followed()
        val logDao = db.notificationLogDao()
        val notifier = Notifier(context)

        for (c in engine.evaluate(today, followed, saleEvents)) {
            if (logDao.count(c.ruleKey, c.refKey) > 0) continue
            // 只有真的送出才記帳。權限被拒時 show() 回 false,不記帳 → 補授權後還會補發。
            if (notifier.show(c)) {
                logDao.insert(NotificationLogEntity(c.ruleKey, c.refKey, today.toString()))
            }
        }
    }
}
