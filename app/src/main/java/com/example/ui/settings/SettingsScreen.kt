package com.example.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.p2p.P2PNode
import com.example.service.P2PNodeService
import com.example.ui.theme.Accent
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorderSubtle
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier,
    onOpenIdentity: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenTerminal: () -> Unit = {}
) {
    val context = LocalContext.current
    val status by p2pNode.nodeStatus.collectAsState()
    val blockedIds by p2pNode.contactPolicy.blocked.collectAsState()
    var backgroundServiceEnabled by remember { mutableStateOf(true) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = TerminalTextPrimary
        )

        // You
        SectionLabel("You")
        SettingsCard {
            SettingsRow(
                title = status.displayName.ifBlank { "Your device" },
                subtitle = "Your name and encryption keys. Tap to view your safety number.",
                onClick = onOpenIdentity
            )
        }

        // Connection
        SectionLabel("Connection")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stay reachable in the background",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TerminalTextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Keeps Sol Mesh running when the app is closed so friends can still reach you. Shows a notification.",
                        fontSize = 13.sp,
                        color = TerminalTextSecondary
                    )
                }
                Switch(
                    checked = backgroundServiceEnabled,
                    onCheckedChange = {
                        backgroundServiceEnabled = it
                        if (it) P2PNodeService.startService(context) else P2PNodeService.stopService(context)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Accent
                    ),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .testTag("background_service_switch")
                )
            }
        }

        // Privacy & data
        SectionLabel("Privacy and data")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "End-to-end encrypted",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TerminalTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Only you and the person you are talking to can read messages and files. " +
                        "Chats are stored on this phone only, not on any server.",
                    fontSize = 13.sp,
                    color = TerminalTextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TerminalRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalRed),
                    modifier = Modifier.testTag("clear_data_button")
                ) {
                    Text("Delete all chats and contacts", fontSize = 14.sp)
                }
            }
        }

        // Blocked people (only shown when there are any)
        if (blockedIds.isNotEmpty()) {
            SectionLabel("Blocked")
            SettingsCard {
                Column {
                    blockedIds.forEach { blockedId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ID " + (if (blockedId.length > 14) blockedId.take(14) + "..." else blockedId),
                                fontSize = 14.sp,
                                color = TerminalTextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { p2pNode.unblockContact(blockedId) }) {
                                Text("Unblock", fontSize = 14.sp, color = Accent)
                            }
                        }
                    }
                }
            }
        }

        // Advanced
        SectionLabel("Advanced")
        SettingsCard {
            Column {
                SettingsRow(
                    title = "Network details",
                    subtitle = "Ports, addresses and discovery status",
                    onClick = onOpenNetwork
                )
                SettingsRow(
                    title = "Terminal",
                    subtitle = "Text commands, for developers",
                    onClick = onOpenTerminal
                )
            }
        }

        // About
        SectionLabel("About")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sol Mesh 1.0",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TerminalTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Peer-to-peer messaging and file transfer.\n" +
                        "Encryption: X25519 and Ed25519 keys, Double Ratchet, AES-256-GCM.",
                    fontSize = 13.sp,
                    color = TerminalTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = {
                Text(
                    text = "Delete everything?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TerminalTextPrimary
                )
            },
            text = {
                Text(
                    text = "This removes all messages, contacts and file transfer history from this phone. It cannot be undone.",
                    fontSize = 14.sp,
                    color = TerminalTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            p2pNode.database.clearAllTables()
                        }
                        Toast.makeText(context, "All chats and contacts deleted", Toast.LENGTH_SHORT).show()
                        showClearDataDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TerminalRed)
                ) {
                    Text("Delete", fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel", fontSize = 14.sp, color = TerminalTextSecondary)
                }
            },
            containerColor = TerminalBg,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Accent,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        border = BorderStroke(1.dp, TerminalBorderSubtle)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TerminalTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = TerminalTextSecondary
            )
        }
        Text(
            text = "\u203A",
            fontSize = 24.sp,
            color = TerminalTextMuted,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
