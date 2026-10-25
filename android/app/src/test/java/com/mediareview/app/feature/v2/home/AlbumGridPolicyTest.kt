package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlbumGridPolicyTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test fun `media are grouped into stable month day sections`() {
        val first = media("a", 2026, 10, 26)
        val second = media("b", 2026, 10, 26)
        val third = media("c", 2026, 10, 25)

        val groups = groupAlbumMediaByDay(listOf(first, second, third), zone)

        assertEquals(listOf("2026年10月26日", "2026年10月25日"), groups.map { it.title })
        assertEquals(listOf("a", "b"), groups.first().items.map { it.id })
        assertEquals(listOf("c"), groups.last().items.map { it.id })
    }

    @Test fun `invalid timestamps remain visible in unknown date section`() {
        val groups = groupAlbumMediaByDay(listOf(media("unknown", 1970, 1, 1).copy(dateMillis = 0L)), zone)
        assertEquals("日期未知", groups.single().title)
        assertEquals("unknown", groups.single().items.single().id)
    }

    @Test fun `mixed media preserve input order and video duration`() {
        val image = media("image", 2026, 10, 26)
        val video = media("video", 2026, 10, 26).copy(type = V2MediaType.VIDEO, durationMs = 65_000L)
        val group = groupAlbumMediaByDay(listOf(image, video), zone).single()
        assertEquals(listOf("image", "video"), group.items.map { it.id })
        assertTrue(group.items.last().isVideo)
        assertEquals("01:05", formatDuration(group.items.last().durationMs))
    }

    @Test fun `grid headers translate to nearest media index for prefetch`() {
        val entries = buildAlbumGridEntries(listOf(media("a", 2026, 10, 26), media("b", 2026, 10, 25)), zone)
        assertEquals(0, mediaIndexAtOrAfter(entries, 0))
        assertEquals(0, mediaIndexAtOrAfter(entries, 1))
        assertEquals(1, mediaIndexAtOrAfter(entries, 2))
        assertEquals(1, mediaIndexAtOrAfter(entries, 3))
    }

    private fun media(id: String, year: Int, month: Int, day: Int): V2Media {
        val calendar = Calendar.getInstance(zone).apply {
            clear(); set(year, month - 1, day, 12, 0, 0)
        }
        return V2Media(id, id, id, "album", "相册", V2MediaType.IMAGE, 0L, 1L,
            calendar.timeInMillis, false, false, "", "", null, null, 1, 1)
    }
}
