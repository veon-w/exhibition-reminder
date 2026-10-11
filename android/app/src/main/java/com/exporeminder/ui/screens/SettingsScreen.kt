package com.exporeminder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.exporeminder.ui.AppViewModel
import com.exporeminder.ui.Kind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.exporeminder.ui.UiState

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    state: UiState,
    onSave: (url: String, first: Int, second: Int, saleDays: Int) -> Unit,
    onSyncNow: () -> Unit,
    onTheme: (String) -> Unit,
    onKindPrefs: (List<Pair<Kind, Boolean>>) -> Unit,
    batteryExempt: Boolean,
    onRequestBatteryExempt: () -> Unit,
    exactAlarmAllowed: Boolean,
    onRequestExactAlarm: () -> Unit,
    onExport: (Uri, (Int) -> Unit) -> Unit,
    onImport: (Uri, (AppViewModel.ImportResult?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { onExport(it) { n -> toast(if (n >= 0) "已匯出 $n 筆追蹤" else "匯出失敗") } }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            onImport(it) { r ->
                toast(
                    when {
                        r == null -> "匯入失敗(檔案格式不對?)"
                        r.total == 0 -> "檔案裡沒有追蹤項目"
                        // 對不上任何展覽的筆數要講清楚,否則使用者會以為匯入壞了
                        r.matchedUnavailable > 0 ->
                            "已匯入 ${r.added} 筆,其中 ${r.matchedUnavailable} 筆要等同步後才會出現"
                        else -> "已匯入 ${r.added} 筆追蹤"
                    }
                )
            }
        }
    }
    val cfg = state.settings
    var url by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("14") }
    var second by remember { mutableStateOf("3") }
    var saleDays by remember { mutableStateOf("3") }
    LaunchedEffect(cfg) {
        cfg?.let {
            url = it.jsonUrl
            first = it.closingFirstDays.toString()
            second = it.closingSecondDays.toString()
            saleDays = it.saleNoticeDays.toString()
        }
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("資料來源", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("exhibitions.json 網址") },
            placeholder = { Text("https://raw.githubusercontent.com/你的帳號/repo/main/data/exhibitions.json") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("部署方式見專案 README(把 scraper 放上你的 GitHub 即可)",
            style = MaterialTheme.typography.bodySmall)

        Text("主題", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟隨系統", "light" to "亮色", "dark" to "暗色").forEach { (key, label) ->
                FilterChip(
                    selected = (cfg?.theme ?: "system") == key,
                    onClick = { onTheme(key) },
                    label = { Text(label) },
                )
            }
        }

        Text("提醒", style = MaterialTheme.typography.titleMedium)
        Text(
            "所有提醒(開展日、閉展前、開賣、每週摘要)只會針對你按 ★ 追蹤的展覽發送;" +
                "沒追蹤的展覽不會發任何通知。追蹤中的展覽可在詳情頁單獨靜音。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (batteryExempt) {
            Text("✅ 已加入省電白名單,每日提醒可正常運作",
                style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedButton(onClick = onRequestBatteryExempt) {
                Text("⚠ 允許背景運作(否則省電機制可能擋掉提醒)")
            }
        }
        // Android 12/12L 的精確鬧鐘預設關閉。沒開的話提醒仍會發,但時間會漂。
        if (!exactAlarmAllowed) {
            Text(
                "⚠ 系統未允許「鬧鐘與提醒」,每日同步時間可能不準(會晚幾分鐘到幾小時)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onRequestExactAlarm) {
                Text("開啟鬧鐘與提醒權限")
            }
        }

        Text("閉展前提醒", style = MaterialTheme.typography.titleMedium)
        // 第一次必須比第二次早(天數較多)。填反的話第二段視窗會是空的,第一次提醒會
        // 靜默消失 —— 引擎端也會自動排序,這裡先擋一次並告訴使用者發生了什麼。
        val firstPicked = first.toIntOrNull() ?: 14
        val secondPicked = second.toIntOrNull() ?: 3
        val orderSwapped = firstPicked < secondPicked
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = first, onValueChange = { first = it.filter(Char::isDigit) },
                label = { Text("第一次(天)") }, modifier = Modifier.weight(1f),
                isError = orderSwapped,
            )
            OutlinedTextField(
                value = second, onValueChange = { second = it.filter(Char::isDigit) },
                label = { Text("第二次(天)") }, modifier = Modifier.weight(1f),
                isError = orderSwapped,
            )
        }
        Text(
            if (orderSwapped) "⚠ 第一次的天數要比第二次多(例如 14 / 3),儲存時會自動交換"
            else "第一次的天數要比第二次多(例如 14 / 3)",
            style = MaterialTheme.typography.bodySmall,
            color = if (orderSwapped) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("類別顯示與順序", style = MaterialTheme.typography.titleMedium)
        Text("關掉的類別不會出現在清單(已追蹤的不受影響)",
            style = MaterialTheme.typography.bodySmall)
        val prefs = Kind.parsePrefs(cfg?.kindPrefs ?: "")
        prefs.forEachIndexed { i, (kind, enabled) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val m = prefs.toMutableList()
                        m[i] = m[i - 1].also { m[i - 1] = m[i] }
                        onKindPrefs(m)
                    },
                    enabled = i > 0,
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "${kind.label}上移") }
                IconButton(
                    onClick = {
                        val m = prefs.toMutableList()
                        m[i] = m[i + 1].also { m[i + 1] = m[i] }
                        onKindPrefs(m)
                    },
                    enabled = i < prefs.lastIndex,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "${kind.label}下移") }
                Text(kind.label, Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        onKindPrefs(prefs.mapIndexed { j, p -> if (j == i) p.first to on else p })
                    },
                )
            }
        }

        Text("開賣前提醒", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = saleDays, onValueChange = { saleDays = it.filter(Char::isDigit) },
            label = { Text("開賣前幾天提醒") },
            supportingText = { Text("目前只有 udn 售票的檔期有開賣時間資料") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                // 空字串或 0 會讓提醒永遠不發,所以下限夾在 1;順序顛倒就交換。
                val f = (first.toIntOrNull() ?: 14).coerceIn(1, 365)
                val s = (second.toIntOrNull() ?: 3).coerceIn(1, 365)
                onSave(url, maxOf(f, s), minOf(f, s),
                    (saleDays.toIntOrNull() ?: 3).coerceIn(0, 365))
            },
            enabled = url.isNotBlank(),
        ) { Text("儲存") }

        OutlinedButton(onClick = onSyncNow, enabled = cfg?.jsonUrl?.isNotEmpty() == true) {
            Text("立即同步")
        }

        Text("追蹤清單備份(換手機用)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("expo-follows.json") }) {
                Text("匯出")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text("匯入")
            }
        }

        cfg?.let {
            Column {
                if (it.lastSyncAt.isNotEmpty()) Text("上次同步:${it.lastSyncAt}",
                    style = MaterialTheme.typography.bodySmall)
                if (it.lastGeneratedAt.isNotEmpty()) Text("資料產生於:${it.lastGeneratedAt}",
                    style = MaterialTheme.typography.bodySmall)
                if (it.sourceStatus.isNotEmpty()) Text("來源狀態:${it.sourceStatus}",
                    style = MaterialTheme.typography.bodySmall)
                if (it.lastSyncError.isNotEmpty()) Text("同步錯誤:${it.lastSyncError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
