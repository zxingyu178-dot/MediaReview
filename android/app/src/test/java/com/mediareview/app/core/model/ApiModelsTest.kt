package com.mediareview.app.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段 8:验证服务端统一响应包与配对模型的反序列化(纯 JVM,无 Android 依赖)。 */
class ApiModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `解析服务器健康检查响应`() {
        val raw = """{"success":true,"data":{"status":"ok","database":"ok","version":"0.4.0"},
            "error":null,"request_id":"req-1"}"""
        val env = json.decodeFromString<Envelope<HealthOut>>(raw)
        assertTrue(env.success)
        assertEquals("ok", env.data?.status)
        assertEquals("0.4.0", env.data?.version)
    }

    @Test
    fun `解析配对校验成功响应并提取 token`() {
        val raw = """{"success":true,"data":{"paired":true,"token":"mr_abc123"},"error":null}"""
        val env = json.decodeFromString<Envelope<VerifyOut>>(raw)
        assertTrue(env.data?.paired == true)
        assertEquals("mr_abc123", env.data?.token)
    }

    @Test
    fun `解析配对失败(未配对、无 token)`() {
        val raw = """{"success":true,"data":{"paired":false,"token":""},"error":null}"""
        val env = json.decodeFromString<Envelope<VerifyOut>>(raw)
        assertFalse(env.data?.paired ?: true)
        assertEquals("", env.data?.token)
    }

    @Test
    fun `解析错误响应包`() {
        val raw = """{"success":false,"data":null,
            "error":{"code":"UNAUTHORIZED","message":"设备未配对或凭据失效"},"request_id":"req-2"}"""
        val env = json.decodeFromString<Envelope<Nothing>>(raw)
        assertFalse(env.success)
        assertEquals("UNAUTHORIZED", env.error?.code)
    }
}