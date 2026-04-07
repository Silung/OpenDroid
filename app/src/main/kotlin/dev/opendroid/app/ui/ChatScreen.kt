package dev.opendroid.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opendroid.agent.ToolResultImage
import dev.opendroid.app.AppLocale
import dev.opendroid.app.ChatLine
import dev.opendroid.app.ChatViewModel
import dev.opendroid.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val userBubbleShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
private val agentBubbleShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
private val composerFieldShape = RoundedCornerShape(22.dp)

@Composable
private fun ToolResultImageThumbnails(images: List<ToolResultImage>) {
    if (images.isEmpty()) return
    val cd = stringResource(R.string.cd_tool_screenshot_thumbnail)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        for (img in images) {
            val bitmap = remember(img.mediaType, img.base64Data) {
                runCatching {
                    val bytes = Base64.decode(img.base64Data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = cd,
                    modifier = Modifier
                        .height(120.dp)
                        .widthIn(max = 220.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun UserMessageBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = 312.dp)
                .padding(start = 48.dp),
            shape = userBubbleShape,
            color = OpenDroidChatPalette.userBubbleBackground,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, OpenDroidChatPalette.userBubbleOutline),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenDroidChatPalette.userBubbleText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AgentMessageBubble(text: String, streaming: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = 320.dp)
                .padding(end = 40.dp),
            shape = agentBubbleShape,
            color = OpenDroidChatPalette.agentBubbleBackground,
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, OpenDroidChatPalette.agentBubbleOutline),
        ) {
            Text(
                text = text + if (streaming) " …" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = OpenDroidChatPalette.agentText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    appVersionLabel: String,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    val lines by vm.lines.collectAsState()
    val busy by vm.busy.collectAsState()
    val asrBusy by vm.asrBusy.collectAsState()
    val voiceRecording by vm.voiceRecording.collectAsState()
    val input by vm.input.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val activeSessionId by vm.activeSessionId.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chatListState = rememberLazyListState()
    val sessionTimeFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
    val micPermission = Manifest.permission.RECORD_AUDIO
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startVoiceRecording()
    }

    LaunchedEffect(lines, busy) {
        if (lines.isEmpty() || !busy) return@LaunchedEffect
        val target = lines.lastIndex
        if (target < 0) return@LaunchedEffect
        runCatching { chatListState.scrollToItem(target) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.75f),
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = appVersionLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.skill_management)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                onOpenSkills()
                            }
                        },
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.settings)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                onOpenSettings()
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.language_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                    val localeTag = AppLocale.getStoredTag(context)
                    val langOptions = listOf(
                        AppLocale.TAG_ZH to "中文",
                        AppLocale.TAG_EN to "English",
                    )
                    val selectedLangIndex = langOptions.indexOfFirst { it.first == localeTag }
                        .takeIf { it >= 0 } ?: 0
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        langOptions.forEachIndexed { index, (tag, label) ->
                            SegmentedButton(
                                selected = index == selectedLangIndex,
                                onClick = { AppLocale.setLocale(context, tag) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = langOptions.size,
                                ),
                            ) { Text(label) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(
                        text = stringResource(R.string.chat_history),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                vm.newSession()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.new_session))
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        items(
                            items = sessions,
                            key = { it.id },
                        ) { s ->
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(
                                            text = s.title.ifBlank { stringResource(R.string.default_session_title) },
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = sessionTimeFormat.format(Date(s.updatedAt)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                selected = s.id == activeSessionId,
                                onClick = {
                                    scope.launch {
                                        drawerState.close()
                                        vm.selectSession(s.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_drawer))
                        }
                    },
                )
            },
        ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OpenDroidChatPalette.chatAreaBackground),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = lines,
                            key = { item ->
                                when (item) {
                                    is ChatLine.User -> item.id
                                    is ChatLine.Assistant -> item.id
                                    is ChatLine.Tool -> item.id
                                    is ChatLine.System -> item.id
                                }
                            },
                        ) { item ->
                            when (item) {
                                is ChatLine.User -> UserMessageBubble(item.text)
                                is ChatLine.Assistant -> AgentMessageBubble(item.text, item.streaming)
                                is ChatLine.Tool -> {
                                    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                    val inputScroll = rememberScrollState()
                                    val resultScroll = rememberScrollState()
                                    val toolStatusWord = if (item.ok) {
                                        stringResource(R.string.status_success)
                                    } else {
                                        stringResource(R.string.status_failed)
                                    }
                                    val resultLegend = if (item.name == "capture_screenshot" && item.resultImages.isNotEmpty()) {
                                        stringResource(R.string.tool_screenshot_result_legend, item.resultTotalChars)
                                    } else {
                                        stringResource(R.string.result_chars, item.resultTotalChars)
                                    }
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                        ),
                                        border = BorderStroke(1.dp, OpenDroidChatPalette.userBubbleOutline),
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(
                                                text = stringResource(
                                                    R.string.tool_status_line,
                                                    item.name,
                                                    item.durationMs,
                                                    toolStatusWord,
                                                ),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = OpenDroidChatPalette.agentText,
                                            )
                                            Text(
                                                text = stringResource(R.string.tool_use_id_line, item.toolUseId),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                stringResource(R.string.parameters),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = OpenDroidChatPalette.agentText,
                                                modifier = Modifier.padding(top = 8.dp),
                                            )
                                            Text(
                                                text = item.inputSummary,
                                                style = mono,
                                                color = OpenDroidChatPalette.agentText,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 200.dp)
                                                    .verticalScroll(inputScroll),
                                            )
                                            Text(
                                                text = resultLegend,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = OpenDroidChatPalette.agentText,
                                                modifier = Modifier.padding(top = 8.dp),
                                            )
                                            Text(
                                                text = item.resultPreview,
                                                style = mono,
                                                color = OpenDroidChatPalette.agentText,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 360.dp)
                                                    .verticalScroll(resultScroll),
                                            )
                                            ToolResultImageThumbnails(item.resultImages)
                                        }
                                    }
                                }
                                is ChatLine.System -> Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp, horizontal = 24.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = input,
                    onValueChange = vm::updateInput,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    shape = composerFieldShape,
                    placeholder = { Text(stringResource(R.string.input_message_placeholder)) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    voiceRecording -> vm.stopVoiceRecordingAndTranscribe()
                                    busy || asrBusy -> Unit
                                    ContextCompat.checkSelfPermission(context, micPermission) !=
                                        PackageManager.PERMISSION_GRANTED ->
                                        micLauncher.launch(micPermission)
                                    else -> vm.startVoiceRecording()
                                }
                            },
                            enabled = (!busy && !asrBusy) || voiceRecording,
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.cd_voice_input)
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = if (voiceRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                )
                if (busy) {
                    Button(
                        onClick = { vm.stopAgent() },
                        shape = composerFieldShape,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
                            .semantics { contentDescription = context.getString(R.string.stop_current_task) },
                    ) {
                        Text(
                            text = "\u23F9",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        )
                    }
                } else {
                    Button(
                        onClick = vm::send,
                        enabled = input.isNotBlank(),
                        shape = composerFieldShape,
                    ) {
                        Text(stringResource(R.string.send))
                    }
                }
            }
        }
        }
    }
}
