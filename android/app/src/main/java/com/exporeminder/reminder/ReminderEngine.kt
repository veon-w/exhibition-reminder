package com.exporeminder.reminder

import com.exporeminder.data.db.ExhibitionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

/**
 * 提醒引擎:每日評估制。
 * 每天同步後對「追蹤中」的展覽全量評估,該發而帳本(NotificationLog)沒記錄的就發。
 * 手機關機錯過的,只要還在規則的有效窗口內就會補發;窗口外不補(避免連環轟炸)。
 * 同一檔展同一天多條日期規則命中,只發最緊急一條;開賣(R3)是事件驅動,獨立發送。
 */
class ReminderEngine(
    private val closingFirstDays: Long = 14,
    private val closingSecondDays: Long = 3,
    private val saleNoticeDays: Long = 3,
) {

    data class Candidate(
        val ruleKey: String,
        val refKey: String,
        val exhibitionId: String?,   // R4 週摘要為 null
        val title: String,
        val message: String,
        val urgency: Int,            // 數字越小越緊急,同展取最小
    )

    /** 開賣事件,由同步時的快照 diff 產生。 */
    data class SaleEvent(
        val exhibition: ExhibitionEntity,
        val signature: String,        // 事件簽名,進 refKey 防重發:onsale / link:{udnId}
        val ticketUrl: String,
        val viaTitle: String? = null, // 經 suggestedLink 觸發時,udn 那側的展名
        val saleTimeText: String? = null, // udn 那側尚未開賣時的開賣時間(MM/dd HH:mm)
    )

    fun evaluate(
        today: LocalDate,
        followed: List<ExhibitionEntity>,
        saleEvents: List<SaleEvent>,
    ): List<Candidate> {
        val dateRules = followed.mapNotNull { pickDateRule(today, it) }
        val sales = saleEvents.map { ev ->
            Candidate(
                ruleKey = "R3",
                refKey = "${ev.exhibition.id}:${ev.signature}",
                exhibitionId = ev.exhibition.id,
                title = "開賣了:${ev.exhibition.title}",
                message = when {
                    ev.viaTitle != null && ev.saleTimeText != null ->
                        "udn 售票網出現同名檔期「${ev.viaTitle}」,${ev.saleTimeText} 開賣"
                    ev.viaTitle != null ->
                        "udn 售票網出現同名檔期「${ev.viaTitle}」,點開購票頁看看"
                    else -> "這檔展開始售票了,點開購票頁"
                },
                urgency = 0,
            )
        }
        val saleNotices = followed.mapNotNull { saleNotice(today, it) }
        val weekly = weeklyDigest(today, followed)
        return sales + saleNotices + dateRules + listOfNotNull(weekly)
    }

    /**
     * R5 即將開賣:開賣日前 saleNoticeDays 天內發一次(單發;開賣當天 R3 會再響一次)。
     * refKey 帶開賣時間,主辦改開賣時間會自動重發。開賣時間已過不發(R3 的事)。
     */
    private fun saleNotice(today: LocalDate, ex: ExhibitionEntity): Candidate? {
        val saleAt = ex.saleStartAt ?: return null
        val dt = try {
            LocalDateTime.parse(saleAt)
        } catch (_: Exception) {
            return null
        }
        val daysUntil = ChronoUnit.DAYS.between(today, dt.toLocalDate())
        if (daysUntil !in 0..saleNoticeDays) return null
        val timeText = dt.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        val message = if (daysUntil == 0L) "今天 ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))} 開賣,準備搶票!"
        else "$daysUntil 天後開賣($timeText)"
        return Candidate(
            ruleKey = "R5",
            refKey = "${ex.id}:$saleAt",
            exhibitionId = ex.id,
            title = "即將開賣:${ex.title}",
            message = message,
            urgency = 0,
        )
    }

    /** 同展同日只挑一條日期規則:閉展迫近 > 開展 > 閉展預告。 */
    private fun pickDateRule(today: LocalDate, ex: ExhibitionEntity): Candidate? {
        val start = parse(ex.startDate) ?: return null
        val end = parse(ex.endDate) ?: return null
        val daysLeft = ChronoUnit.DAYS.between(today, end)
        val removedHint = if (ex.removedAt != null) "(此展已從來源移除,日期為最後已知資訊)" else ""

        // R2 第二段:剩 [0, closingSecondDays] 天
        if (daysLeft in 0..closingSecondDays) {
            val closeHint = ex.closeTime?.takeIf { it.isNotEmpty() }
            val message = if (daysLeft == 0L)
                "今天結束" + (closeHint?.let { ",開放至 $it" } ?: "") + removedHint
            else "只剩 $daysLeft 天(${ex.endDate} 閉展)$removedHint"
            return Candidate(
                ruleKey = "R2_$closingSecondDays",
                refKey = "${ex.id}:${ex.endDate}",
                exhibitionId = ex.id,
                title = "快結束了:${ex.title}",
                message = message,
                urgency = 1,
            )
        }
        // R1 開展:today ∈ [start, start+2],錯過最多補 2 天
        val sinceStart = ChronoUnit.DAYS.between(start, today)
        if (sinceStart in 0..2) {
            return Candidate(
                ruleKey = "R1",
                refKey = ex.id,
                exhibitionId = ex.id,
                title = "開展了:${ex.title}",
                message = "${ex.startDate} 起在${ex.venueName.ifEmpty { ex.city }}展出$removedHint",
                urgency = 2,
            )
        }
        // R2 第一段:剩 (closingSecondDays, closingFirstDays] 天
        if (daysLeft in (closingSecondDays + 1)..closingFirstDays) {
            return Candidate(
                ruleKey = "R2_$closingFirstDays",
                refKey = "${ex.id}:${ex.endDate}",
                exhibitionId = ex.id,
                title = "別忘了:${ex.title}",
                message = "剩 $daysLeft 天(${ex.endDate} 閉展)$removedHint",
                urgency = 3,
            )
        }
        return null
    }

    /** R4:每週日彙總。refKey = ISO 週,同一週只發一次。 */
    private fun weeklyDigest(today: LocalDate, followed: List<ExhibitionEntity>): Candidate? {
        if (today.dayOfWeek != DayOfWeek.SUNDAY) return null
        val wf = WeekFields.ISO
        val weekStart = today.with(wf.dayOfWeek(), 1)
        val opening = followed.filter { ex ->
            parse(ex.startDate)?.let { it >= weekStart && it <= today.plusDays(7) } == true
        }
        val closing = followed.filter { ex ->
            parse(ex.endDate)?.let {
                ChronoUnit.DAYS.between(today, it) in 0..13
            } == true
        }
        if (opening.isEmpty() && closing.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (opening.isNotEmpty()) parts += "本週開展 ${opening.size} 檔:" +
            opening.joinToString("、") { it.title }
        if (closing.isNotEmpty()) parts += "兩週內閉展 ${closing.size} 檔:" +
            closing.joinToString("、") { it.title }
        val week = "%d-W%02d".format(
            today.get(wf.weekBasedYear()), today.get(wf.weekOfWeekBasedYear()))
        return Candidate(
            ruleKey = "R4",
            refKey = week,
            exhibitionId = null,
            title = "本週展覽摘要",
            message = parts.joinToString(";"),
            urgency = 9,
        )
    }

    private fun parse(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (_: Exception) {
        null
    }
}
