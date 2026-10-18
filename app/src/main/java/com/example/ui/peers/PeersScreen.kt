package com.example.ui.peers

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.IdentityManager
import com.example.data.MessageEntity
import com.example.data.PeerEntity
import com.example.p2p.P2PNode
import com.example.ui.components.ContactAvatar
import com.example.ui.theme.Accent
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun startOfDay(timestamp: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = timestamp
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** "14:05" for today, "Yesterday", otherwise "3 Mar". */
private fun listTime(timestamp: Long): String {
    val days = Math.round((startOfDay(System.currentTimeMillis()) - startOfDay(timestamp)) / 86_400_000.0).toInt()
    return when {
        days <= 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        days == 1 -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
fun PeersScreen(
    p2pNode: P2PNode,
    onNavigateToChat: (String) -> Unit,
    onNavigateToTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val peers by p2pNode.database.peerDao().getAllPeersFlow().collectAsState(initial = emptyList())
    val recentMessages by p2pNode.database.messageDao().getRecentMessagesFlow()
        .collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var addressInput by remember { mutableStateOf("") }
    var selectedPeerForSafetyNumber by remember { mutableStateOf<PeerEntity?>(null) }

    // Last message and unread count per conversation (from the 100 most recent messages)
    val lastMessageByPeer = remember(recentMessages) {
        recentMessages.groupBy { it.conversationPeerId }.mapValues { entry -> entry.value.maxByOrNull { it.timestamp } }
    }
    val unreadByPeer = remember(recentMessages) {
        recentMessages.filter { !it.isOutgoing && !it.isRead }.groupingBy { it.conversationPeerId }.eachCount()
    }

    val visiblePeers = remember(peers, searchQuery, lastMessageByPeer) {
        peers
            .filter {
                searchQuery.isBlank() ||
                    it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.peerId.contains(searchQuery, ignoreCase = true)
            }
            .sortedByDescending { lastMessageByPeer[it.peerId]?.timestamp ?: it.lastSeen }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .testTag("peers_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.headlineMedium,
                color = TerminalTextPrimary
            )
            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.testTag("direct_connect_button")
            ) {
                Text("Add someone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search", fontSize = 15.sp, color = TerminalTextMuted) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = TerminalTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = TerminalBg,
                focusedContainerColor = TerminalBg,
                unfocusedBorderColor = TerminalBg,
                focusedBorderColor = Accent,
                focusedTextColor = TerminalTextPrimary,
                unfocusedTextColor = TerminalTextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("peer_search_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (visiblePeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (peers.isEmpty()) "No chats yet" else "No results",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (peers.isEmpty()) {
                            "People on the same Wi-Fi appear here automatically. " +
                                "To reach someone on another network, use the Home tab or tap Add someone."
                        } else {
                            "Nobody matches your search."
                        },
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        color = TerminalTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visiblePeers, key = { it.peerId }) { peer ->
                    ChatRow(
                        peer = peer,
                        lastMessage = lastMessageByPeer[peer.peerId],
                        unread = unreadByPeer[peer.peerId] ?: 0,
                        onOpen = { onNavigateToChat(peer.peerId) },
                        onSecurity = { selectedPeerForSafetyNumber = peer }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "Add someone",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TerminalTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter their address, for example 192.168.1.20 or 192.168.1.20:9091. " +
                            "You can also paste their ID.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = TerminalTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        placeholder = { Text("192.168.1.20", fontSize = 15.sp, color = TerminalTextMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = TerminalSurface,
                            focusedContainerColor = TerminalSurface,
                            unfocusedBorderColor = TerminalBorder,
                            focusedBorderColor = Accent,
                            focusedTextColor = TerminalTextPrimary,
                            unfocusedTextColor = TerminalTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("direct_address_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = addressInput.trim()
                        if (input.isNotEmpty()) {
                            if (input.startsWith("/") || input.contains(".") || input.contains(":")) {
                                p2pNode.connectDirectlyToAddress(input)
                            } else {
                                p2pNode.lookupAndConnect(input)
                            }
                        }
                        showAddDialog = false
                        addressInput = ""
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.testTag("dialog_confirm_connect")
                ) {
                    Text("Connect", fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", fontSize = 14.sp, color = TerminalTextSecondary)
                }
            },
            containerColor = TerminalBg,
            shape = RoundedCornerShape(20.dp)
        )
    }

    selectedPeerForSafetyNumber?.let { peer ->
        val ourPub = p2pNode.identityManager.ed25519PublicKey
        val theirPub = try {
            IdentityManager.hexToBytes(peer.publicKeyHex)
        } catch (_: Exception) {
            ByteArray(32)
        }
        val safetyNumber = IdentityManager.computeSafetyNumber(ourPub, theirPub)
        AlertDialog(
            onDismissRequest = { selectedPeerForSafetyNumber = null },
            title = {
                Text(
                    text = "Verify ${peer.displayName}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TerminalTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Compare this number with ${peer.displayName}, in person or on a call. " +
                            "If it is the same on both phones, nobody is listening in on your chat.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = TerminalTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TerminalSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = safetyNumber,
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            color = TerminalGreenBright,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        p2pNode.updatePeerVerification(peer.peerId, !peer.isVerified)
                        selectedPeerForSafetyNumber = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (peer.isVerified) TerminalAmber else Accent,
                        contentColor = if (peer.isVerified) Color(0xFF2E1900) else Color.White
                    ),
                    modifier = Modifier.testTag("toggle_verification_button")
                ) {
                    Text(
                        text = if (peer.isVerified) "Remove verification" else "It matches, mark as verified",
                        fontSize = 14.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPeerForSafetyNumber = null }) {
                    Text("Close", fontSize = 14.sp, color = TerminalTextSecondary)
                }
            },
            containerColor = TerminalBg,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun ChatRow(
    peer: PeerEntity,
    lastMessage: MessageEntity?,
    unread: Int,
    onOpen: () -> Unit,
    onSecurity: () -> Unit
) {
    val online = peer.connectionState == "CONNECTED"
    val preview = when {
        lastMessage == null -> if (online) "Online. Tap to start chatting" else "Tap to start chatting"
        lastMessage.isOutgoing -> "You: " + lastMessage.content.replace('\n', ' ')
        else -> lastMessage.content.replace('\n', ' ')
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            ContactAvatar(name = peer.displayName, seed = peer.peerId, size = 48.dp)
            if (online) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(TerminalBlack)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(TerminalGreenBright)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peer.displayName.ifBlank { "Unknown contact" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TerminalTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (peer.isVerified) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Verified",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalGreenBright
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preview,
                fontSize = 14.sp,
                fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (unread > 0) TerminalTextPrimary else TerminalTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            if (lastMessage != null) {
                Text(
                    text = listTime(lastMessage.timestamp),
                    fontSize = 12.sp,
                    color = if (unread > 0) Accent else TerminalTextMuted
                )
            }
            if (unread > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (unread > 99) "99+" else unread.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        IconButton(onClick = onSecurity, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Verify ${peer.displayName}",
                tint = if (peer.isVerified) TerminalGreenBright else TerminalTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
