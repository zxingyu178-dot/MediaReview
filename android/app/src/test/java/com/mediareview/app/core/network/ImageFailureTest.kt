package com.mediareview.app.core.network

import coil.network.HttpException
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFailureTest {
    @Test fun httpFailureShowsStatusAndSafeRequestIdWithoutUrlOrBody() {
        val response = Response.Builder().request(Request.Builder().url("https://private.example/image").build())
            .protocol(Protocol.HTTP_1_1).code(502).message("private upstream message")
            .header("X-Request-ID", "image-request-1").build()
        val text = imageFailureDescription(HttpException(response))
        assertTrue(text.contains("502"))
        assertTrue(text.contains("image-request-1"))
        assertFalse(text.contains("private"))
    }

    @Test fun networkFailureDoesNotExposeExceptionMessage() {
        val text = imageFailureDescription(IOException("secret token and local path"))
        assertTrue(text.contains("网络"))
        assertFalse(text.contains("secret"))
    }
}
