package com.exporeminder.ui

import com.exporeminder.data.db.ExhibitionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 展名取自 data/exhibitions.json 實際資料,誤判案例是調關鍵字時真的踩到的。 */
class AcgFilterTest {

    private fun ex(title: String, source: String = "twtc", category: String = "") =
        ExhibitionEntity(
            id = "$source:x", source = source, title = title,
            startDate = "2026-01-01", endDate = "2026-12-31",
            venueName = "", address = "", city = "", organizer = "", category = category,
            priceText = "", onSales = "N", saleStartAt = null, saleStartText = null,
            openTime = null, closeTime = null, lat = null, lng = null,
            ticketUrl = "", officialUrl = "", imageUrl = "", descriptionShort = "",
            removedAt = null,
        )

    @Test
    fun `抓得到主要漫展`() {
        listOf(
            "2026年第25屆漫畫博覽會、2026年第十屆台北國際電影玩具暨玩具創作大展",
            "2026年第十四屆台北國際動漫節",
            "2026台北國際電玩展",
            "2026年第六屆台北國際娛樂數位內容整合展、2026年第八屆台灣國際電玩電競產業展",
            "第二屆台北國際潮流玩具、授權展/ 2026創作者博覽會",
            "2027世界COSPLAY高峰會台灣賽區",
            "2027台北設計玩創意文具展/2027台北國際玩具設計展",
            "台灣電子遊戲機國際產業展",
        ).forEach { assertTrue(it, isAcgEvent(ex(it))) }
    }

    @Test
    fun `具名活動大小寫不敏感`() {
        assertTrue(isAcgEvent(ex("WIRFORCE 2026")))
        assertTrue(isAcgEvent(ex("acg 主題展")))
    }

    @Test
    fun `不誤抓形似的非 ACG 展`() {
        listOf(
            "2026台灣國際軍事戶外玩具槍用品展",                    // 有「玩具」但不是複合詞
            "台北國際兒童博覽會暨益智圖書展",
            "爸爸的友善茶園-沉浸式繪本動畫展",                      // 「動畫展」不是「動畫特展」
            "波隆那世界插畫大獎展",
            "2026年1月【雲林布袋戲館-「掌演乾坤」常設展】",
            "2026第34屆台北國際書展",
            "臺中市藝術亮點-《光·影·境2.0》海至繪畫與微縮場景",
        ).forEach { assertFalse(it, isAcgEvent(ex(it))) }
    }

    @Test
    fun `只看展名不看描述`() {
        val e = ex("旗山生活文化園區常設展").copy(descriptionShort = "館內展出早期公仔與生活器物")
        assertFalse(isAcgEvent(e))
    }

    @Test
    fun `同人場來源整包算 ACG`() {
        // CWT 的展名只有場次代號,關鍵字掃不到,要靠 source 判定
        assertTrue(isAcgEvent(ex("CWT-74 台灣同人誌販售會(台北場)", source = "cwt")))
        assertTrue(isAcgEvent(ex("Fancy Frontier開拓動漫祭 47", source = "ff")))
        // 開拓辦的動漫歌手公演也算 ACG
        assertTrue(isAcgEvent(ex("ASCA LIVE TOUR 2026", source = "ff", category = "演唱會")))
    }

    @Test
    fun `同人展類別只收 ff 與 cwt 的非演唱會`() {
        assertTrue(Kind.DOUJIN.matches(ex("開拓動漫祭 47", source = "ff", category = "同人展")))
        assertTrue(Kind.DOUJIN.matches(ex("CWT-74", source = "cwt", category = "同人展")))
        assertFalse(Kind.DOUJIN.matches(ex("ASCA LIVE", source = "ff", category = "演唱會")))
        assertFalse(Kind.DOUJIN.matches(ex("漫畫博覽會", source = "twtc")))
        // 開拓的公演歸演唱會 chip
        assertTrue(Kind.CONCERT.matches(ex("ASCA LIVE", source = "ff", category = "演唱會")))
    }

    @Test
    fun `舊版設定沒有同人展時自動補上並預設開啟`() {
        val prefs = Kind.parsePrefs("ART:1,EXPO:1,TRADE:0,CONCERT:1")
        assertEquals(true, prefs.first { it.first == Kind.DOUJIN }.second)
        assertEquals(false, prefs.first { it.first == Kind.TRADE }.second)  // 舊設定保留
        assertEquals(Kind.ORDER.toSet(), prefs.map { it.first }.toSet())
    }
}
