package dev.opendroid.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AnthropicMessagesEndpointTest {

    @Test
    fun addsV1WhenMissing() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            anthropicMessagesEndpoint("https://api.anthropic.com"),
        )
    }

    @Test
    fun doesNotDuplicateV1() {
        assertEquals(
            "https://api.siliconflow.cn/v1/messages",
            anthropicMessagesEndpoint("https://api.siliconflow.cn/v1/"),
        )
    }
}
