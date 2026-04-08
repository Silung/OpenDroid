package dev.opendroid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPayloadTransformsTest {

    @Test
    fun omitToolResultImages_stripsImages_andKeepsText() {
        val img = ToolResultImage("image/jpeg", "abc")
        val msg = ChatMessage(
            ChatRole.User,
            listOf(ContentBlock.ToolResult("t1", """{"ok":true}""", false, listOf(img))),
        )
        val out = listOf(msg).omitToolResultImagesForLlm()
        val tr = out.single().blocks.single() as ContentBlock.ToolResult
        assertTrue(tr.images.isEmpty())
        assertTrue(tr.content.contains("纯文本模式"))
        assertTrue(tr.content.startsWith("""{"ok":true}"""))
    }

    @Test
    fun omitToolResultImages_leavesAssistantAndEmptyToolResults() {
        val a = ChatMessage(ChatRole.Assistant, listOf(ContentBlock.Text("hi")))
        val u = ChatMessage(
            ChatRole.User,
            listOf(ContentBlock.ToolResult("t1", "{}", false, emptyList())),
        )
        val out = listOf(a, u).omitToolResultImagesForLlm()
        assertEquals(2, out.size)
        assertEquals(a, out[0])
        assertEquals(u, out[1])
    }
}
