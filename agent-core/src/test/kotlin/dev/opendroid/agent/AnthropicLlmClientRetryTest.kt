package dev.opendroid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicLlmClientRetryTest {

    @Test
    fun computeRetryDelay_honorsRetryAfter() {
        val ms = AnthropicLlmClient.computeRetryDelayMs(attempt = 1, retryAfterMs = 12_000L)
        assertEquals(12_000L, ms)
    }

    @Test
    fun computeRetryDelay_exponentialWithoutHeader() {
        val a1 = AnthropicLlmClient.computeRetryDelayMs(1, null)
        val a2 = AnthropicLlmClient.computeRetryDelayMs(2, null)
        assertTrue(a1 in 500L..800L)
        assertTrue(a2 >= a1)
        assertTrue(a2 <= 50_000L)
    }
}
