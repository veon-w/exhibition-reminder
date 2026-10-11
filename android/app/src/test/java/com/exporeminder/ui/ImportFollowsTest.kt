package com.exporeminder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匯入追蹤清單的解析。
 *
 * 這裡守著一個實際踩過的坑:以前是一邊解析一邊逐筆寫入,檔案中間有壞掉的一筆時,
 * 前面幾筆已經進資料庫、使用者卻看到「匯入失敗」。現在改成先整份解析、成功才寫入。
 */
class ImportFollowsTest {

    private val today = "2026-10-05"

    private fun file(vararg entries: String) =
        """{"version":1,"follows":[${entries.joinToString(",")}]}"""

    @Test
    fun `正常檔案全部匯入`() {
        val out = parseFollowsFile(
            file(
                """{"id":"moc:a","followedAt":"2026-09-01","muted":false}""",
                """{"id":"twtc:b","followedAt":"2026-09-02","muted":true}""",
            ),
            today,
        )
        assertEquals(listOf("moc:a", "twtc:b"), out.map { it.exhibitionId })
        assertEquals(false, out[0].muted)
        assertEquals(true, out[1].muted)
        assertEquals("2026-09-01", out[0].followedAt)
    }

    @Test
    fun `檔案壞掉要整批失敗而不是留半套`() {
        // follows 不是陣列 → 拋例外,呼叫端的 runCatching 會擋下來,一筆都不會寫
        var threw = false
        try {
            parseFollowsFile("""{"version":1,"follows":"壞掉"}""", today)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("格式壞掉時應該拋例外,讓呼叫端整批放棄", threw)
    }

    @Test
    fun `沒有id的項目跳過_不影響其他筆`() {
        val out = parseFollowsFile(
            file(
                """{"followedAt":"2026-09-01"}""",           // 沒有 id
                """{"id":"","muted":false}""",                // 空 id
                """{"id":"moc:good"}""",
            ),
            today,
        )
        assertEquals(listOf("moc:good"), out.map { it.exhibitionId })
    }

    @Test
    fun `同一檔內重複的id只留一筆`() {
        val out = parseFollowsFile(
            file("""{"id":"moc:a"}""", """{"id":"moc:a"}""", """{"id":"moc:b"}"""),
            today,
        )
        assertEquals(listOf("moc:a", "moc:b"), out.map { it.exhibitionId })
    }

    @Test
    fun `缺少選填欄位時用預設值`() {
        val out = parseFollowsFile(file("""{"id":"moc:a"}"""), today)
        assertEquals(1, out.size)
        assertEquals(today, out[0].followedAt)
        assertEquals(false, out[0].muted)
    }

    @Test
    fun `空的follows陣列可以接受`() {
        assertEquals(0, parseFollowsFile("""{"follows":[]}""", today).size)
    }
}
