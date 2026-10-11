package com.exporeminder.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exporeminder.ui.screens.ExhibitionListScreen
import com.exporeminder.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 通知點進來要開的展覽(onCreate / onNewIntent 都會塞)
    private val pendingDetailId = mutableStateOf<String?>(null)
    private val batteryExempt = mutableStateOf(true)
    private val exactAlarmAllowed = mutableStateOf(true)
    // 提到 activity 層級:通知點進來時要能強制切回清單頁(設定頁開著的話詳情不會被渲染)
    private val showSettings = mutableStateOf(false)

    /** 排得到精確鬧鐘嗎?API 31+ 要使用者授權(API 33+ 由 USE_EXACT_ALARM 自動授予)。 */
    private fun computeExactAlarmAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = getSystemService(AlarmManager::class.java)
        return runCatching { am?.canScheduleExactAlarms() == true }.getOrDefault(false)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingDetailId.value = intent?.getStringExtra("exhibitionId")

        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsState()
            val pendingId by pendingDetailId
            val showCfg by showSettings
            LaunchedEffect(pendingId) {
                val id = pendingId ?: return@LaunchedEffect
                // 設定頁開著時清單(連同詳情對話框)根本沒被組進去,所以一定要先切回去,
                // 否則使用者點了通知只會看到設定頁,以為通知壞了。
                if (!state.settings?.jsonUrl.isNullOrEmpty()) showSettings.value = false
                vm.openDetail(id)
                pendingDetailId.value = null
            }
            val dark = when (state.settings?.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val needSetup = state.settings != null && state.settings!!.jsonUrl.isEmpty()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (showCfg || needSetup) "設定" else "展覽提醒") },
                            actions = {
                                if (!needSetup) IconButton(onClick = { showSettings.value = !showCfg }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "設定")
                                }
                            }
                        )
                    }
                ) { padding ->
                    if (showCfg || needSetup) {
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            state = state,
                            onSave = { url, f, s, sale ->
                                vm.saveSettings(url, f, s, sale)
                                showSettings.value = false
                            },
                            onSyncNow = { vm.syncNow() },
                            onTheme = vm::setTheme,
                            onKindPrefs = vm::setKindPrefs,
                            batteryExempt = batteryExempt.value,
                            onRequestBatteryExempt = ::requestBatteryExemption,
                            exactAlarmAllowed = exactAlarmAllowed.value,
                            onRequestExactAlarm = ::requestExactAlarmPermission,
                            onExport = vm::exportFollows,
                            onImport = vm::importFollows,
                        )
                    } else {
                        ExhibitionListScreen(
                            modifier = Modifier.padding(padding),
                            state = state,
                            onTab = vm::setTab,
                            onQuery = vm::setQuery,
                            onKind = vm::setKind,
                            onToggleCity = vm::toggleCity,
                            onClearCities = vm::clearCities,
                            onToggleFollow = vm::toggleFollow,
                            onToggleMute = vm::toggleMute,
                            onFreeOnly = vm::setFreeOnly,
                            onAcgOnly = vm::setAcgOnly,
                            onDistanceMode = vm::setDistanceMode,
                            onLocation = vm::setLocation,
                            onLocationFailed = vm::setLocationUnavailable,
                            onDismissLocationNotice = vm::dismissLocationNotice,
                            onClearFilters = vm::clearFilters,
                            onCloseDetail = vm::closeDetail,
                            batteryExempt = batteryExempt.value,
                            onRequestBatteryExempt = ::requestBatteryExemption,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDetailId.value = intent.getStringExtra("exhibitionId")
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        batteryExempt.value = pm.isIgnoringBatteryOptimizations(packageName)
        // 使用者可能剛從系統設定回來,重算精確鬧鐘狀態
        exactAlarmAllowed.value = computeExactAlarmAllowed()
    }

    /** 請求把 app 加入省電白名單 —— 三星/小米等激進省電會讓每日提醒整天不跑。 */
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:$packageName".toUri())
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /**
     * 引導使用者開啟「鬧鐘與提醒」權限。
     * Android 12/12L(API 31–32)的 SCHEDULE_EXACT_ALARM 預設是關的,而 manifest 只在
     * 這個範圍宣告它(33+ 走 USE_EXACT_ALARM 自動授予)。沒開的話提醒會靜默降級成不精確,
     * 可能延後幾分鐘到幾小時——使用者只會覺得「提醒怎麼不準」。
     */
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                "package:$packageName".toUri()))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
        }
    }
}