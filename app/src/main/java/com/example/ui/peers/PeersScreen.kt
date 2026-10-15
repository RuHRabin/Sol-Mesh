package com.example.ui.peers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PeerEntity
import com.example.p2p.Multiaddress
import com.example.p2p.P2PNode
import com.example.p2p.PeerRecord
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextPrompt
import com.example.ui.theme.TerminalTextSecondary

@Composable
fun PeersScreen(
    p2pNode: P2PNode,
    onNavigateToChat: (String) -> Unit,
    onNavigateToTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val peers by p2pNode.database.peerDao().getAllPeersFlow().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showDirectConnectDialog by remember { mutableStateOf(false) }
    var directAddressInput by remember { mutableStateOf("") }
    var selectedPeerForSafetyNumber by remember { mutableStateOf<PeerEntity?>(null) }

    val filteredPeers = peers.filter {
        it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.peerId.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("peers_screen")
    ) {
        // Hacker Search & Dial Prompt
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "$ grep peer_id or alias...",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        color = TerminalTextMuted
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TerminalTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("peer_search_input"),
                singleLine = true,
                shape = RoundedCornerShape(2.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = TerminalBg,
                    focusedContainerColor = TerminalBg,
                    unfocusedBorderColor = TerminalBorder,
                    focusedBorderColor = TerminalGreenBright,
                    focusedTextColor = TerminalTextPrimary,
                    unfocusedTextColor = TerminalTextPrimary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { showDirectConnectDialog = true },
                shape = RoundedCornerShape(2.dp),
                border = BorderStroke(1.dp, TerminalBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalCyan),
                modifier = Modifier.testTag("direct_connect_button")
            ) {
                Text("$ dial", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NETWORK_PEER_TABLE",
                fontFamily = RobotoMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TerminalTextPrompt
            )
            Text(
                text = "TOTAL: ${filteredPeers.size}",
                fontFamily = RobotoMonoFontFamily,
                fontSize = 10.sp,
                color = TerminalTextSecondary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (filteredPeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "[!] ROUTING_TABLE_EMPTY",
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TerminalAmber
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Listening for local broadcasts on UDP:9090.\nUse '$ dial' above to connect directly.",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        color = TerminalTextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredPeers, key = { it.peerId }) { peer ->
                    TerminalPeerCard(
                        peer = peer,
                        onConnect = { p2pNode.connectToPeer(peer.peerId) },
                        onChat = { onNavigateToChat(peer.peerId) },
                        onViewSafetyNumber = { selectedPeerForSafetyNumber = peer }
                    )
                }
            }
        }
    }

    // Direct Dial Terminal Dialog
    if (showDirectConnectDialog) {
        AlertDialog(
            onDismissRequest = { showDirectConnectDialog = false },
            title = {
                Text(
                    text = "$ p2p-dial --multiaddr",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 13.sp,
                    color = TerminalTextPrompt
                )
            },
            text = {
                Column {
                    Text(
                        text = "Specify target multiaddress or 256-bit PeerID:",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        color = TerminalTextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = directAddressInput,
                        onValueChange = { directAddressInput = it },
                        placeholder = {
                            Text(
                                "/ip4/192.168.1.100/tcp/9091",
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 11.sp,
                                color = TerminalTextMuted
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("direct_address_input"),
                        shape = RoundedCornerShape(2.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = TerminalSurface,
                            focusedContainerColor = TerminalSurface,
                            unfocusedBorderColor = TerminalBorder,
                            focusedBorderColor = TerminalCyan
                        )
                    )
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        val input = directAddressInput.trim()
                        if (input.isNotEmpty()) {
                            if (input.startsWith("/") || input.contains(".") || input.contains(":")) {
                                p2pNode.connectDirectlyToAddress(input)
                            } else {
                                p2pNode.lookupAndConnect(input)
                            }
                        }
                        showDirectConnectDialog = false
                        directAddressInput = ""
                    },
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, TerminalGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalGreenBright),
                    modifier = Modifier.testTag("dialog_confirm_connect")
                ) {
                    Text("$ execute", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDirectConnectDialog = false }) {
                    Text("cancel", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp, color = TerminalTextMuted)
                }
            },
            containerColor = TerminalBg,
            shape = RoundedCornerShape(2.dp)
        )
    }

    // Safety Fingerprint Terminal Modal
    selectedPeerForSafetyNumber?.let { peer ->
        val ourPub = p2pNode.identityManager.ed25519PublicKey
        val theirPub = try {
            com.example.crypto.IdentityManager.hexToBytes(peer.publicKeyHex)
        } catch (_: Exception) {
            ByteArray(32)
        }
        val safetyNumber = com.example.crypto.IdentityManager.computeSafetyNumber(ourPub, theirPub)

        AlertDialog(
            onDismissRequest = { selectedPeerForSafetyNumber = null },
            title = {
                Text(
                    text = "MUTUAL_FINGERPRINT_VERIFY",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TerminalTextPrompt
                )
            },
            text = {
                Column {
                    Text(
                        text = "Target: ${peer.displayName} [${peer.peerId.take(12)}...]",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        color = TerminalTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = TerminalSurface,
                        border = BorderStroke(1.dp, TerminalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = safetyNumber,
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TerminalGreenBright,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 20.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Verify these numeric groups out-of-band to prevent MITM attacks.",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 10.sp,
                        color = TerminalTextMuted
                    )
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        val newVerified = !peer.isVerified
                        p2pNode.updatePeerVerification(peer.peerId, newVerified)
                        selectedPeerForSafetyNumber = null
                    },
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, if (peer.isVerified) TerminalAmber else TerminalGreen),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (peer.isVerified) TerminalAmber else TerminalGreenBright
                    ),
                    modifier = Modifier.testTag("toggle_verification_button")
                ) {
                    Text(
                        text = if (peer.isVerified) "$ unverify" else "$ verify_fingerprint",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPeerForSafetyNumber = null }) {
                    Text("close", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp, color = TerminalTextMuted)
                }
            },
            containerColor = TerminalBg,
            shape = RoundedCornerShape(2.dp)
        )
    }
}

