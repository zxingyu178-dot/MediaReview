package com.mediareview.app.feature.v2.viewer

import com.mediareview.app.core.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class Stage8IImagePolicyTest {
    @Test fun profilesPreserveVersionOriginAndDemo() {
        val raw = "https://paired.example:8443/api/v1/media/p1/cover?v=s%2F2&extra=ok&profile=ALBUM"
        val url = thumbnailProfileUrl(raw, ThumbnailProfile.GRID_SMALL).toHttpUrl()
        assertEquals("paired.example", url.host)
        assertEquals(8443, url.port)
        assertEquals("/api/v1/media/p1/thumbnail", url.encodedPath)
        assertEquals("s/2", url.queryParameter("v"))
        assertEquals("ok", url.queryParameter("extra"))
        assertEquals(listOf("GRID_SMALL"), url.queryParameterValues("profile"))
        for (rawDemo in listOf("android.resource://app/123", "file:///demo.jpg", "https://jellyfin.example/Items/1/Images/Primary", "https://paired.example/api/v1/media/p1/original?v=2")) {
            assertEquals(rawDemo, thumbnailProfileUrl(rawDemo, ThumbnailProfile.ALBUM))
        }
    }
    @Test fun originalsUseOnlyImmediateNeighborsAndSourceVersionKeys() {
        assertEquals(listOf(51, 49), adjacentOriginalIndices(50, 100))
        assertEquals(listOf(1), adjacentOriginalIndices(0, 100))
        assertEquals(listOf(98), adjacentOriginalIndices(99, 100))
        assertNotEquals(originalImageCacheKey("source1", "u?v=1"), originalImageCacheKey("source2", "u?v=1"))
        assertNotEquals(originalImageCacheKey("source1", "u?v=1"), originalImageCacheKey("source1", "u?v=2"))
    }
    @Test fun onlySameOriginAndSameMediaCanSupplyOriginalVersion() {
        val original = "https://paired.example/api/v1/media/p1/original?other=ok"
        val cover = "https://paired.example/api/v1/media/p1/thumbnail?v=new"
        assertEquals("new", originalSourceVersionUrl(original, cover).toHttpUrl().queryParameter("v"))
        assertEquals("ok", originalSourceVersionUrl(original, cover).toHttpUrl().queryParameter("other"))
        assertEquals(original, originalSourceVersionUrl(original, cover.replace("paired.example", "foreign.example")))
        assertEquals(original, originalSourceVersionUrl(original, cover.replace("/p1/", "/p2/")))
        val own = "$original&v=authoritative"
        assertEquals(own, originalSourceVersionUrl(own, cover))
        assertEquals("android.resource://app/123", originalSourceVersionUrl("android.resource://app/123", cover))
    }
    @Test fun mediaIdNamedCoverIsNotMistakenForTheResourceEndpoint() {
        val original = "https://paired.example/api/v1/media/cover/original"
        val cover = "https://paired.example/api/v1/media/cover/thumbnail?v=fingerprint"
        assertEquals("fingerprint", originalSourceVersionUrl(original, cover).toHttpUrl().queryParameter("v"))
    }
}
