package com.mediareview.app.feature.connect.discovery

import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 自动发现到的服务器候选。 */
data class DiscoveredServer(
    val host: String,
    val port: Int = 8765,
    val name: String = "",
) {
    val baseUrl: String get() = "http://$host:$port"
}

/**
 * 局域网服务发现。
 *
 * 策略(可替换):
 * 1. UDP 组播 / 广播:向组播/广播地址发一个小数据报,监听响应;
 *    服务器实现为"收到本机自定义探测则回包,给出 ServerName + 端口"。
 * 2. 兜底扫描常见网段端口(可选,默认关闭)。
 *
 * 阶段 8 提供接口与组播实现;若你的服务器不支持探测协议,
 * 可退化到"手动 IP"页,二者并列 UI。
 */
class ServerDiscovery(port: Int = 8765) {

    private val multicastGroup = "239.255.42.99"
    private val multicastPort = 35001

    /** 发送探测并收集响应(阻塞式,切到 IO 线程);超时返回已发现的服务器。 */
    suspend fun discover(timeoutMs: Long = 2000): List<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            java.net.DatagramSocket().use { socket ->
                socket.broadcast = true
                val msg = "MEDIAREVIEW_DISCOVER".toByteArray(Charsets.UTF_8)
                val packet = java.net.DatagramPacket(
                    msg,
                    msg.size,
                    InetAddress.getByName(multicastGroup),
                    multicastPort,
                )
                socket.soTimeout = timeoutMs.toInt()
                socket.send(packet)
                val found = LinkedHashSet<String>()
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val resp = java.net.DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        if (resp.length > 0) {
                            found.add(resp.address.hostAddress ?: continue)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
                found.map { DiscoveredServer(host = it) }
            }
        }
}