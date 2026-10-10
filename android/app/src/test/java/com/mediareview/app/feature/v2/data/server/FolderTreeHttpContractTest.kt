package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.*
import com.mediareview.app.feature.v2.model.*
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FolderTreeHttpContractTest {
    @Test fun missingCapabilitySkipsOnlyTreeButNewTreeUsesDirectPagedMediaContract() = runTest {
        MockWebServer().use { server ->
            server.start()
            val status = V2ServerStatusStore().also { it.recordCompatible("1.2.2", 2) }
            val repository = repository(server, status)
            assertNull(repository.folderTree())
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"items":[],"page":1,"page_size":50,"total":0}}"""))
            repository.mediaPage(V2MediaQuery())
            assertEquals("/api/v1/media", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(V2ServerStatus.Online, status.status.value)
            status.recordCompatible("1.3.0", 2, listOf("folder_tree_v1"))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"folder_id":"ft_child","parent_id":"ft_root","library_id":"l1","name":"电影","child_count":0,"direct_media_count":3,"recursive_media_count":3,"direct_image_count":0}]}"""))
            assertEquals("ft_root", repository.folderTree()!!.single().parentId)
            assertEquals("/api/v1/media/folder-tree", server.takeRequest().requestUrl!!.encodedPath)
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"items":[],"page":2,"page_size":50,"total":75}}"""))
            val result = repository.mediaPage(V2MediaQuery(page = 2, folderId = "ft_child", includeDescendants = false))
            assertEquals(75, result.total)
            val request = server.takeRequest().requestUrl!!
            assertEquals("ft_child", request.queryParameter("folder_id"))
            assertEquals("false", request.queryParameter("include_descendants"))
            assertEquals("2", request.queryParameter("page"))
            assertEquals("50", request.queryParameter("page_size"))
        }
    }
    @Test fun albumCarriesServerCoverURLWithoutCoverMediaInCurrentPage() = runTest {
        MockWebServer().use { server ->
            server.start()
            val status = V2ServerStatusStore().also { it.recordCompatible("1.3.0", 2) }
            val repository = repository(server, status)
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"folder_id":"photos","name":"照片","count":70,"image_count":70,"cover_media_id":"unloaded-cover","cover_url":"/api/v1/media/unloaded-cover/thumbnail?v=7"}]}"""))
            val album = repository.albums().single()
            assertNull(repository.mediaById("unloaded-cover"))
            assertEquals("http://mediareview.test:${server.port}/api/v1/media/unloaded-cover/thumbnail?v=7", album.coverUrl)
            assertEquals(1, server.requestCount)
        }
    }
    private fun repository(server: MockWebServer, status: V2ServerStatusStore): V2ServerMediaRepository {
        val base = "http://mediareview.test:${server.port}"
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
        return V2ServerMediaRepository(
            object : V2ServerProfilePort { override suspend fun baseUrl() = base },
            ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
            V2MediaMapper(MediaUrlResolver()), V2PlaybackResolver(MediaUrlResolver()),
            object : V2ServerAlbumCoverPort {
                override suspend fun allCovers() = emptyMap<String, String>()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            }, status,
        )
    }
}
