package com.example.ui.network

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.p2p.DhtManager
import com.example.p2p.Multiaddress
import com.example.p2p.P2PNode
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
fun NetworkStatusScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier
) {
    val status by p2pNode.nodeStatus.collectAsState()
    val circuitStatus by p2pNode.circuitHopperManager.circuitStatus.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("network_status_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Network details",
                fontFamily = RobotoMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = TerminalTextPrompt
            )
            Text(
                text = "Technical information about how this phone connects to others.",
                fontFamily = RobotoMonoFontFamily,
                fontSize = 12.sp,
                color = TerminalTextMuted
            )
        }

        item {
            // Socket Bindings (netstat -tuln style)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(2.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalBg),
                border = BorderStroke(1.dp, TerminalBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Listening on",
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TerminalTextPrompt
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    TerminalNetstatRow(proto = "tcp", bind = "0.0.0.0:${status.listeningPort}", state = "LISTEN (direct_p2p)")
                    TerminalNetstatRow(proto = "udp", bind = "0.0.0.0:${p2pNode.dhtManager.dhtPort}", state = "LISTEN (kademlia_dht)")
                    TerminalNetstatRow(proto = "udp", bind = "255.255.255.255:9090", state = "BROADCAST (lan_beacon)")

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Public address: ${status.observedPublicAddress ?: "not found yet"}",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        color = TerminalCyan
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Addresses on this network:",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        color = TerminalTextMuted
                    )
                    status.localAddresses.forEach { addr ->
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = TerminalSurface,
                            border = BorderStroke(0.5.dp, TerminalBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = addr,
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 12.sp,
                                color = TerminalTextPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            // Kademlia DHT Table Stats
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
                        Text(
                            text = "Discovery network",
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = TerminalTextPrompt
                        )
                        Text(
                            text = "${status.dhtRoutingTableSize} known",
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = TerminalGreenBright
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Phones and servers this app knows about, used to find people outside your Wi-Fi.",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        color = TerminalTextMuted
                    )
                }
            }
        }

        item {
            Text(
                text = "Starting servers for discovery",
                fontFamily = RobotoMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TerminalTextPrompt
            )
        }

        items(DhtManager.DEFAULT_BOOTSTRAP_NODES) { nodeAddr ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(2.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalBg),
                border = BorderStroke(1.dp, TerminalBorder)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nodeAddr,
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 12.sp,
                            color = TerminalTextPrimary
                        )
                        Text(
                            text = "discovery server",
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 11.sp,
                            color = TerminalTextMuted
                        )
                    }
                    IconButton(
                        onClick = {
                            try {
                                p2pNode.dhtManager.pingNode(Multiaddress.fromString(nodeAddr))
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Ping",
                            tint = TerminalCyan,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalNetstatRow(proto: String, bind: String, state: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$proto  $bind",
            fontFamily = RobotoMonoFontFamily,
            fontSize = 12.sp,
            color = TerminalTextPrimary
        )
        Text(
            text = state,
            fontFamily = RobotoMonoFontFamily,
            fontSize = 12.sp,
            color = TerminalGreenBright
        )
    }
}
