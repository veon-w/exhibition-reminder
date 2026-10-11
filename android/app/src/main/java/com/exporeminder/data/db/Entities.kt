package com.exporeminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 鏡射 exhibitions.json 的展覽資料(schemaVersion 1)。 */
@Entity(tableName = "exhibition")
data class ExhibitionEntity(
    @PrimaryKey val id: String,          // "{source}:{sourceId}"
    val source: String,                  // moc | twtc | udn
    val title: String,
    val startDate: String,               // ISO yyyy-MM-dd
    val endDate: String,
    val venueName: String,
    val address: String,
    val city: String,
    val organizer: String,
    val category: String,
    val priceText: String,
    val onSales: String,                 // Y | N | UNKNOWN
    val saleStartAt: String?,            // 開賣時間 ISO "2026-08-08T12:00",目前僅 udn 有
    val saleStartText: String?,          // 開賣時間原始字串(來源格式對不上時的保底)
    val openTime: String?,               // 每日開館 "09:00",目前僅 moc 有
    val closeTime: String?,              // 每日閉館 "17:00"
    val lat: Double?,                    // 場地座標(距離排序用)
    val lng: Double?,
    val ticketUrl: String,
    val officialUrl: String,
    val imageUrl: String,
    val descriptionShort: String,
    val removedAt: String?,              // 來源已移除的標記,提醒續用最後已知日期
)

@Entity(tableName = "follow")
data class FollowEntity(
    @PrimaryKey val exhibitionId: String,
    val followedAt: String,
    @ColumnInfo(defaultValue = "0")
    val muted: Boolean = false,          // 靜音:仍追蹤但不發任何提醒
)

/**
 * 已發送提醒的帳本,防重複 + 判斷補發。
 * refKey 規則:R1/R3=展覽id(R3 另帶事件簽名)、R2=「id:當時endDate」(改期自動產生新 key 可重發)、
 * R4=ISO 週(如 2026-W32)。
 */
@Entity(tableName = "notification_log", primaryKeys = ["ruleKey", "refKey"])
data class NotificationLogEntity(
    val ruleKey: String,
    val refKey: String,
    val firedAt: String,
)

/** 跨源「可能同檔」建議連結(scraper 產出,只建議不合併)。 */
@Entity(tableName = "suggested_link", primaryKeys = ["idA", "idB"])
data class SuggestedLinkEntity(
    val idA: String,
    val idB: String,
    val confidence: Double,
)
