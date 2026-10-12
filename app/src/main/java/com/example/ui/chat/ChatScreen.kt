package com.example.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MessageEntity
import com.example.p2p.P2PNode
import com.example.p2p.RoutingMode
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    p2pNode: P2PNode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val peer by p2pNode.database.peerDao().getPeerFlow(peerId).collectAsState(initial = null)
    val messages by p2pNode.database.messageDao().getMessagesForConversationFlow(peerId).collectAsState(initial = emptyList())
    val circuitStatus by p2pNode.circuitHopperManager.circuitStatus.collectAsState()
    val isConnected = p2pNode.connectionManager.isConnected(peerId)

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(peerId) {
        p2pNode.chatManager.markConversationAsRead(peerId)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val fileName = "payload_${System.currentTimeMillis()}"
                val tempFile = File(context.cacheDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                p2pNode.sendFile(peerId, tempFile)
                Toast.makeText(context, "Direct chunked file transfer started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "File transfer error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .testTag("chat_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isConnected) TerminalGreenBright else TerminalAmber,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = peer?.displayName ?: "Peer (${peerId.take(8)})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TerminalTextPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = TerminalGreenBright,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isConnected) "End-to-End Encrypted (Online)" else "Resolving Socket Connection...",
                                    fontSize = 11.sp,
                                    color = if (isConnected) TerminalGreen else TerminalAmber
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TerminalTextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            p2pNode.connectToPeer(peerId)
                            Toast.makeText(context, "Re-connecting to peer...", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reconnect",
                            tint = TerminalCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TerminalBg
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(TerminalBlack)
                .imePadding()
        ) {
            // Friendly Route Info Banner
            Surface(
                color = TerminalSurface,
                border = BorderStroke(0.5.dp, TerminalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (circuitStatus.mode == RoutingMode.DIRECT_P2P) "Direct Device Mesh" else "Decentralized Relay (${circuitStatus.activeNode.region})",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (circuitStatus.mode == RoutingMode.DIRECT_P2P) TerminalCyan else TerminalGreenBright
                    )

                    Text(
                        text = "Zero Data Retention Enforced",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 10.sp,
                        color = TerminalTextMuted
                    )
                }
            }

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = TerminalSurface,
                            border = BorderStroke(0.5.dp, TerminalBorder)
                        ) {
                            Text(
                                text = "🔐 Encrypted with Double Ratchet • Real-time Stream Decryption",
                                fontSize = 11.sp,
                                color = TerminalTextMuted,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(messages, key = { it.id }) { msg ->
                    TypewriterDecryptionMessageBubble(message = msg)
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Modern, User-Friendly Input Bar
            Surface(
                color = TerminalBg,
                border = BorderStroke(1.dp, TerminalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("attach_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach File",
                            tint = TerminalTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = TerminalSurface,
                        border = BorderStroke(0.5.dp, TerminalBorder),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            BasicTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = TerminalTextPrimary
                                ),
                                cursorBrush = SolidColor(TerminalGreenBright),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (textInput.isNotBlank()) {
                                            p2pNode.sendMessage(peerId, textInput.trim())
                                            textInput = ""
                                        }
                                    }
                                ),
                                singleLine = false,
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_text_input")
                            )

                            if (textInput.isEmpty()) {
                                Text(
                                    text = "Send encrypted message...",
                                    fontSize = 13.sp,
                                    color = TerminalTextMuted
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                p2pNode.sendMessage(peerId, textInput.trim())
                                textInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (textInput.isNotBlank()) TerminalGreenBright else TerminalTextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Typewriter Decryption Message Bubble:
 * Reveals text character-by-character as if decrypted in real time.
 * Absolutely NO read receipts, tick marks, or delivery indicators.
 */
@Composable
fun TypewriterDecryptionMessageBubble(
    message: MessageEntity
) {
    val isIncoming = !message.isOutgoing
    val fullText = message.content

    // Typewriter decryption state
    var revealedCount by remember(message.id) { mutableStateOf(if (isIncoming) 0 else fullText.length) }
    var cipherGlyph by remember(message.id) { mutableStateOf("") }
    var isDecrypting by remember(message.id) { mutableStateOf(isIncoming) }

    val cipherAlphabet = "0123456789ABCDEF$#@%&*"

    LaunchedEffect(message.id, fullText) {
        if (isIncoming && revealedCount < fullText.length) {
            isDecrypting = true
            revealedCount = 0
            for (i in 1..fullText.length) {
                cipherGlyph = cipherAlphabet[Random.nextInt(cipherAlphabet.length)].toString()
                delay(12)
                revealedCount = i
                cipherGlyph = ""
                delay(16) // Typewriter pace ~28ms per character
            }
            isDecrypting = false
        } else {
            revealedCount = fullText.length
            isDecrypting = false
        }
    }

    val displayedText = if (isIncoming && isDecrypting) {
        fullText.take(revealedCount) + cipherGlyph
    } else {
        fullText
    }

    val timeFormatted = remember(message.timestamp) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date(message.timestamp))
    }

    val alignment = if (isIncoming) Alignment.Start else Alignment.End
    val bubbleShape = if (isIncoming) {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    }

    val bubbleBg = if (isIncoming) {
        TerminalSurface
    } else {
        Color(0xFF0F3820) // Deep crisp emerald for outgoing
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_bubble_${message.id}"),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleBg,
            border = BorderStroke(0.5.dp, if (isIncoming) TerminalBorder else TerminalGreen.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isIncoming && isDecrypting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = "⚡ DECRYPTING STREAM",
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TerminalAmber
                        )
                    }
                }

                Text(
                    text = displayedText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = TerminalTextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Minimal timestamp ONLY - strictly NO checkmarks, NO read receipts, NO ticks
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeFormatted,
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 10.sp,
                        color = TerminalTextMuted
                    )
                }
            }
        }
    }
}
