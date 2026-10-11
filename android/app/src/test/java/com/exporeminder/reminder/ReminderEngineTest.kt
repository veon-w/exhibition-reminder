package com.exporeminder.reminder

import com.exporeminder.data.db.ExhibitionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderEngineTest {

    private val engine = ReminderEngine(closingFirstDays = 14, closingSecondDays = 3)

    private fun ex(
        id: String = "moc:x",
        start: String,
        end: String,
        removedAt: String? = null,
        saleStartAt: String? = null,
    ) = ExhibitionEntity(
        id = id, source = id.substringBefore(":"), title = "展$id",
        startDate = start, endDate = end,
        venueName = "", address = "", city = "", organizer = "", category = "",
        priceText = "", onSales = "N", saleStartAt = saleStartAt, saleStartText = null,
        openTime = null, closeTime = "17:00", lat = null, lng = null,
        ticketUrl = "", officialUrl = "",
        imageUrl = "", descriptionShort = "", removedAt = removedAt,
    )

    // 週一,避免混入 R4
    private val monday: LocalDate = LocalDate.parse("2026-08-03")

    @Test
    fun `開展日發 R1`() {
        val out = engine.evaluate(monday, listOf(ex(start = "2026-08-03", end = "2026-12-31")), emptyList())
        assertEquals(listOf("R1"), out.map { it.ruleKey })
    }

    @Test
    fun `開展錯過兩天內補發_超過不補`() {
        val hit = engine.evaluate(monday, listOf(ex(start = "2026-08-01", end = "2026-12-31")), emptyList())
        assertEquals(listOf("R1"), hit.map { it.ruleKey })
        val miss = engine.evaluate(monday, listOf(ex(start = "2026-07-31", end = "2026-12-31")), emptyList())
        assertTrue(miss.isEmpty())
    }

    @Test
    fun `閉展前14天窗口發 R2_14`() {
        val out = engine.evaluate(monday, listOf(ex(start = "2026-01-01", end = "2026-08-13")), emptyList())
        assertEquals(listOf("R2_14"), out.map { it.ruleKey })
        // refKey 帶 endDate,改期後可重發
        assertEquals("moc:x:2026-08-13", out[0].refKey)
    }

    @Test
    fun `剩3天內只發 R2_3 不發 R2_14`() {
        val out = engine.evaluate(monday, listOf(ex(start = "2026-01-01", end = "2026-08-04")), emptyList())
        assertEquals(listOf("R2_3"), out.map { it.ruleKey })
    }

    @Test
    fun `閉展當天仍發 R2_3_隔天不發`() {
        val today = engine.evaluate(monday, listOf(ex(start = "2026-01-01", end = "2026-08-03")), emptyList())
        assertEquals(listOf("R2_3"), today.map { it.ruleKey })
        // 當天不寫「剩 0 天」,寫「今天結束」+ 閉館時間
        assertTrue(today[0].message.contains("今天結束"))
        assertTrue(today[0].message.contains("開放至 17:00"))
        val ended = engine.evaluate(monday, listOf(ex(start = "2026-01-01", end = "2026-08-02")), emptyList())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `短展同日命中多規則_只發最緊急`() {
        // 今天開展、後天閉展:R1 和 R2_3 同時命中 → 只發 R2_3
        val out = engine.evaluate(monday, listOf(ex(start = "2026-08-03", end = "2026-08-05")), emptyList())
        assertEquals(listOf("R2_3"), out.map { it.ruleKey })
    }

    @Test
    fun `常設展平時安靜`() {
        val out = engine.evaluate(monday, listOf(ex(start = "2022-09-01", end = "2030-12-31")), emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `開賣事件獨立於日期規則`() {
        val e = ex(start = "2026-01-01", end = "2026-08-04") // 同時命中 R2_3
        val sale = ReminderEngine.SaleEvent(e, "link:udn:abc", "https://t", "同名展")
        val out = engine.evaluate(monday, listOf(e), listOf(sale))
        assertEquals(setOf("R3", "R2_3"), out.map { it.ruleKey }.toSet())
        assertEquals("moc:x:link:udn:abc", out.first { it.ruleKey == "R3" }.refKey)
    }

    @Test
    fun `開賣前3天發R5_窗口外不發`() {
        // 常設展避開日期規則干擾;開賣日 8/06 = 3 天後
        val e = ex(start = "2030-01-01", end = "2030-12-31", saleStartAt = "2026-08-06T12:00")
        val out = engine.evaluate(monday, listOf(e), emptyList())
        assertEquals(listOf("R5"), out.map { it.ruleKey })
        assertEquals("moc:x:2026-08-06T12:00", out[0].refKey)
        assertTrue(out[0].message.contains("3 天後開賣"))
        // 開賣日 8/10 = 7 天後,窗口外
        val far = ex(start = "2030-01-01", end = "2030-12-31", saleStartAt = "2026-08-10T12:00")
        assertTrue(engine.evaluate(monday, listOf(far), emptyList()).isEmpty())
    }

    @Test
    fun `開賣當天發R5_已開賣不發`() {
        val todaySale = ex(start = "2030-01-01", end = "2030-12-31", saleStartAt = "2026-08-03T21:00")
        val out = engine.evaluate(monday, listOf(todaySale), emptyList())
        assertEquals(listOf("R5"), out.map { it.ruleKey })
        assertTrue(out[0].message.contains("今天"))
        val past = ex(start = "2030-01-01", end = "2030-12-31", saleStartAt = "2026-08-02T12:00")
        assertTrue(engine.evaluate(monday, listOf(past), emptyList()).isEmpty())
    }

    @Test
    fun `開賣時間爛格式不炸`() {
        val bad = ex(start = "2030-01-01", end = "2030-12-31", saleStartAt = "八月八日")
        assertTrue(engine.evaluate(monday, listOf(bad), emptyList()).isEmpty())
    }

    @Test
    fun `R5可調天數`() {
        val e = ex(start = "2030-01-01", end = "2030-12-31", saleStartAt = "2026-08-10T12:00")
        val engine7 = ReminderEngine(14, 3, saleNoticeDays = 7)
        assertEquals(listOf("R5"), engine7.evaluate(monday, listOf(e), emptyList()).map { it.ruleKey })
    }

    @Test
    fun `週日發摘要_refKey為ISO週`() {
        val sunday = LocalDate.parse("2026-08-09")
        val out = engine.evaluate(
            sunday,
            listOf(
                ex(id = "moc:a", start = "2026-08-08", end = "2026-12-31"), // 本週開展
                ex(id = "moc:b", start = "2026-01-01", end = "2026-08-20"), // 兩週內閉展
            ),
            emptyList(),
        )
        val r4 = out.first { it.ruleKey == "R4" }
        assertEquals("2026-W32", r4.refKey)
        assertTrue(r4.message.contains("moc:a") && r4.message.contains("moc:b"))
    }

    @Test
    fun `週日沒事就不發摘要`() {
        val sunday = LocalDate.parse("2026-08-09")
        val out = engine.evaluate(
            sunday, listOf(ex(start = "2022-09-01", end = "2030-12-31")), emptyList())
        assertTrue(out.none { it.ruleKey == "R4" })
    }

    @Test
    fun `來源已移除的展仍照最後日期提醒_訊息帶警示`() {
        val out = engine.evaluate(
            monday,
            listOf(ex(start = "2026-01-01", end = "2026-08-04", removedAt = "2026-08-01T08:00:00")),
            emptyList(),
        )
        assertEquals(listOf("R2_3"), out.map { it.ruleKey })
        assertTrue(out[0].message.contains("已從來源移除"))
    }

    @Test
    fun `日期爛掉不炸_直接略過`() {
        val out = engine.evaluate(monday, listOf(ex(start = "not-a-date", end = "2026-08-04")), emptyList())
        assertTrue(out.isEmpty())
    }
}
