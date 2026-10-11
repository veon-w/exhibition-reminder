package com.exporeminder.data.remote

import com.exporeminder.data.db.ExhibitionEntity
import com.exporeminder.data.db.SuggestedLinkEntity
import kotlinx.serialization.Serializable

/** exhibitions.json(schemaVersion 1)的傳輸模型。 */
@Serializable
data class RemoteDoc(
    val schemaVersion: Int,
    val generatedAt: String,
    val sourceStatus: Map<String, String> = emptyMap(),
    val exhibitions: List<RemoteExhibition> = emptyList(),
    val suggestedLinks: List<RemoteLink> = emptyList(),
)

@Serializable
data class RemoteExhibition(
    val id: String,
    val source: String,
    val sourceId: String = "",
    val title: String,
    val startDate: String,
    val endDate: String,
    val venueName: String = "",
    val address: String = "",
    val city: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val organizer: String = "",
    val category: String = "",
    val priceText: String = "",
    val onSales: String = "UNKNOWN",
    val saleStartAt: String? = null,
    val saleStartText: String = "",
    val openTime: String = "",
    val closeTime: String = "",
    val ticketUrl: String = "",
    val officialUrl: String = "",
    val imageUrl: String = "",
    val descriptionShort: String = "",
    val lastSeenAt: String = "",
    val removedAt: String? = null,
) {
    fun toEntity() = ExhibitionEntity(
        id = id, source = source, title = title,
        startDate = startDate, endDate = endDate,
        venueName = venueName, address = address, city = city,
        organizer = organizer, category = category, priceText = priceText,
        onSales = onSales, saleStartAt = saleStartAt, saleStartText = saleStartText,
        openTime = openTime, closeTime = closeTime, lat = lat, lng = lng,
        ticketUrl = ticketUrl, officialUrl = officialUrl,
        imageUrl = imageUrl, descriptionShort = descriptionShort,
        removedAt = removedAt,
    )
}

@Serializable
data class RemoteLink(val idA: String, val idB: String, val confidence: Double) {
    fun toEntity() = SuggestedLinkEntity(idA, idB, confidence)
}
