package com.mediareview.app.feature.v2.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.exoplayer.source.MediaSource
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import java.io.File
import tv.danmaku.ijk.media.exo2.ExoMediaSourceInterceptListener
import tv.danmaku.ijk.media.exo2.ExoSourceManager

/** Installs only the existing GSY HTTP extension; media-source and disk-cache policy stay stock. */
@OptIn(UnstableApi::class)
class VideoPrefixPlayback(private val cache: VideoPrefixCache) : VideoPrefetchPort, ExoMediaSourceInterceptListener {
    override suspend fun prefetch(endpoint: V2PlaybackEndpoint) {
        synchronized(this) {
            val current = ExoSourceManager.getExoMediaSourceInterceptListener()
            if (current == null) ExoSourceManager.setExoMediaSourceInterceptListener(this)
            else if (current !== this) return
            cache.attachConsumer()
        }
        cache.prefetch(endpoint)
    }

    override fun getMediaSource(url: String?, preview: Boolean, cacheEnable: Boolean, looping: Boolean, cacheDir: File?): MediaSource? = null
    override fun cacheWriteDataSinkFactory(cachePath: String?, url: String?): DataSink.Factory? = null

    override fun getHttpDataSourceFactory(
        userAgent: String?, listener: TransferListener?, connectTimeoutMillis: Int, readTimeoutMillis: Int,
        mapHeadData: MutableMap<String, String>?, allowCrossProtocolRedirects: Boolean,
    ): DataSource.Factory {
        val headers = mapHeadData?.toMap().orEmpty()
        val factory = DefaultHttpDataSource.Factory().setUserAgent(userAgent)
            .setConnectTimeoutMs(connectTimeoutMillis).setReadTimeoutMs(readTimeoutMillis)
            .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects).setDefaultRequestProperties(headers)
        listener?.let(factory::setTransferListener)
        return DataSource.Factory { VideoPrefixDataSource(cache, factory.createDataSource(), headers) }
    }
}

@OptIn(UnstableApi::class)
private class VideoPrefixDataSource(
    private val cache: VideoPrefixCache,
    private val original: HttpDataSource,
    private val headers: Map<String, String>,
) : DataSource {
    private var reader: VideoPrefixReader? = null
    private var originalSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) = original.addTransferListener(transferListener)
    override fun getUri(): Uri? = original.uri ?: originalSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = original.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        originalSpec = dataSpec
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null) return original.open(dataSpec)
        val endpoint = V2PlaybackEndpoint(dataSpec.uri.toString(), headers + dataSpec.httpRequestHeaders)
        val transport = object : VideoRangeConnection {
            override fun open(position: Long, length: Long, ifMatch: String?): VideoRangeResponse {
                val spec = if (ifMatch == null) dataSpec else dataSpec.buildUpon()
                    .setPosition(position).setLength(length)
                    .setHttpRequestHeaders(dataSpec.httpRequestHeaders + mapOf("If-Match" to ifMatch, "Accept-Encoding" to "identity"))
                    .build()
                return try {
                    val openedLength = original.open(spec)
                    VideoRangeResponse(original.responseCode, openedLength, original.uri?.toString().orEmpty(), original.responseHeaders)
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    if (ifMatch == null || error.responseCode !in setOf(400, 405, 412, 416, 501)) throw error
                    VideoRangeResponse(error.responseCode, 0, endpoint.url, emptyMap())
                }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = original.read(buffer, offset, length)
            override fun close() = original.close()
        }
        return VideoPrefixReader(cache, endpoint, transport).also { reader = it }.open(dataSpec.position, dataSpec.length)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reader?.read(buffer, offset, length) ?: original.read(buffer, offset, length)
    override fun close() {
        try { reader?.close() ?: original.close() } finally { reader = null; originalSpec = null }
    }
}
