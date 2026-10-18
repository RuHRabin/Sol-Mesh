package com.example.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MessageEntity
import com.example.p2p.P2PNode
import com.example.service.AppState
import com.example.ui.components.ContactAvatar
import com.example.ui.theme.Accent
import com.example.ui.theme.AccentStrong
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalSurfaceElevated
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

private const val GROUP_GAP_MS = 3 * 60 * 1000L
private const val STUCK_SENDING_MS = 20_000L

private val FailedOnAccent = Color(0xFFFFC9C4)

private enum class SendState { OK, SENDING, FAILED }

private sealed interface ChatListItem {
    val key: String

    data class DateHeader(val label: String, val dayStart: Long) : ChatListItem {
        override val key: String get() = "date_$dayStart"
    }

    data class Bubble(
        val message: MessageEntity,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean
    ) : ChatListItem {
        override val key: String get() = message.id
    }
}

/** The file name the user sees in the picker, without any path. */
private fun displayNameOf(context: Context, uri: Uri): String {
    var name: String? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
    } catch (_: Exception) {
    }
    val clean = name?.let { File(it).name }?.trim()
    return if (clean.isNullOrEmpty()) "file_${System.currentTimeMillis()}" else clean
}

private fun dayStartOf(timestamp: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = timestamp
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun dayLabel(dayStart: Long): String {
    val today = dayStartOf(System.currentTimeMillis())
    val days = Math.round((today - dayStart) / 86_400_000.0).toInt()
    return when (days) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(dayStart))
    }
}

/** Adds date headers and marks the first / last message of each run of messages from the same side. */
private fun buildChatItems(messages: List<MessageEntity>): List<ChatListItem> {
    val result = ArrayList<ChatListItem>(messages.size + 8)
    var lastDay = Long.MIN_VALUE
    for ((index, m) in messages.withIndex()) {
        val day = dayStartOf(m.timestamp)
        if (day != lastDay) {
            result.add(ChatListItem.DateHeader(dayLabel(day), day))
            lastDay = day
        }
        val prev = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val joinsPrev = prev != null && prev.isOutgoing == m.isOutgoing &&
            dayStartOf(prev.timestamp) == day && m.timestamp - prev.timestamp < GROUP_GAP_MS
        val joinsNext = next != null && next.isOutgoing == m.isOutgoing &&
            dayStartOf(next.timestamp) == day && next.timestamp - m.timestamp < GROUP_GAP_MS
        result.add(ChatListItem.Bubble(m, firstInGroup = !joinsPrev, lastInGroup = !joinsNext))
    }
    return result
}

