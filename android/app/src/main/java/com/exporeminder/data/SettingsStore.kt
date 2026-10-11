package com.exporeminder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 內建資料網址。填了之後 app 裝好一開就有資料,不必手動貼網址;
 * 留空則維持原行為:第一次開啟強制停在設定頁要求貼網址。
 * 這只是預設值 —— 使用者在設定頁存過自己的網址就以他的為準。
 *
 * 格式:https://raw.githubusercontent.com/<帳號>/<repo>/main/data/exhibitions.json
 */
const val DEFAULT_JSON_URL = ""

class SettingsStore(private val context: Context) {

    data class Settings(
        val jsonUrl: String,
        val closingFirstDays: Int,
        val closingSecondDays: Int,
        val lastSyncAt: String,
        val lastGeneratedAt: String,
        val lastSyncError: String,
        val sourceStatus: String,
        val theme: String,           // system | light | dark
        val saleNoticeDays: Int,     // 開賣前 N 天提醒
        val kindPrefs: String,       // 類別順序與顯示,如 "ART:1,EXPO:1,TRADE:0,CONCERT:1"
    )

    companion object {
        private val KEY_URL = stringPreferencesKey("json_url")
        private val KEY_R2_FIRST = intPreferencesKey("closing_first_days")
        private val KEY_R2_SECOND = intPreferencesKey("closing_second_days")
        private val KEY_LAST_SYNC = stringPreferencesKey("last_sync_at")
        private val KEY_GENERATED = stringPreferencesKey("last_generated_at")
        private val KEY_SYNC_ERROR = stringPreferencesKey("last_sync_error")
        private val KEY_SOURCE_STATUS = stringPreferencesKey("source_status")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_SALE_DAYS = intPreferencesKey("sale_notice_days")
        private val KEY_KIND_PREFS = stringPreferencesKey("kind_prefs")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            jsonUrl = p[KEY_URL] ?: DEFAULT_JSON_URL,
            closingFirstDays = p[KEY_R2_FIRST] ?: 14,
            closingSecondDays = p[KEY_R2_SECOND] ?: 3,
            lastSyncAt = p[KEY_LAST_SYNC] ?: "",
            lastGeneratedAt = p[KEY_GENERATED] ?: "",
            lastSyncError = p[KEY_SYNC_ERROR] ?: "",
            sourceStatus = p[KEY_SOURCE_STATUS] ?: "",
            theme = p[KEY_THEME] ?: "system",
            saleNoticeDays = p[KEY_SALE_DAYS] ?: 3,
            kindPrefs = p[KEY_KIND_PREFS] ?: "",
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setTheme(theme: String) =
        context.dataStore.edit { it[KEY_THEME] = theme }

    suspend fun setJsonUrl(url: String) =
        context.dataStore.edit { it[KEY_URL] = url.trim() }

    suspend fun setClosingDays(first: Int, second: Int) = context.dataStore.edit {
        it[KEY_R2_FIRST] = first
        it[KEY_R2_SECOND] = second
    }

    suspend fun setSaleNoticeDays(days: Int) =
        context.dataStore.edit { it[KEY_SALE_DAYS] = days }

    suspend fun setKindPrefs(prefs: String) =
        context.dataStore.edit { it[KEY_KIND_PREFS] = prefs }

    suspend fun recordSync(syncAt: String, generatedAt: String, sourceStatus: String, error: String) =
        context.dataStore.edit {
            it[KEY_LAST_SYNC] = syncAt
            if (generatedAt.isNotEmpty()) it[KEY_GENERATED] = generatedAt
            if (sourceStatus.isNotEmpty()) it[KEY_SOURCE_STATUS] = sourceStatus
            it[KEY_SYNC_ERROR] = error
        }
}
