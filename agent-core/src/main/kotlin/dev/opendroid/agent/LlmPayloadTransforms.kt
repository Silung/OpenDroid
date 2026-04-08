package dev.opendroid.agent

private const val TEXT_ONLY_IMAGE_OMIT_SUFFIX =
    "\n\n[OpenDroid] 纯文本模式：截图像素未发送至模型。请仅依据上文 JSON（含 width/height、jpegByteLength、omniparser.parsed_content_list 等）理解界面；勿假定你见过图像像素。"

/**
 * 在构造 LLM 请求前调用：去掉所有 [ContentBlock.ToolResult.images]，避免 OpenAI `image_url` / Anthropic `image` 块进入请求。
 * 会话列表 [conversation] 本身不变，仅用于 [LlmRequest.messages] 的派生副本。
 */
fun List<ChatMessage>.omitToolResultImagesForLlm(): List<ChatMessage> =
    map { msg ->
        if (msg.role != ChatRole.User) return@map msg
        val newBlocks = msg.blocks.map { block ->
            when (block) {
                is ContentBlock.ToolResult ->
                    if (block.images.isEmpty()) {
                        block
                    } else {
                        ContentBlock.ToolResult(
                            toolUseId = block.toolUseId,
                            content = block.content + TEXT_ONLY_IMAGE_OMIT_SUFFIX,
                            isError = block.isError,
                            images = emptyList(),
                        )
                    }
                else -> block
            }
        }
        if (newBlocks == msg.blocks) msg else ChatMessage(msg.role, newBlocks)
    }
