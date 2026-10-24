package com.mediareview.app.feature.v2.organize.data

import com.mediareview.app.core.model.CommitResultDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteReceiptDtoTest {
    @Test
    fun `服务端真实删除字段不能在DTO解码时丢弃`() {
        val json = Json { ignoreUnknownKeys = true }
        val input = """{"outcome":{"a":"success","b":"missing","c":"failed","d":"pending"},"deleted_count":1,"deleted_bytes":4321,"missing_count":1,"failed_count":1,"pending_count":1}"""
        val dto = json.decodeFromString<CommitResultDto>(input)
        val actual = json.parseToJsonElement(json.encodeToString(dto)).jsonObject
        assertEquals("4321", actual["deleted_bytes"]?.toString())
        assertEquals("1", actual["deleted_count"]?.toString())
        assertEquals("1", actual["missing_count"]?.toString())
        assertEquals("1", actual["failed_count"]?.toString())
        assertEquals("1", actual["pending_count"]?.toString())
    }
}
