package com.example.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MessageEntity
import com.example.data.TransferEntity
import com.example.p2p.P2PNode
import com.example.service.AppState
import com.example.ui.components.ContactAvatar
import com.example.ui.components.openFile
import com.example.ui.components.rememberFileSaver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val outgoing: Boolean

    data class DateHeader(val label: String, val dayStart: Long) : ChatListItem {
        override val key: String get() = "date_$dayStart"
        override val outgoing: Boolean get() = false
    }

    data class Bubble(
        val message: MessageEntity,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean
    ) : ChatListItem {
        override val key: String get() = message.id
        override val outgoing: Boolean get() = message.isOutgoing
    }

    data class FileBubble(
        val transfer: TransferEntity,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean
    ) : ChatListItem {
        override val key: String get() = "file_" + transfer.id
        override val outgoing: Boolean get() = transfer.isOutgoing
    }
}

/** A message or a file, placed on one timeline. */
private class ChatEvent(
    val timestamp: Long,
    val outgoing: Boolean,
    val message: MessageEntity?,
    val transfer: TransferEntity?
)

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

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
}

/** Adds date headers and marks the first / last item of each run from the same side. */
private fun buildChatItems(messages: List<MessageEntity>, transfers: List<TransferEntity>): List<ChatListItem> {
    val events = ArrayList<ChatEvent>(messages.size + transfers.size)
    for (m in messages) events.add(ChatEvent(m.timestamp, m.isOutgoing, m, null))
    for (t in transfers) events.add(ChatEvent(t.createdAt, t.isOutgoing, null, t))
    events.sortBy { it.timestamp }

    val result = ArrayList<ChatListItem>(events.size + 8)
    var lastDay = Long.MIN_VALUE
    for ((index, e) in events.withIndex()) {
        val day = dayStartOf(e.timestamp)
        if (day != lastDay) {
            result.add(ChatListItem.DateHeader(dayLabel(day), day))
            lastDay = day
        }
        val prev = events.getOrNull(index - 1)
        val next = events.getOrNull(index + 1)
        val joinsPrev = prev != null && prev.outgoing == e.outgoing &&
            dayStartOf(prev.timestamp) == day && e.timestamp - prev.timestamp < GROUP_GAP_MS
        val joinsNext = next != null && next.outgoing == e.outgoing &&
            dayStartOf(next.timestamp) == day && next.timestamp - e.timestamp < GROUP_GAP_MS
        if (e.message != null) {
            result.add(ChatListItem.Bubble(e.message, firstInGroup = !joinsPrev, lastInGroup = !joinsNext))
        } else if (e.transfer != null) {
            result.add(ChatListItem.FileBubble(e.transfer, firstInGroup = !joinsPrev, lastInGroup = !joinsNext))
        }
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
    val transfers by p2pNode.database.transferDao().getTransfersForPeerFlow(peerId)
        .collectAsState(initial = emptyList())
    // Messages that already played the decrypt effect (survives scrolling, resets when the chat closes)
    val animatedIds = remember { mutableSetOf<String>() }
    val saveFile = rememberFileSaver()

    val isOnline = peer?.connectionState == "CONNECTED" || p2pNode.connectionManager.isConnected(peerId)
    val contactName = peer?.displayName ?: "Unknown contact"

    // Bubbles take at most 78% of the screen width, like other messengers
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val chatItems = remember(messages, transfers) { buildChatItems(messages, transfers) }
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

    // Keep new items in view, but do not yank the list while the user reads older ones
    val itemCount = chatItems.size
    LaunchedEffect(itemCount) {
        if (itemCount == 0) return@LaunchedEffect
        if (!didInitialScroll) {
            listState.scrollToItem(itemCount)
            didInitialScroll = true
            return@LaunchedEffect
        }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= itemCount - 2
        if (nearBottom || chatItems.last().outgoing) {
            listState.animateScrollToItem(itemCount)
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
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TerminalTextPrimary
                    )
                }
                ContactAvatar(name = contactName, seed = peerId, size = 36.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) TerminalGreenBright else TerminalAmber)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isOnline) "Online" else "Offline",
                            fontSize = 12.sp,
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
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = TerminalAmber,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }

        // Messages and files
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(TerminalSurface)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = TerminalTextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "End-to-end encrypted. Only you and $contactName can read these messages.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = TerminalTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 260.dp)
                        )
                    }
                    if (chatItems.isEmpty()) {
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            text = "No messages yet",
                            fontSize = 16.sp,
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
                            maxWidth = maxBubbleWidth,
                            animate = recentlyReceived && msg.id !in animatedIds,
                            onAnimated = { animatedIds.add(msg.id) },
                            onRetry = { id ->
                                p2pNode.retryMessage(id)
                                if (!isOnline) p2pNode.connectToPeer(peerId)
                                Toast.makeText(context, "Trying to send again...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    is ChatListItem.FileBubble -> {
                        val t = item.transfer
                        FileBubble(
                            transfer = t,
                            firstInGroup = item.firstInGroup,
                            lastInGroup = item.lastInGroup,
                            maxWidth = maxBubbleWidth,
                            onOpen = { openFile(context, t.localFilePath, t.mimeType) },
                            onSave = { saveFile(t.localFilePath, t.fileName) },
                            onResume = {
                                p2pNode.fileTransferManager.resumeTransfer(t.fileId)
                                if (!isOnline) p2pNode.connectToPeer(peerId)
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // Input bar
        val canSend = textInput.isNotBlank()
        Surface(color = TerminalBg, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("attach_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = TerminalTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(TerminalSurface)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = TerminalTextPrimary),
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
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            color = TerminalTextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .size(40.dp)
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
                        modifier = Modifier.size(20.dp)
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
            .padding(vertical = 10.dp),
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
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

private fun bubbleShape(outgoing: Boolean, firstInGroup: Boolean): RoundedCornerShape {
    val big = 16.dp
    val small = 4.dp
    return if (outgoing) {
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
}

/**
 * One text message. Long-press selects text (and offers copy). Incoming messages that just arrived play a
 * short decrypt effect (purely visual: the message was already decrypted on arrival). A message that could
 * not be sent says so and can be tapped to try again. No read receipts or ticks are ever shown.
 */
@Composable
private fun MessageBubble(
    message: MessageEntity,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    maxWidth: Dp,
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

    // Decrypt effect (incoming only). The text keeps its length, so the bubble never changes size.
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

    val bubbleColor = if (outgoing) AccentStrong else TerminalSurfaceElevated
    val textColor = if (outgoing) Color.White else TerminalTextPrimary
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.7f) else TerminalTextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (firstInGroup) 8.dp else 2.dp)
            .testTag("message_bubble_${message.id}"),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(bubbleShape(outgoing, firstInGroup))
                .background(bubbleColor)
                .then(
                    if (sendState == SendState.FAILED) Modifier.clickable { onRetry(message.id) } else Modifier
                )
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 5.dp)
        ) {
            SelectionContainer {
                Text(
                    text = displayedText,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    color = textColor
                )
            }

            if (lastInGroup || sendState != SendState.OK) {
                Text(
                    text = when (sendState) {
                        SendState.FAILED -> "Not sent. Tap to try again"
                        SendState.SENDING -> "Sending..."
                        SendState.OK -> timeText
                    },
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (sendState == SendState.FAILED) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sendState == SendState.FAILED) FailedOnAccent else metaColor,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 1.dp)
                )
            }
        }
    }
}

/** Reads a small preview of an image file. Returns null if it is not a readable image. */
private fun decodeThumbnail(path: String, targetPx: Int = 480): ImageBitmap? {
    return try {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/**
 * A file in the conversation, for both people. While it moves it shows progress; when it is done the
 * sender sees "Sent successfully" and the receiver sees "Received". Either side can open it, and the
 * receiver can save a copy. Images show a preview.
 */
@Composable
private fun FileBubble(
    transfer: TransferEntity,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    maxWidth: Dp,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onResume: () -> Unit
) {
    val outgoing = transfer.isOutgoing
    val completed = transfer.status == "COMPLETED"
    val inProgress = transfer.status == "TRANSFERRING" || transfer.status == "PENDING" || transfer.status == "ACCEPTED"
    val paused = transfer.status == "PAUSED"
    val failed = transfer.status == "FAILED"
    val cancelled = transfer.status == "CANCELLED"

    val progress = if (transfer.chunksTotal > 0) {
        (transfer.chunksTransferred.toFloat() / transfer.chunksTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val percent = (progress * 100).toInt()

    val isImage = transfer.mimeType.startsWith("image/")
    // Our own outgoing copy exists from the start; a received file only once it is complete
    val previewReady = isImage && (completed || outgoing)
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, transfer.localFilePath, previewReady) {
        value = if (previewReady) withContext(Dispatchers.IO) { decodeThumbnail(transfer.localFilePath) } else null
    }

    val statusLabel = when {
        completed && outgoing -> "\u2713 Sent successfully"
        completed -> "Received"
        inProgress -> (if (outgoing) "Sending " else "Receiving ") + percent + "%"
        paused -> "Paused. Tap to resume"
        failed -> "Failed. Tap to try again"
        cancelled -> "Cancelled"
        else -> transfer.status.lowercase()
    }

    val bubbleColor = if (outgoing) AccentStrong else TerminalSurfaceElevated
    val textColor = if (outgoing) Color.White else TerminalTextPrimary
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.75f) else TerminalTextSecondary
    val statusColor = when {
        failed -> if (outgoing) FailedOnAccent else Color(0xFFFF8A80)
        completed && outgoing -> Color.White
        completed -> TerminalGreenBright
        paused -> if (outgoing) Color.White else TerminalAmber
        else -> metaColor
    }

    val timeText = remember(transfer.createdAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(transfer.createdAt))
    }

    val tapAction: (() -> Unit)? = when {
        completed -> onOpen
        paused || failed -> onResume
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (firstInGroup) 8.dp else 2.dp)
            .testTag("file_bubble_${transfer.fileId}"),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 210.dp, max = maxWidth)
                .clip(bubbleShape(outgoing, firstInGroup))
                .background(bubbleColor)
                .then(if (tapAction != null) Modifier.clickable { tapAction() } else Modifier)
                .padding(6.dp)
        ) {
            val preview = thumbnail
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = transfer.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (preview == null) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = transfer.fileName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSize(transfer.fileSizeBytes),
                        fontSize = 12.sp,
                        color = metaColor
                    )
                }
            }

            if (inProgress || paused) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .height(4.dp),
                    color = if (outgoing) Color.White else Accent,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
                if (lastInGroup) {
                    Text(
                        text = timeText,
                        fontSize = 11.sp,
                        color = metaColor,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            if (completed) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        text = "Open",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (outgoing) Color.White else Accent,
                        modifier = Modifier
                            .clickable { onOpen() }
                            .padding(vertical = 5.dp)
                            .testTag("file_open_${transfer.fileId}")
                    )
                    if (!outgoing) {
                        Text(
                            text = "Save",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Accent,
                            modifier = Modifier
                                .clickable { onSave() }
                                .padding(vertical = 5.dp)
                                .testTag("file_save_${transfer.fileId}")
                        )
                    }
                }
            }
        }
    }
}