@Composable
fun ChatScreen(
    peerId: String,
    p2pNode: P2PNode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val peer by p2pNode.database.peerDao().getPeerFlow(peerId).collectAsState(initial = null)
    val messages by p2pNode.database.messageDao().getMessagesForConversationFlow(peerId)
        .collectAsState(initial = emptyList())
    // Messages that already played the decrypt effect (survives scrolling, resets when the chat closes)
    val animatedIds = remember { mutableSetOf<String>() }

    val isOnline = peer?.connectionState == "CONNECTED" || p2pNode.connectionManager.isConnected(peerId)
    val contactName = peer?.displayName ?: "Unknown contact"

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val chatItems = remember(messages) { buildChatItems(messages) }
    var didInitialScroll by remember { mutableStateOf(false) }

    // Tell the app which chat is open, so no notification is shown for it
    DisposableEffect(peerId) {
        AppState.openChatPeerId = peerId
        onDispose {
            if (AppState.openChatPeerId == peerId) AppState.openChatPeerId = null
        }
    }

    // Open the chat connected whenever possible
    LaunchedEffect(peerId) {
        if (!p2pNode.connectionManager.isConnected(peerId)) {
            p2pNode.connectToPeer(peerId)
        }
        p2pNode.chatManager.markConversationAsRead(peerId)
    }

    // Keep new messages in view, but do not yank the list while the user reads older ones
    val lastItemCount = chatItems.size
    LaunchedEffect(lastItemCount) {
        if (lastItemCount == 0) return@LaunchedEffect
        if (!didInitialScroll) {
            listState.scrollToItem(lastItemCount)
            didInitialScroll = true
            return@LaunchedEffect
        }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= lastItemCount - 2
        val lastIsMine = (chatItems.last() as? ChatListItem.Bubble)?.message?.isOutgoing == true
        if (nearBottom || lastIsMine) {
            listState.animateScrollToItem(lastItemCount)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Keep the real file name (and type) so the other person receives "photo.jpg", not a random name
                val originalName = displayNameOf(context, uri)
                val folder = File(context.cacheDir, "outgoing_${System.currentTimeMillis()}").also { it.mkdirs() }
                val tempFile = File(folder, originalName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                p2pNode.sendFile(peerId, tempFile, mimeType)
                Toast.makeText(context, "Sending $originalName...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not send file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val sendNow = {
        val text = textInput.trim()
        if (text.isNotEmpty()) {
            p2pNode.sendMessage(peerId, text)
            textInput = ""
            if (!isOnline) p2pNode.connectToPeer(peerId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .imePadding()
            .testTag("chat_screen")
    ) {
        // Header
        Surface(color = TerminalBg, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TerminalTextPrimary
                    )
                }
                ContactAvatar(name = contactName, seed = peerId, size = 40.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) TerminalGreenBright else TerminalAmber)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOnline) "Online" else "Offline",
                            fontSize = 13.sp,
                            color = if (isOnline) TerminalGreenBright else TerminalAmber
                        )
                    }
                }
                if (!isOnline) {
                    IconButton(
                        onClick = {
                            p2pNode.connectToPeer(peerId)
                            Toast.makeText(context, "Trying to reconnect...", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reconnect",
                            tint = Accent
                        )
                    }
                }
            }
        }

        if (!isOnline) {
            Surface(color = TerminalAmber.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$contactName is offline. Messages you write now will be sent when you are connected again.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = TerminalAmber,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(TerminalSurface)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = TerminalTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "End-to-end encrypted. Only you and $contactName can read these messages.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = TerminalTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 260.dp)
                        )
                    }
                    if (messages.isEmpty()) {
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            text = "No messages yet",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TerminalTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Say hello to $contactName.",
                            fontSize = 14.sp,
                            color = TerminalTextSecondary
                        )
                    }
                }
            }

            items(chatItems, key = { it.key }) { item ->
                when (item) {
                    is ChatListItem.DateHeader -> DateHeader(item.label)
                    is ChatListItem.Bubble -> {
                        val msg = item.message
                        // Only freshly received messages get the decrypt effect, and only once
                        val recentlyReceived = !msg.isOutgoing &&
                            abs(System.currentTimeMillis() - msg.timestamp) < 30_000
                        MessageBubble(
                            message = msg,
                            firstInGroup = item.firstInGroup,
                            lastInGroup = item.lastInGroup,
                            animate = recentlyReceived && msg.id !in animatedIds,
                            onAnimated = { animatedIds.add(msg.id) },
                            onRetry = { id ->
                                p2pNode.retryMessage(id)
                                if (!isOnline) p2pNode.connectToPeer(peerId)
                                Toast.makeText(context, "Trying to send again...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(10.dp)) }
        }

        // Input bar
        val canSend = textInput.isNotBlank()
        Surface(color = TerminalBg, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("attach_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = TerminalTextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(TerminalSurface)
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        textStyle = TextStyle(fontSize = 16.sp, color = TerminalTextPrimary),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_text_input")
                    )
                    if (textInput.isEmpty()) {
                        Text(
                            text = "Message",
                            fontSize = 16.sp,
                            color = TerminalTextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSend) Accent else TerminalSurface)
                        .clickable(enabled = canSend) { sendNow() }
                        .testTag("send_message_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color.White else TerminalTextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TerminalTextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(TerminalSurface)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * One message. Long-press selects text (and offers copy). Incoming messages that just arrived play a short
 * decrypt effect (purely visual: the message was already decrypted on arrival). A message that could not be
 * sent says so and can be tapped to try again. No read receipts or ticks are ever shown.
 */
@Composable
private fun MessageBubble(
    message: MessageEntity,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    animate: Boolean,
    onAnimated: () -> Unit,
    onRetry: (String) -> Unit
) {
    val outgoing = message.isOutgoing
    val fullText = message.content

    val ageMs = System.currentTimeMillis() - message.timestamp
    val sendState = when {
        !outgoing -> SendState.OK
        message.deliveryStatus == "FAILED" -> SendState.FAILED
        message.deliveryStatus == "SENDING" && ageMs > STUCK_SENDING_MS -> SendState.FAILED
        message.deliveryStatus == "SENDING" -> SendState.SENDING
        else -> SendState.OK
    }

    // Decrypt effect (incoming only)
    var isDecrypting by remember(message.id) { mutableStateOf(animate && fullText.isNotEmpty()) }
    var revealedCount by remember(message.id) { mutableStateOf(if (isDecrypting) 0 else fullText.length) }
    var scrambleTick by remember(message.id) { mutableStateOf(0) }
    val cipherAlphabet = "0123456789ABCDEF$#@%&*"

    LaunchedEffect(message.id) {
        if (isDecrypting) {
            onAnimated() // remember that this message was animated so scrolling never replays it
            val frames = fullText.length.coerceIn(10, 30)
            val step = (fullText.length + frames - 1) / frames
            var shown = 0
            while (shown < fullText.length) {
                delay(40)
                shown = minOf(shown + step, fullText.length)
                revealedCount = shown
                scrambleTick++
            }
            isDecrypting = false
        }
    }

    val displayedText = if (isDecrypting && scrambleTick >= 0) {
        buildString {
            for (i in fullText.indices) {
                val c = fullText[i]
                append(if (i < revealedCount || c.isWhitespace()) c else cipherAlphabet[Random.nextInt(cipherAlphabet.length)])
            }
        }
    } else {
        fullText
    }

    val timeText = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    val big = 18.dp
    val small = 5.dp
    val shape = if (outgoing) {
        RoundedCornerShape(
            topStart = big,
            topEnd = if (firstInGroup) big else small,
            bottomEnd = small,
            bottomStart = big
        )
    } else {
        RoundedCornerShape(
            topStart = if (firstInGroup) big else small,
            topEnd = big,
            bottomEnd = big,
            bottomStart = small
        )
    }

    val bubbleColor = if (outgoing) AccentStrong else TerminalSurfaceElevated
    val textColor = if (outgoing) Color.White else TerminalTextPrimary
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.7f) else TerminalTextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (firstInGroup) 10.dp else 2.dp)
            .testTag("message_bubble_${message.id}"),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                .then(
                    if (sendState == SendState.FAILED) Modifier.clickable { onRetry(message.id) } else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            if (!outgoing && isDecrypting) {
                Text(
                    text = "Decrypting...",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Accent,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }

            SelectionContainer {
                Text(
                    text = displayedText,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = textColor
                )
            }

            if (lastInGroup || sendState != SendState.OK) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (sendState) {
                        SendState.FAILED -> Text(
                            text = "Not sent. Tap to try again",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FailedOnAccent
                        )
                        SendState.SENDING -> Text(
                            text = "Sending...",
                            fontSize = 12.sp,
                            color = metaColor
                        )
                        SendState.OK -> Text(
                            text = timeText,
                            fontSize = 12.sp,
                            color = metaColor
                        )
                    }
                }
            }
        }
    }
}
