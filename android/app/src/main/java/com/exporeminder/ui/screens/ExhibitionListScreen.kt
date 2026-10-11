package com.exporeminder.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.exporeminder.ui.CITY_UNKNOWN
import com.exporeminder.ui.ExhibitionRow
import com.exporeminder.ui.Kind
import com.exporeminder.ui.Tab as UiTab
import com.exporeminder.ui.UiState
import java.time.LocalDate
import java.time.ZoneId

private val SOURCE_LABEL = mapOf(
    "moc" to "藝文", "udn" to "特展", "twtc" to "商展",
    "ff" to "同人展", "cwt" to "同人展",
)

private val ONGOING_GREEN = Color(0xFF4CAF50)
private val UPCOMING_ORANGE = Color(0xFFFF9800)

private fun cardLabel(ex: com.exporeminder.data.db.ExhibitionEntity): String =
    if (ex.category == "演唱會") "演唱會" else SOURCE_LABEL[ex.source] ?: ex.source

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExhibitionListScreen(
    modifier: Modifier = Modifier,
    state: UiState,
    onTab: (UiTab) -> Unit,
    onQuery: (String) -> Unit,
    onKind: (Kind) -> Unit,
    onToggleCity: (String) -> Unit,
    onClearCities: () -> Unit,
    onToggleFollow: (ExhibitionRow) -> Unit,
    onToggleMute: (ExhibitionRow) -> Unit,
    onFreeOnly: (Boolean) -> Unit,
    onAcgOnly: (Boolean) -> Unit,
    onDistanceMode: (Boolean) -> Unit,
    onLocation: (Double, Double) -> Unit,
    onLocationFailed: (String) -> Unit,
    onDismissLocationNotice: () -> Unit,
    onCloseDetail: () -> Unit,
    batteryExempt: Boolean,
    onRequestBatteryExempt: () -> Unit,
) {
    var localDetail by remember { mutableStateOf<ExhibitionRow?>(null) }
    var cityPanelOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 進行中的定位請求,重按或離開畫面時要能取消
    var locateSignal by remember { mutableStateOf<CancellationSignal?>(null) }

    /**
     * 主動取一次目前位置。
     *
     * 以前只用 getLastKnownLocation():手機沒快取過位置時它回 null,整個功能就靜默失效
     * ——使用者按了晶片什麼都不會發生、也沒有任何說明。現在改用 getCurrentLocation
     * (API 30+)主動要一個新的定位;API 26–29 沒有這個方法,退回上次已知位置,
     * 但真的拿不到時一定要講,不再靜默。
     */
    fun grabLocation() {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            onLocationFailed("這台裝置沒有定位服務,無法排序")
            return
        }
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            onLocationFailed("系統定位已關閉,請開啟後再試(設定 → 位置)")
            return
        }
        fun fallbackLastKnown() {
            val last = lm.allProviders.firstNotNullOfOrNull {
                runCatching { lm.getLastKnownLocation(it) }.getOrNull()
            }
            if (last != null) {
                onLocation(last.latitude, last.longitude)
                onDistanceMode(true)
                // 快取位置可能很久以前;明講是快取,免得使用者以為是剛剛的定位而不解為何排序怪
                last.time?.let { onLocationFailed("目前定位收不到訊號,暫用上次已知位置排序") }
            } else {
                onLocationFailed("暫時拿不到位置,請確認已允許定位後再試一次")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locateSignal?.cancel()
            val signal = CancellationSignal()
            locateSignal = signal
            runCatching {
                lm.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER, signal,
                    context.mainExecutor,
                ) { loc ->
                    locateSignal = null
                    if (loc != null) {
                        onLocation(loc.latitude, loc.longitude)
                        onDistanceMode(true)
                    } else {
                        fallbackLastKnown()   // 主動取失敗,至少用上次已知位置
                    }
                }
            }.onFailure {
                locateSignal = null
                fallbackLastKnown()
            }
        } else {
            fallbackLastKnown()
        }
    }

    val locPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) grabLocation()
        else onLocationFailed("沒有定位權限,無法依距離排序(可到系統設定開啟)")
    }

    Column(modifier.fillMaxSize()) {
        if (!batteryExempt) {
            Text(
                "⚠ 省電機制可能擋住每日提醒,點此允許背景運作",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRequestBatteryExempt)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.dataStale) {
            Text(
                "⚠ 資料超過 2 天未更新,請檢查你的 GitHub Actions 是否正常",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // 「離我近」失敗或目前用了上次快取位置時的說明;點一下可關掉
        state.locationNotice?.let { notice ->
            Text(
                text = notice,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissLocationNotice)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        TabRow(selectedTabIndex = if (state.tab == UiTab.ALL) 0 else 1) {
            Tab(state.tab == UiTab.ALL, onClick = { onTab(UiTab.ALL) },
                text = { Text("全部") })
            Tab(state.tab == UiTab.FOLLOWED, onClick = { onTab(UiTab.FOLLOWED) },
                text = { Text("追蹤中") })
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = { Text("搜尋展名 / 場地 / 縣市") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf(Kind.ALL) + state.kindPrefs.filter { it.second }.map { it.first }).forEach { k ->
                FilterChip(
                    selected = state.kind == k,
                    onClick = { onKind(k) },
                    label = { Text(k.label) },
                )
            }
            FilterChip(
                selected = state.acgOnly,
                onClick = { onAcgOnly(!state.acgOnly) },
                label = { Text("漫展/ACG") },
            )
            FilterChip(
                selected = state.selectedCities.isNotEmpty(),
                onClick = { cityPanelOpen = !cityPanelOpen },
                label = {
                    val n = state.selectedCities.size
                    Text(if (n == 0) "地區 ▾" else "地區($n) " + if (cityPanelOpen) "▴" else "▾")
                },
            )
            FilterChip(
                selected = state.freeOnly,
                onClick = { onFreeOnly(!state.freeOnly) },
                label = { Text("免費") },
            )
            FilterChip(
                selected = state.distanceMode,
                onClick = {
                    if (state.distanceMode) {
                        onDistanceMode(false)
                    } else if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        grabLocation()
                    } else {
                        locPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                },
                label = { Text("離我近") },
            )
        }
        AnimatedVisibility(visible = cityPanelOpen) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.cityOptions.forEach { (city, count) ->
                    FilterChip(
                        selected = city in state.selectedCities,
                        onClick = { onToggleCity(city) },
                        label = {
                            Text(if (city == CITY_UNKNOWN) "未標地區($count)" else "$city($count)")
                        },
                    )
                }
                if (state.selectedCities.isNotEmpty()) {
                    AssistChip(onClick = onClearCities, label = { Text("清除") })
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(state.rows, key = { it.ex.id }) { row ->
                ExhibitionCard(row, onToggleFollow) { localDetail = row }
            }
        }
    }

    // 通知點進來的詳情優先於清單點擊
    val detail = state.detailRow ?: localDetail
    detail?.let { row ->
        DetailDialog(
            row,
            onDismiss = {
                if (state.detailRow != null) onCloseDetail() else localDetail = null
            },
            onToggleFollow = onToggleFollow,
            onToggleMute = onToggleMute,
        )
    }
}

@Composable
private fun ExhibitionCard(
    row: ExhibitionRow,
    onToggleFollow: (ExhibitionRow) -> Unit,
    onClick: () -> Unit,
) {
    val ex = row.ex
    val border = when {
        row.notStarted -> row.daysToStart?.takeIf { it <= 14 }
            ?.let { BorderStroke(2.dp, UPCOMING_ORANGE) }
        else -> BorderStroke(2.dp, ONGOING_GREEN)
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), border = border) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ex.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = ex.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(ex.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                val hours = if (!ex.openTime.isNullOrEmpty() && !ex.closeTime.isNullOrEmpty())
                    " · ${ex.openTime}–${ex.closeTime}" else ""
                Text(
                    "${ex.startDate} ~ ${ex.endDate}$hours",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(cardLabel(ex)) })
                    val badge = when {
                        row.notStarted && row.daysToStart != null && row.daysToStart <= 14 ->
                            "${row.daysToStart} 天後開展"
                        row.notStarted -> "即將開展"
                        row.daysLeft == 0L ->
                            "今天結束" + (ex.closeTime?.takeIf { it.isNotEmpty() }
                                ?.let { "(開放至 $it)" } ?: "")
                        row.daysLeft != null && row.daysLeft <= 14 -> "剩 ${row.daysLeft} 天"
                        else -> null
                    }
                    badge?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    row.distanceKm?.let {
                        Text("%.1f km".format(it), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        listOf(ex.city, ex.venueName).filter { it.isNotEmpty() }
                            .distinct().joinToString(" "),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    )
                }
                row.saleDisplay?.let {
                    Text("🎫 $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (row.udnHint != null && ex.source != "udn") {
                    Text(row.udnHint, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (row.muted) {
                    Text("🔕 已靜音", style = MaterialTheme.typography.labelSmall)
                }
                if (ex.removedAt != null) {
                    Text("⚠ 來源已移除,資訊可能過期", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { onToggleFollow(row) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (row.followed) "取消追蹤" else "追蹤",
                    tint = if (row.followed) Color(0xFFFFC107)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun addToCalendar(context: Context, row: ExhibitionRow) {
    val ex = row.ex
    val zone = ZoneId.of("Asia/Taipei")
    fun epoch(d: String) = runCatching {
        LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()
    val begin = epoch(ex.startDate) ?: return
    val end = epoch(ex.endDate)?.plus(24 * 60 * 60 * 1000) ?: return  // 全天事件含最後一天
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        putExtra(CalendarContract.Events.TITLE, ex.title)
        putExtra(CalendarContract.Events.EVENT_LOCATION,
            listOf(ex.venueName, ex.address).filter { it.isNotEmpty() }.joinToString(" "))
        putExtra(CalendarContract.Events.DESCRIPTION,
            listOf(ex.ticketUrl, ex.officialUrl).filter { it.isNotEmpty() }.joinToString("\n"))
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun DetailDialog(
    row: ExhibitionRow,
    onDismiss: () -> Unit,
    onToggleFollow: (ExhibitionRow) -> Unit,
    onToggleMute: (ExhibitionRow) -> Unit,
) {
    val ex = row.ex
    val context = LocalContext.current
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ex.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("展期:${ex.startDate} ~ ${ex.endDate}")
                if (!ex.openTime.isNullOrEmpty() && !ex.closeTime.isNullOrEmpty())
                    Text("開放時間:${ex.openTime}–${ex.closeTime}")
                if (ex.venueName.isNotEmpty() || ex.address.isNotEmpty())
                    Text("地點:${listOf(ex.venueName, ex.address).filter { it.isNotEmpty() }.joinToString("\n")}")
                if (ex.organizer.isNotEmpty()) Text("主辦:${ex.organizer}")
                if (ex.priceText.isNotEmpty()) Text("票價:${ex.priceText}")
                (row.saleDisplay ?: ex.saleStartText?.takeIf { it.isNotEmpty() })?.let {
                    Text("售票時間:$it")
                }
                if (ex.descriptionShort.isNotEmpty())
                    Text(ex.descriptionShort, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    if (ex.ticketUrl.isNotEmpty())
                        TextButton({ open(ex.ticketUrl) }) { Text("購票頁") }
                    if (ex.officialUrl.isNotEmpty())
                        TextButton({ open(ex.officialUrl) }) { Text("官網") }
                    TextButton({ addToCalendar(context, row) }) { Text("行事曆") }
                }
                Row {
                    if (row.followed) {
                        TextButton({ onToggleMute(row) }) {
                            Text(if (row.muted) "取消靜音" else "靜音提醒")
                        }
                    }
                    TextButton({ onToggleFollow(row); onDismiss() }) {
                        Text(if (row.followed) "取消追蹤" else "追蹤")
                    }
                }
            }
        },
    )
}