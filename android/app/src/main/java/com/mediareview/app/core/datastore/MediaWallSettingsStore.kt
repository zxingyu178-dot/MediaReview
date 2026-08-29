package com.mediareview.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mediaWallDataStore by preferencesDataStore(name = "media_wall_settings")

data class MediaWallSettings(
    val gridColumns: Int = 3,
    val sortBy: SortField = SortField.Name,
    val sortOrder: SortOrder = SortOrder.Asc,
    val type: MediaTypeFilter = MediaTypeFilter.All,
)

interface MediaWallSettingsDataSource {
    suspend fun current(): MediaWallSettings
    suspend fun save(value: MediaWallSettings)
}

object MediaWallSettingsCodec {
    fun sortField(value: String?): SortField =
        SortField.entries.firstOrNull { it.wire == value } ?: SortField.Name

    fun sortOrder(value: String?): SortOrder =
        SortOrder.entries.firstOrNull { it.wire == value } ?: SortOrder.Asc

    fun mediaType(value: String?): MediaTypeFilter = MediaTypeFilter.fromWire(value)
}

class MediaWallSettingsStore(private val context: Context) : MediaWallSettingsDataSource {
    private val keyGridColumns = intPreferencesKey("grid_columns")
    private val keySortBy = stringPreferencesKey("sort_by")
    private val keySortOrder = stringPreferencesKey("sort_order")
    private val keyType = stringPreferencesKey("media_type")

    val settings: Flow<MediaWallSettings> = context.mediaWallDataStore.data.map { prefs ->
        MediaWallSettings(
            gridColumns = (prefs[keyGridColumns] ?: 3).coerceIn(2, 5),
            sortBy = MediaWallSettingsCodec.sortField(prefs[keySortBy]),
            sortOrder = MediaWallSettingsCodec.sortOrder(prefs[keySortOrder]),
            type = MediaWallSettingsCodec.mediaType(prefs[keyType]),
        )
    }

    override suspend fun current(): MediaWallSettings = settings.first()

    override suspend fun save(value: MediaWallSettings) {
        context.mediaWallDataStore.edit { prefs ->
            prefs[keyGridColumns] = value.gridColumns.coerceIn(2, 5)
            prefs[keySortBy] = value.sortBy.wire
            prefs[keySortOrder] = value.sortOrder.wire
            prefs[keyType] = value.type.wire.orEmpty()
        }
    }
}
