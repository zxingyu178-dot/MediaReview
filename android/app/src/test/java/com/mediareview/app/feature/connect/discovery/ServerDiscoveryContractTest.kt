package com.mediareview.app.feature.connect.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDiscoveryContractTest {
    @Test
    fun `解析服务名端口并使用数据包来源 host`() {
        assertEquals(
            DiscoveredServer("192.168.31.20", 8766, "客厅服务器"),
            parseDiscoveryReply("MEDIAREVIEW 客厅服务器\n8766", "192.168.31.20"),
        )
    }

    @Test
    fun `接受合法 advertised port 并忽略回环畸形端口`() {
        assertEquals(
            DiscoveredServer("192.168.31.20", 9123, "Home"),
            parseDiscoveryReply("MEDIAREVIEW Home\n9123", "192.168.31.20"),
        )
        assertNull(parseDiscoveryReply("MEDIAREVIEW Home\n8766", "127.0.0.1"))
        assertNull(parseDiscoveryReply("MEDIAREVIEW Home\n0", "192.168.31.20"))
        assertNull(parseDiscoveryReply("bad", "192.168.31.20"))
    }

    @Test
    fun `候选去重且只有健康确认成功才显示`() = runBlocking {
        val checked = mutableListOf<String>()
        val candidates = confirmHealthyCandidates(
            listOf(
                DiscoveredServer("192.168.31.20", 8766, "A"),
                DiscoveredServer("192.168.31.20", 8766, "A duplicate"),
                DiscoveredServer("192.168.31.21", 8766, "B"),
            ),
        ) { baseUrl ->
            checked += baseUrl
            baseUrl.contains("31.20")
        }

        assertEquals(listOf(DiscoveredServer("192.168.31.20", 8766, "A")), candidates)
        assertEquals(2, checked.size)
    }
}
