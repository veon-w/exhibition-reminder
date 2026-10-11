package com.exporeminder.ui

import android.Manifest
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
            LaunchedEffect(pendingId) {
                pendingId?.let { vm.openDetail(it); pendingDetailId.value = null }
            }
            val dark = when (state.settings?.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                var showSettings by remember { mutableStateOf(false) }
                val needSetup = state.settings != null && state.settings!!.jsonUrl.isEmpty()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (showSettings || needSetup) "設定" else "展覽提醒") },
                            actions = {
                                if (!needSetup) IconButton(onClick = { showSettings = !showSettings }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "設定")
                                }
                            }
                        )
                    }
                ) { padding ->
                    if (showSettings || needSetup) {
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            state = state,
                            onSave = { url, f, s, sale ->
                                vm.saveSettings(url, f, s, sale)
                                showSettings = false
                            },
                            onSyncNow = { vm.syncNow() },
                            onTheme = vm::setTheme,
                            onKindPrefs = vm::setKindPrefs,
                            batteryExempt = batteryExempt.value,
                            onRequestBatteryExempt = ::requestBatteryExemption,
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
}