@Composable
fun TerminalPeerCard(
    peer: PeerEntity,
    onConnect: () -> Unit,
    onChat: () -> Unit,
    onViewSafetyNumber: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("peer_card_${peer.peerId}"),
        shape = RoundedCornerShape(2.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        border = BorderStroke(1.dp, TerminalBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[PID] ${peer.displayName}",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = TerminalTextPrimary
                )
                if (peer.isVerified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "[VERIFIED]",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = TerminalGreenBright
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onViewSafetyNumber, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Safety",
                        tint = if (peer.isVerified) TerminalGreenBright else TerminalTextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                text = "ID: ${peer.peerId}",
                fontFamily = RobotoMonoFontFamily,
                fontSize = 10.sp,
                color = TerminalTextMuted,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STATUS: ${peer.connectionState}",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (peer.connectionState) {
                        "CONNECTED" -> TerminalGreenBright
                        "CONNECTING", "AUTHENTICATING" -> TerminalAmber
                        "FAILED" -> TerminalRed
                        else -> TerminalTextMuted
                    }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (peer.connectionState != "CONNECTED") {
                        OutlinedButton(
                            onClick = onConnect,
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, TerminalBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalCyan),
                            modifier = Modifier.testTag("connect_btn_${peer.peerId}")
                        ) {
                            Text("$ connect", fontFamily = RobotoMonoFontFamily, fontSize = 10.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = onChat,
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, TerminalGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalGreenBright),
                        modifier = Modifier.testTag("chat_btn_${peer.peerId}")
                    ) {
                        Text("$ chat", fontFamily = RobotoMonoFontFamily, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
