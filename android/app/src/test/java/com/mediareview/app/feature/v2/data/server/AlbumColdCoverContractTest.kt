package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.onlineServerStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class AlbumColdCoverContractTest {
    @Test fun defaultCoverMetadataNeedsNoMediaCacheAndCustomCoverHydratesOnlySelectedMedia() = runTest {
        val hits = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                hits += path
                val body = when(path) {
                    "/api/v1/media/folders" -> """[{"folder_id":"f","name":"相册","count":1000,"image_count":1000,"cover_media_id":"default","cover_url":"/api/v1/media/default/cover?v=7"}]"""
                    "/api/v1/media/custom" -> """{"media_id":"custom","name":"自选","media_type":"image","library_id":"lib","folder_id":"f","folder_name":"相册","cover_url":"/api/v1/media/custom/cover?v=9"}"""
                    else -> error("不应全量读取媒体：$path")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":$body}""")
            }
        }
        server.start()
        try {
            val base = "http://mediareview.test:${server.port}"
            var custom = false
            val http = OkHttpClient.Builder().dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).build()
            val repository = V2ServerMediaRepository(
                object : V2ServerProfilePort { override suspend fun baseUrl() = base },
                ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true }),
                V2MediaMapper(MediaUrlResolver()), V2PlaybackResolver(MediaUrlResolver()),
                object : V2ServerAlbumCoverPort {
                    override suspend fun allCovers() = if (custom) mapOf("f" to "custom") else emptyMap()
                    override suspend fun setCover(folderId: String, mediaId: String?) = Unit
                }, statusStore = onlineServerStatus(),
            )
            val default = repository.albums().single()
            assertNull(repository.mediaById("default"))
            assertEquals("$base/api/v1/media/default/cover?v=7", default.coverUrl)
            assertEquals(listOf("/api/v1/media/folders"), hits.toList())
            custom = true
            val selected = repository.albums().single()
            assertEquals("custom", selected.coverImageId)
            assertEquals("$base/api/v1/media/custom/cover?v=9", selected.coverUrl)
            assertNotNull(repository.mediaById("custom"))
            repository.albums()
            assertEquals(1, hits.count { it == "/api/v1/media/custom" })
            assertFalse(hits.any { it == "/api/v1/media" })
        } finally { server.shutdown() }
    }
    @Test fun coldVideoCustomCoverFallsBackToServerDefault() = invalidCustomFallsBack("video", "f", false)
    @Test fun coldOtherFolderCustomCoverFallsBackToServerDefault() = invalidCustomFallsBack("image", "other", false)
    @Test fun coldMissingCustomCoverFallsBackToServerDefault() = invalidCustomFallsBack("image", "f", true)

    private fun invalidCustomFallsBack(type: String, folder: String, missing: Boolean) = runTest {
        val hits = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                hits += path
                if (path == "/api/v1/media/custom" && missing) return MockResponse().setResponseCode(404)
                val body = when (path) {
                    "/api/v1/media/folders" -> """[{"folder_id":"f","name":"相册","count":1000,"image_count":1000,"cover_media_id":"default","cover_url":"/api/v1/media/default/cover?v=7"}]"""
                    "/api/v1/media/custom" -> """{"media_id":"custom","name":"无效自选","media_type":"$type","library_id":"lib","folder_id":"$folder","folder_name":"相册","cover_url":"/api/v1/media/custom/cover?v=9"}"""
                    else -> error("禁止全量读取：$path")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":$body}""")
            }
        }
        server.start()
        try {
            val base = "http://mediareview.test:${server.port}"
            val http = OkHttpClient.Builder().dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).build()
            val repository = V2ServerMediaRepository(
                object : V2ServerProfilePort { override suspend fun baseUrl() = base },
                ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true }),
                V2MediaMapper(MediaUrlResolver()), V2PlaybackResolver(MediaUrlResolver()),
                object : V2ServerAlbumCoverPort {
                    override suspend fun allCovers() = mapOf("f" to "custom")
                    override suspend fun setCover(folderId: String, mediaId: String?) = Unit
                }, statusStore = onlineServerStatus(),
            )
            assertNull(repository.mediaById("custom"))
            val selected = repository.albums().single()
            assertEquals("default", selected.coverImageId)
            assertEquals("$base/api/v1/media/default/cover?v=7", selected.coverUrl)
            assertEquals(listOf("/api/v1/media/folders", "/api/v1/media/custom"), hits.toList())
        } finally { server.shutdown() }
    }
}
