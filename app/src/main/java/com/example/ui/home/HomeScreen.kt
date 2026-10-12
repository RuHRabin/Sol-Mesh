package com.example.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PeerEntity
import com.example.p2p.P2PNode
import com.example.p2p.RoutingMode
import com.example.service.P2PNodeService
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
import com.example.ui.theme.TerminalTextPrompt
import com.example.ui.theme.TerminalTextSecondary

@Composable
fun HomeScreen(
    p2pNode: P2PNode,
    onNavigateToPeers: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onNavigateToTransfers: () -> Unit,
    onNavigateToIdentity: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val status by p2pNode.nodeStatus.collectAsState()
    val circuitStatus by p2pNode.circuitHopperManager.circuitStatus.collectAsState()
    val peers by p2pNode.database.peerDao().getAllPeersFlow().collectAsState(initial = emptyList())

    var quickIpInput by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }

    // Pulsing radar animation
    val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_scale"
    )

    val primaryLocalAddress = remember(status.localAddresses, status.listeningPort) {
        val nonLoopback = status.localAddresses.firstOrNull { !it.contains("127.0.0.1") }
        if (nonLoopback != null) {
            val clean = nonLoopback.removePrefix("/ip4/").removeSuffix("/tcp/${status.listeningPort}")
            "$clean:${status.listeningPort}"
        } else {
            "127.0.0.1:${status.listeningPort}"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Hero Card: My Device P2P Address
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("my_address_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalBg),
                border = BorderStroke(1.dp, TerminalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (status.isRunning) TerminalGreenBright else TerminalAmber,
                                modifier = Modifier.size(10.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (status.isRunning) "P2P Node Active" else "P2P Node Halted",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TerminalTextPrimary
                            )
                        }

                        Switch(
                            checked = status.isRunning,
                            onCheckedChange = { start ->
                                if (start) {
                                    P2PNodeService.startService(context)
                                } else {
                                    P2PNodeService.stopService(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = TerminalGreen
                            ),
                            modifier = Modifier.testTag("node_power_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Share this address with another phone on the same Wi-Fi / Hotspot to connect directly:",
                        fontSize = 12.sp,
                        color = TerminalTextSecondary,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = TerminalSurface,
                        border = BorderStroke(0.5.dp, TerminalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MY DIRECT P2P ADDRESS",
                                    fontFamily = RobotoMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TerminalCyan
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = primaryLocalAddress,
                                    fontFamily = RobotoMonoFontFamily,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TerminalGreenBright
                                )
                            }

                            Button(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("P2P Address", primaryLocalAddress))
                                    Toast.makeText(context, "Address copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                                modifier = Modifier.testTag("copy_address_button")
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            // Quick Connect Card: Connect Directly to Another Phone
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_connect_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalBg),
                border = BorderStroke(1.dp, TerminalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AddLink,
                            contentDescription = null,
                            tint = TerminalCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Direct Connect to Other Phone",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TerminalTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter the IP address shown on the other phone (e.g. 192.168.1.5 or 192.168.43.1):",
                        fontSize = 12.sp,
                        color = TerminalTextMuted
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quickIpInput,
                            onValueChange = { quickIpInput = it },
                            placeholder = { Text("192.168.1.15", fontSize = 13.sp, color = TerminalTextMuted) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_ip_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = TerminalSurface,
                                unfocusedContainerColor = TerminalSurface,
                                focusedBorderColor = TerminalGreenBright,
                                unfocusedBorderColor = TerminalBorder
                            )
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = {
                                val target = quickIpInput.trim()
                                if (target.isNotEmpty()) {
                                    isConnecting = true
                                    p2pNode.connectDirectlyToAddress(target)
                                    Toast.makeText(context, "Connecting directly to $target...", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                            modifier = Modifier.testTag("quick_connect_button")
                        ) {
                            Text("Connect", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            // Radar Discovery Card: Scan for Nearby Phones
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("radar_scan_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalBg),
                border = BorderStroke(1.dp, TerminalBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = TerminalSurface,
                            border = BorderStroke(1.dp, TerminalGreen),
                            modifier = Modifier
                                .size(42.dp)
                                .scale(pulseScale)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Radar,
                                    contentDescription = "Radar",
                                    tint = TerminalGreenBright,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Wi-Fi & Hotspot Discovery",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TerminalTextPrimary
                            )
                            Text(
                                text = "Auto-detecting devices on local subnet",
                                fontSize = 11.sp,
                                color = TerminalTextSecondary
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            p2pNode.broadcastDiscoveryNow()
                            Toast.makeText(context, "Broadcasting discovery beacons...", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, TerminalGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalGreenBright),
                        modifier = Modifier.testTag("scan_now_button")
                    ) {
                        Text("Scan Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            // Discovered Peers Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby Devices (${peers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TerminalTextPrimary
                )

                if (peers.isNotEmpty()) {
                    Text(
                        text = "Manage All",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalCyan,
                        modifier = Modifier
                            .clickable(onClick = onNavigateToPeers)
                            .padding(4.dp)
                    )
                }
            }
        }

        if (peers.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TerminalBg,
                    border = BorderStroke(1.dp, TerminalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = TerminalAmber,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No other phones detected yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TerminalTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Make sure both phones are connected to the same Wi-Fi or mobile hotspot. You can also connect directly by entering the other phone's IP address above.",
                            fontSize = 12.sp,
                            color = TerminalTextMuted,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        } else {
            items(peers, key = { it.peerId }) { peer ->
                UserFriendlyPeerCard(
                    peer = peer,
                    onConnect = { p2pNode.connectToPeer(peer.peerId) },
                    onChat = { onNavigateToChat(peer.peerId) }
                )
            }
        }
    }
}

@Composable
fun UserFriendlyPeerCard(
    peer: PeerEntity,
    onConnect: () -> Unit,
    onChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onChat)
            .testTag("peer_card_${peer.peerId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        border = BorderStroke(1.dp, TerminalBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (peer.connectionState == "CONNECTED") TerminalGreen.copy(alpha = 0.2f) else TerminalSurface,
                border = BorderStroke(1.dp, if (peer.connectionState == "CONNECTED") TerminalGreen else TerminalBorder),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = if (peer.connectionState == "CONNECTED") TerminalGreenBright else TerminalTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TerminalTextPrimary
                    )
                    if (peer.isVerified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Verified",
                            tint = TerminalGreenBright,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (peer.connectionState == "CONNECTED") "Connected (E2EE Ready)" else "Discovered Nearby",
                    fontSize = 11.sp,
                    color = if (peer.connectionState == "CONNECTED") TerminalGreenBright else TerminalTextMuted
                )
            }

            Button(
                onClick = onChat,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                modifier = Modifier.testTag("chat_btn_${peer.peerId}")
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chat", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
