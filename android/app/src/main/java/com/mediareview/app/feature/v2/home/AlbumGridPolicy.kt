package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.V2Media
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AlbumDayGroup(val key: String, val title: String, val items: List<V2Media>)

sealed interface AlbumGridEntry {
    data class Header(val key: String, val title: String) : AlbumGridEntry
    data class Media(val value: V2Media) : AlbumGridEntry
}

fun groupAlbumMediaByDay(
    media: List<V2Media>,
    timeZone: TimeZone = TimeZone.getDefault(),
): List<AlbumDayGroup> {
    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { this.timeZone = timeZone }
    val titleFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).apply { this.timeZone = timeZone }
    return media.groupBy { item ->
        if (item.dateMillis > 0L) keyFormat.format(Date(item.dateMillis)) else "unknown"
    }.map { (key, items) ->
        AlbumDayGroup(
            key = key,
            title = if (key == "unknown") "日期未知" else titleFormat.format(Date(items.first().dateMillis)),
            items = items,
        )
    }
}

fun buildAlbumGridEntries(media: List<V2Media>, timeZone: TimeZone = TimeZone.getDefault()): List<AlbumGridEntry> =
    groupAlbumMediaByDay(media, timeZone).flatMap { group ->
        listOf(AlbumGridEntry.Header(group.key, group.title)) + group.items.map(AlbumGridEntry::Media)
    }

fun mediaIndexAtOrAfter(entries: List<AlbumGridEntry>, gridIndex: Int): Int {
    val total = entries.count { it is AlbumGridEntry.Media }
    if (total == 0) return 0
    return entries.take(gridIndex.coerceAtLeast(0)).count { it is AlbumGridEntry.Media }.coerceAtMost(total - 1)
}
