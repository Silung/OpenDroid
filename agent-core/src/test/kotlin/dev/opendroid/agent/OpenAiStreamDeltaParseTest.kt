package dev.opendroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiStreamDeltaParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deltaContentJsonNull_yieldsEmpty() {
        val delta = json.parseToJsonElement(
            """{"role":"assistant","content":null}""",
        ).jsonObject
        assertEquals("", openAiStreamDeltaTextPiece(delta))
    }

    @Test
    fun deltaContentAbsent_yieldsEmpty() {
        val delta = json.parseToJsonElement(
            """{"role":"assistant"}""",
        ).jsonObject
        assertEquals("", openAiStreamDeltaTextPiece(delta))
    }

    @Test
    fun deltaContentString_ok() {
        val delta = json.parseToJsonElement(
            """{"content":"Hi"}""",
        ).jsonObject
        assertEquals("Hi", openAiStreamDeltaTextPiece(delta))
    }

    @Test
    fun deltaTextFallback_ok() {
        val delta = json.parseToJsonElement(
            """{"text":"x"}""",
        ).jsonObject
        assertEquals("x", openAiStreamDeltaTextPiece(delta))
    }
}
