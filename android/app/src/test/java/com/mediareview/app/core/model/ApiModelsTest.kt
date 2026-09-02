package com.mediareview.app.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `解析重复分组含成员与保留标记`() {
        val raw = """{"success":true,"data":{
            "group_id":"g1","type":"exact","count":2,
            "media_ids":["m1","m2"],"names":["a.mp4","b.mp4"],
            "size_bytes":2048,"duration_ms":60000,"detail":"大小与时长一致",
            "members":[{"media_id":"m1","name":"a.mp4","keep":true},{"media_id":"m2","name":"b.mp4","keep":false}]},
            "error":null}"""
        val env = json.decodeFromString<Envelope<DuplicateGroupDto>>(raw)
        val group = env.data
        assertTrue(env.success)
        assertEquals("g1", group?.group_id)
        assertEquals(2, group?.count)
        assertEquals(2, group?.members?.size)
        assertTrue(group?.members?.first()?.keep == true)
        assertFalse(group?.members?.last()?.keep == true)
        assertEquals("m2", group?.members?.last()?.media_id)
    }

    @Test
    fun `解析重复扫描任务状态(有任务)`() {
        val raw = """{"success":true,"data":{"task_id":"t9","type":"duplicate_scan",
            "status":"running","progress":55,"error":null},"error":null}"""
        val env = json.decodeFromString<Envelope<DuplicateScanStatusDto>>(raw)
        assertEquals("t9", env.data?.task_id)
        assertEquals("running", env.data?.status)
        assertEquals(55, env.data?.progress)
    }

    @Test
    fun `解析重复扫描任务状态(无任务)`() {
        val raw = """{"success":true,"data":{"task_id":null},"error":null}"""
        val env = json.decodeFromString<Envelope<DuplicateScanStatusDto>>(raw)
        assertNull(env.data?.task_id)
    }

    @Test
    fun `编码保留选择请求体`() {
        val body = DuplicateKeepRequest(media_id = "m1", keep = true)
        val wire = json.encodeToString(body)
        assertTrue(wire.contains("\"media_id\":\"m1\""))
        assertTrue(wire.contains("\"keep\":true"))
    }
}