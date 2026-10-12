package com.example.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextPrompt
import com.example.ui.theme.TerminalTextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var backgroundServiceEnabled by remember { mutableStateOf(true) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    val circuitStatus by p2pNode.circuitHopperManager.circuitStatus.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "DAEMON_CONFIGURATION",
            fontFamily = RobotoMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TerminalTextPrompt
        )

        // Ephemeral Zero Data Retention Mode
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "EPHEMERAL_ZERO_DATA_RETENTION",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TerminalTextPrompt
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Guarantee zero persistence: all network traffic, circuit routing, and message packets are forwarded strictly in RAM without saving any data.",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    color = TerminalTextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (circuitStatus.isZeroDataRetentionActive) "[RAM_ONLY: ENFORCED]" else "[DISK_LOGS: PERMITTED]",
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (circuitStatus.isZeroDataRetentionActive) TerminalGreenBright else TerminalAmber
                    )
                    Switch(
                        checked = circuitStatus.isZeroDataRetentionActive,
                        onCheckedChange = {
                            p2pNode.circuitHopperManager.toggleZeroDataRetention(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TerminalGreen
                        )
                    )
                }
            }
        }

        // Background Foreground Service
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SOCKET_DAEMON_PERSISTENCE",
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TerminalTextPrompt
                        )
                        Text(
                            text = "Keep direct port 9091 and Kademlia UDP listening in background.",
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 10.sp,
                            color = TerminalTextMuted
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
                            checkedTrackColor = TerminalGreen
                        ),
                        modifier = Modifier.testTag("background_service_switch")
                    )
                }
            }
        }

        // Clear Local SQLite Disk Cache
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "PURGE_STORAGE",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TerminalTextPrompt
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Purges all local SQLite messages, cached peer contacts, and transfer logs.",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    color = TerminalTextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, TerminalRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalRed),
                    modifier = Modifier.testTag("clear_data_button")
                ) {
                    Text("$ rm -rf /data/sol-mesh.db", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp)
                }
            }
        }

        // System Kernel / About
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "KERNEL_INFO",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TerminalTextPrompt
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "sol-mesh Core v1.0.0-cli (Linux Android Kernel)\n" +
                            "• Mode 1: Peer-to-peer crypto mesh (X25519, Ed25519, Double Ratchet)\n" +
                            "• Mode 2: Decentralized dynamic public node hopping (Zero data saved)\n" +
                            "• AEAD: AES-256-GCM 128-bit authentication tag",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = TerminalTextSecondary
                )
            }
        }
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = {
                Text(
                    text = "$ rm -rf --purge",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 13.sp,
                    color = TerminalRed
                )
            },
            text = {
                Text(
                    text = "Confirm total disk purge of all local messages and peer tables?",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 11.sp,
                    color = TerminalTextPrimary
                )
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            p2pNode.database.clearAllTables()
                        }
                        Toast.makeText(context, "[PURGE] LOCAL_TABLES_CLEARED", Toast.LENGTH_SHORT).show()
                        showClearDataDialog = false
                    },
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, TerminalRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalRed)
                ) {
                    Text("$ confirm_purge", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("abort", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp, color = TerminalTextMuted)
                }
            },
            containerColor = TerminalBg,
            shape = RoundedCornerShape(2.dp)
        )
    }
}
