package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PeerEntity
import com.example.p2p.P2PNode
import com.example.ui.theme.Accent
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary

private fun shortId(peerId: String): String = if (peerId.length > 12) peerId.take(12) + "..." else peerId

private fun nameOf(peers: List<PeerEntity>, peerId: String): String =
    peers.firstOrNull { it.peerId == peerId }?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"

/**
 * Shows a dialog whenever an unknown person connects and waits for an answer, on any screen.
 * Dismissing the dialog ("Later") keeps the request visible in the Chats tab.
 */
@Composable
fun ContactRequestHost(p2pNode: P2PNode) {
    val pendingIds by p2pNode.contactPolicy.pending.collectAsState()
    val peers by p2pNode.database.peerDao().getAllPeersFlow().collectAsState(initial = emptyList())
    var postponed by remember { mutableStateOf(setOf<String>()) }

    val currentId = pendingIds.firstOrNull { it !in postponed } ?: return
    val name = nameOf(peers, currentId)

    AlertDialog(
        onDismissRequest = { postponed = postponed + currentId },
        title = {
            Text(
                text = "Connection request",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TerminalTextPrimary
            )
        },
        text = {
            Column {
                Text(
                    text = "$name wants to connect with you.",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TerminalTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Until you accept, they cannot send you files and their messages stay hidden. " +
                        "Anyone can choose any name, so only accept people you know.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = TerminalTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "ID: " + shortId(currentId),
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 12.sp,
                    color = TerminalTextMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { p2pNode.blockContact(currentId) },
                    modifier = Modifier.testTag("request_block_button")
                ) {
                    Text("Block", fontSize = 14.sp, color = TerminalRed)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { p2pNode.acceptContact(currentId) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.testTag("request_accept_button")
            ) {
                Text("Accept", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { p2pNode.declineContact(currentId) },
                modifier = Modifier.testTag("request_decline_button")
            ) {
                Text("Decline", fontSize = 14.sp, color = TerminalTextSecondary)
            }
        },
        containerColor = TerminalBg,
        shape = RoundedCornerShape(20.dp)
    )
}

/** One request, as a card with Accept / Decline, for the top of the Chats list. */
@Composable
fun ContactRequestCard(
    name: String,
    peerId: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("request_card_$peerId"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        border = BorderStroke(1.dp, TerminalAmber.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(name = name, seed = peerId, size = 40.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "wants to connect with you",
                        fontSize = 13.sp,
                        color = TerminalAmber
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decline", fontSize = 14.sp, color = TerminalTextSecondary)
                }
                Button(
                    onClick = onAccept,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Accept", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
