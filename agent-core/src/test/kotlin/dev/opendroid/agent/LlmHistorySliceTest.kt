package dev.opendroid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmHistorySliceTest {

    private fun u() = ChatMessage(ChatRole.User, listOf(ContentBlock.Text("u")))
    private fun a() = ChatMessage(ChatRole.Assistant, listOf(ContentBlock.Text("a")))

    @Test
    fun underAssistantCap_fullList() {
        val m = listOf(u(), a(), u(), a(), u())
        assertEquals(m, sliceChatMessagesForLlmRequest(m, 6))
    }

    @Test
    fun dropsOldestPrefixToMeetAssistantCap_prefersStartingWithUser() {
        // U A U A U A U A  -> 4 assistants, cap 2 => earliest start with <=2 A and leading U is index 4
        val m = listOf(u(), a(), u(), a(), u(), a(), u(), a())
        val out = sliceChatMessagesForLlmRequest(m, 2)
        assertEquals(4, out.size)
        assertTrue(out.first().role == ChatRole.User)
        assertEquals(2, out.count { it.role == ChatRole.Assistant })
    }

    @Test
    fun capOne_assistantOnlyTwoInTail() {
        val m = listOf(u(), a(), u(), a(), u(), a())
        val out = sliceChatMessagesForLlmRequest(m, 1)
        assertEquals(1, out.count { it.role == ChatRole.Assistant })
        assertTrue(out.first().role == ChatRole.User)
    }

    @Test
    fun pinKeepsFirstUserWhenAssistantCapWouldDropIt() {
        val goal = ChatMessage(ChatRole.User, listOf(ContentBlock.Text("user goal")))
        fun toolUser() = ChatMessage(ChatRole.User, listOf(ContentBlock.ToolResult("x", "{}", false)))
        // U_goal, (A,Utool)×7 → 7 assistants；cap=6 时无 pin 会丢掉开头的 user goal
        val m = buildList {
            add(goal)
            repeat(7) {
                add(a())
                add(toolUser())
            }
        }
        val withoutPin = sliceChatMessagesForLlmRequest(m, maxAssistantMessages = 6)
        assertTrue(withoutPin.first() != goal)

        val withPin = sliceChatMessagesForLlmRequest(
            m,
            maxAssistantMessages = 6,
            firstMessageIndexToKeep = 0,
        )
        assertTrue(withPin.first().blocks.any { it is ContentBlock.Text })
        assertEquals("user goal", (withPin.first().blocks.first() as ContentBlock.Text).text)
        assertEquals(7, withPin.count { it.role == ChatRole.Assistant })
    }
}
