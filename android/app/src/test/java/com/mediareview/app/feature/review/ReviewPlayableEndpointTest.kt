package com.mediareview.app.feature.review

import com.mediareview.app.core.model.PlaybackEndpointDto
import com.mediareview.app.core.model.PlaybackInfoDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I1 回归:批阅路径从播放信息提取直连 URL + 设备级凭据 headers。
 *
 * - 优先 Task C 的 ``direct`` 端点(含 X-Emby-Token headers);
 * - ``direct`` 缺失/空白时回退 legacy ``stream_url``(无凭据)。
 */
class ReviewPlayableEndpointTest {

    @Test
    fun directEndpointPreferredWithHeaders() {
        val info = PlaybackInfoDto(
            stream_url = "http://legacy/stream",
            direct = PlaybackEndpointDto(
                url = "http://jellyfin/video",
                headers = mapOf("X-Emby-Token" to "device-key"),
            ),
        )
        val (url, headers) = directPlaybackEndpoint(info)
        assertEquals("http://jellyfin/video", url)
        assertEquals(mapOf("X-Emby-Token" to "device-key"), headers)
    }

    @Test
    fun legacyStreamUrlFallbackWithoutHeaders() {
        val info = PlaybackInfoDto(stream_url = "http://legacy/stream")
        val (url, headers) = directPlaybackEndpoint(info)
        assertEquals("http://legacy/stream", url)
        assertEquals(emptyMap<String, String>(), headers)
    }

    @Test
    fun blankDirectFallsBackToStreamUrl() {
        val info = PlaybackInfoDto(
            stream_url = "http://legacy/stream",
            direct = PlaybackEndpointDto(url = "   ", headers = mapOf("X-Emby-Token" to "k")),
        )
        val (url, headers) = directPlaybackEndpoint(info)
        assertEquals("http://legacy/stream", url)
        assertEquals(emptyMap<String, String>(), headers)
    }
}
