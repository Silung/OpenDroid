package dev.opendroid.agent

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeCompactionTest {

    @Test
    fun minifyAndCap_trimsWhitespaceAndCaps() {
        val raw = """{"a":  1  ,  "b":[2,3]}"""
        val u = UiTreeCompaction.minifyAndCap(raw, 500)
        assertTrue(u.contains("\"a\":1"))
        val tiny = UiTreeCompaction.minifyAndCap("0123456789", 5)
        assertTrue(tiny.contains("截断"))
        assertTrue(tiny.length <= 80)
    }

    @Test
    fun stubHistoric_keepsOnlyLatestGetUiTree() {
        val stub = "[STUB]"
        val oldTree = ContentBlock.ToolResult("t1", "BIG_OLD", false)
        val newTree = ContentBlock.ToolResult("t2", "SMALL_NEW", false)
        val messages = listOf(
            ChatMessage(ChatRole.User, listOf(ContentBlock.Text("hi"))),
            ChatMessage(
                ChatRole.Assistant,
                listOf(ContentBlock.ToolUse("t1", "get_ui_tree", buildJsonObject { })),
            ),
            ChatMessage(ChatRole.User, listOf(oldTree)),
            ChatMessage(
                ChatRole.Assistant,
                listOf(
                    ContentBlock.ToolUse("t2", "get_ui_tree", buildJsonObject { }),
                ),
            ),
            ChatMessage(ChatRole.User, listOf(newTree)),
        )
        val out = UiTreeCompaction.stubHistoricGetUiTreeResults(messages, stub)
        val firstBatch = out[2].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        val secondBatch = out[4].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals(stub, firstBatch.content)
        assertEquals("SMALL_NEW", secondBatch.content)
    }

    @Test
    fun stubHistoric_doesNotTouchTapToolResult() {
        val messages = listOf(
            ChatMessage(ChatRole.User, listOf(ContentBlock.Text("x"))),
            ChatMessage(
                ChatRole.Assistant,
                listOf(
                    ContentBlock.ToolUse("a", "get_ui_tree", buildJsonObject { }),
                    ContentBlock.ToolUse("b", "tap", buildJsonObject { }),
                ),
            ),
            ChatMessage(
                ChatRole.User,
                listOf(
                    ContentBlock.ToolResult("a", "TREE", false),
                    ContentBlock.ToolResult("b", "{\"ok\":true}", false),
                ),
            ),
        )
        val out = UiTreeCompaction.stubHistoricGetUiTreeResults(messages, "[X]")
        // only one get_ui_tree — no stubbing
        val tr = out[2].blocks.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals("TREE", tr[0].content)
        assertEquals("{\"ok\":true}", tr[1].content)
    }

    @Test
    fun stubHistoricCapture_keepsOnlyLatestScreenshot_andStripsImages() {
        val stub = "[NO_OLD_SHOT]"
        val img = ToolResultImage("image/jpeg", "fake64")
        val oldShot = ContentBlock.ToolResult("s1", "old_meta", false, listOf(img))
        val newShot = ContentBlock.ToolResult("s2", "new_meta", false, listOf(img))
        val messages = listOf(
            ChatMessage(ChatRole.User, listOf(ContentBlock.Text("hi"))),
            ChatMessage(
                ChatRole.Assistant,
                listOf(ContentBlock.ToolUse("s1", "capture_screenshot", buildJsonObject { })),
            ),
            ChatMessage(ChatRole.User, listOf(oldShot)),
            ChatMessage(
                ChatRole.Assistant,
                listOf(ContentBlock.ToolUse("s2", "capture_screenshot", buildJsonObject { })),
            ),
            ChatMessage(ChatRole.User, listOf(newShot)),
        )
        val out = UiTreeCompaction.stubHistoricCaptureScreenshotResults(messages, stub)
        val first = out[2].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        val second = out[4].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals(stub, first.content)
        assertTrue(first.images.isEmpty())
        assertEquals("new_meta", second.content)
        assertEquals(1, second.images.size)
    }

    @Test
    fun stripConsumed_removesCaptureScreenshotImages_inOrder() {
        val stub = "[STRIPPED]"
        val img = ToolResultImage("image/jpeg", "b64")
        val conv = mutableListOf(
            ChatMessage(ChatRole.User, listOf(ContentBlock.Text("hi"))),
            ChatMessage(
                ChatRole.Assistant,
                listOf(ContentBlock.ToolUse("cap", "capture_screenshot", buildJsonObject { })),
            ),
            ChatMessage(
                ChatRole.User,
                listOf(
                    ContentBlock.ToolResult("cap", "{\"ok\":true}", false, listOf(img)),
                ),
            ),
        )
        UiTreeCompaction.stripConsumedCaptureScreenshots(conv, stub)
        val tr = conv[2].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals(stub, tr.content)
        assertTrue(tr.images.isEmpty())
    }

    @Test
    fun stripConsumed_leavesGetUiTreeAndTapUnchanged() {
        val conv = mutableListOf(
            ChatMessage(ChatRole.User, listOf(ContentBlock.Text("hi"))),
            ChatMessage(
                ChatRole.Assistant,
                listOf(
                    ContentBlock.ToolUse("t1", "get_ui_tree", buildJsonObject { }),
                    ContentBlock.ToolUse("t2", "tap", buildJsonObject { }),
                ),
            ),
            ChatMessage(
                ChatRole.User,
                listOf(
                    ContentBlock.ToolResult("t1", "{\"tree\":1}", false),
                    ContentBlock.ToolResult("t2", "{\"ok\":true}", false),
                ),
            ),
        )
        UiTreeCompaction.stripConsumedCaptureScreenshots(conv, "[X]")
        val results = conv[2].blocks.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals("{\"tree\":1}", results[0].content)
        assertEquals("{\"ok\":true}", results[1].content)
    }
}
