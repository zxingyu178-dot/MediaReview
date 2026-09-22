package com.mediareview.app.feature.v2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.searchDataStore by preferencesDataStore(name = "v2_search_history")

/**
 * 搜索历史持久化（DataStore Preferences）：
 * 保存最近 [MAX_ITEMS] 条，App 重启后仍保留；支持追加去重 / 全部清空。
 */
@Singleton
open class SearchHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val MAX_ITEMS = 10
        private val KEY_ITEMS = stringPreferencesKey("recent_searches")

        /** 追加逻辑（可单测）：去重后置顶，最多保留 [MAX_ITEMS] 条。 */
        fun nextItems(term: String, current: List<String>): List<String> {
            val t = term.trim()
            if (t.isEmpty()) return current
            return (listOf(t) + current).distinct().take(MAX_ITEMS)
        }
    }

    /** 最近搜索（新 → 旧）。 */
    val recentItems: Flow<List<String>> = context.searchDataStore.data.map { prefs ->
        prefs[KEY_ITEMS]
            ?.split("\u0001")
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    open suspend fun current(): List<String> = recentItems.first()

    /** 追加一条搜索词：去重后置顶，最多保留 [MAX_ITEMS] 条。 */
    open suspend fun add(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        context.searchDataStore.edit { prefs ->
            val old = prefs[KEY_ITEMS]?.split("\u0001")?.filter { it.isNotBlank() }.orEmpty()
            prefs[KEY_ITEMS] = nextItems(t, old).joinToString("\u0001")
        }
    }

    open suspend fun clear() {
        context.searchDataStore.edit { prefs ->
            prefs.remove(KEY_ITEMS)
        }
    }
}