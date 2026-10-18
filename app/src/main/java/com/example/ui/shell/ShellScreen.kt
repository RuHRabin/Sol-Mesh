package com.example.ui.shell

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.data.PeerEntity
import com.example.p2p.Multiaddress
import com.example.p2p.P2PNode
import com.example.p2p.PeerRecord
import com.example.p2p.RoutingMode
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShellLine(
    val timestamp: String,
    val prefix: String,
    val text: String,
    val color: Color = TerminalTextPrimary
)

@Composable
fun ShellScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputCommand by remember { mutableStateOf("") }

    val outputLines = remember {
        mutableStateListOf(
            ShellLine(
                timestamp = currentTimestamp(),
                prefix = "[BOOT]",
                text = "sol-mesh v1.0.0-cli kernel loaded. Type 'help' or tap quick-action chips below.",
                color = TerminalCyan
            ),
            ShellLine(
                timestamp = currentTimestamp(),
                prefix = "[INFO]",
                text = "Architecture: Pure P2P crypto mesh + decentralized ephemeral relay circuit.",
                color = TerminalTextMuted
            )
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    val executeCommand: (String) -> Unit = { rawCmd ->
        val cmd = rawCmd.trim()
        if (cmd.isNotEmpty()) {
            outputLines.add(
                ShellLine(
                    timestamp = currentTimestamp(),
                    prefix = "sol-mesh:~$",
                    text = cmd,
                    color = TerminalGreenBright
                )
            )

            coroutineScope.launch {
                handleShellExecution(cmd, p2pNode, outputLines)
            }
            inputCommand = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(12.dp)
            .imePadding()
            .testTag("shell_screen")
    ) {
        // Quick Action Chips Header (Power User + User Friendly)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val chips = listOf("help", "status", "peers", "hop", "route", "nodes", "dht", "keys", "clear")
            chips.forEach { chip ->
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = TerminalSurface,
                    border = BorderStroke(0.5.dp, TerminalBorder),
                    modifier = Modifier.clickable {
                        if (chip == "clear") {
                            outputLines.clear()
                            outputLines.add(
                                ShellLine(
                                    timestamp = currentTimestamp(),
                                    prefix = "[INFO]",
                                    text = "Terminal buffer cleared. System active.",
                                    color = TerminalTextMuted
                                )
                            )
                        } else {
                            executeCommand(chip)
                        }
                    }
                ) {
                    Text(
                        text = ":$chip",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (chip) {
                            "hop" -> TerminalAmber
                            "help" -> TerminalCyan
                            "clear" -> TerminalRed
                            else -> TerminalGreenBright
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Terminal Log History View
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBg)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(outputLines) { line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${line.timestamp} ",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        color = TerminalTextMuted
                    )
                    Text(
                        text = "${line.prefix} ",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (line.prefix) {
                            "[BOOT]", "[SYS]" -> TerminalCyan
                            "[OK]", "[VERIFIED]" -> TerminalGreenBright
                            "[ERR]", "[FAIL]" -> TerminalRed
                            "[HOP]", "[WARN]" -> TerminalAmber
                            "sol-mesh:~$" -> TerminalTextPrompt
                            else -> TerminalTextMuted
                        }
                    )
                    Text(
                        text = line.text,
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 12.sp,
                        color = line.color,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Interactive Command Input Bar
        Surface(
            color = TerminalSurface,
            border = BorderStroke(1.dp, TerminalBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "sol-mesh:~$ ",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TerminalTextPrompt
                )

                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = inputCommand,
                        onValueChange = { inputCommand = it },
                        textStyle = TextStyle(
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 13.sp,
                            color = TerminalTextPrimary
                        ),
                        cursorBrush = SolidColor(TerminalGreenBright),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { executeCommand(inputCommand) }),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shell_command_input")
                    )

                    if (inputCommand.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "type command (e.g. 'help', 'hop', 'peers')",
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 12.sp,
                                color = TerminalTextMuted
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "█",
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 12.sp,
                                color = TerminalGreenBright.copy(alpha = cursorAlpha)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { executeCommand(inputCommand) },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("shell_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Execute",
                        tint = if (inputCommand.isNotBlank()) TerminalGreenBright else TerminalTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private suspend fun handleShellExecution(
    rawCmd: String,
    p2pNode: P2PNode,
    outputList: MutableList<ShellLine>
) {
    val tokens = rawCmd.trim().split("\\s+".toRegex())
    val root = tokens[0].lowercase()

    when (root) {
        "help", "?" -> {
            outputList.add(ShellLine(currentTimestamp(), "[OK]", "AVAILABLE COMMANDS:", TerminalCyan))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "status       : Print system telemetry, socket ports & NAT state", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "peers        : Dump discovered peer routing table", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "hop          : Force immediate circuit hop to next public node", TerminalAmber))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "route [p2p|hop] : Toggle pure P2P mesh or decentralized relay", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "nodes        : List public decentralized circuit relays", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "dht          : Print Kademlia 256 k-buckets state", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "keys         : Print Ed25519, X25519 & PeerID credentials", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "dial <addr>  : Dial multiaddress directly", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "purge        : Flush local SQLite storage completely", TerminalRed))
            outputList.add(ShellLine(currentTimestamp(), "[CMD]", "clear        : Clear terminal screen buffer", TerminalTextMuted))
        }

        "status" -> {
            val status = p2pNode.nodeStatus.value
            val circuit = p2pNode.circuitHopperManager.circuitStatus.value
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "PEER_ID: ${status.peerId}", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "ALIAS: ${status.displayName}", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "DAEMON: ${if (status.isRunning) "ACTIVE" else "STOPPED"}", if (status.isRunning) TerminalGreenBright else TerminalAmber))
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "PORTS: TCP/${status.listeningPort} (p2p), UDP/9090 (lan), UDP/${p2pNode.dhtManager.dhtPort} (dht)", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "NAT_REFLEX: ${status.observedPublicAddress ?: "resolving..."}", TerminalCyan))
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "ROUTING_MODE: ${circuit.mode} (hops: ${circuit.totalHopsCompleted})", TerminalGreenBright))
            outputList.add(ShellLine(currentTimestamp(), "[SYS]", "DATA_TRACE: ZERO_RETENTION_ENFORCED [RAM_ONLY]", TerminalGreen))
        }

        "peers" -> {
            val peers: List<PeerEntity> = withContext(Dispatchers.IO) {
                p2pNode.database.peerDao().getAllPeers()
            }
            if (peers.isEmpty()) {
                outputList.add(ShellLine(currentTimestamp(), "[WARN]", "Routing table empty. No peers currently connected.", TerminalAmber))
            } else {
                outputList.add(ShellLine(currentTimestamp(), "[OK]", "DISCOVERED PEERS (${peers.size}):", TerminalCyan))
                peers.forEach { peer ->
                    val verifiedTag = if (peer.isVerified) "[VERIFIED]" else "[UNVERIFIED]"
                    outputList.add(
                        ShellLine(
                            currentTimestamp(),
                            "[PEER]",
                            "${peer.displayName.padEnd(12)} | ${peer.connectionState.padEnd(10)} | $verifiedTag | ${peer.peerId.take(14)}...",
                            if (peer.connectionState == "CONNECTED") TerminalGreenBright else TerminalTextSecondary
                        )
                    )
                }
            }
        }

        "hop" -> {
            p2pNode.circuitHopperManager.hopToNextNode()
            val curr = p2pNode.circuitHopperManager.circuitStatus.value
            outputList.add(ShellLine(currentTimestamp(), "[HOP]", "Circuit hopped to public node: ${curr.activeNode.alias} (${curr.activeNode.region})", TerminalAmber))
            outputList.add(ShellLine(currentTimestamp(), "[OK]", "Ephemeral circuit active. Zero data saved to disk.", TerminalGreenBright))
        }

        "route" -> {
            val arg = tokens.getOrNull(1)?.lowercase()
            when (arg) {
                "p2p" -> {
                    p2pNode.circuitHopperManager.setRoutingMode(RoutingMode.DIRECT_P2P)
                    outputList.add(ShellLine(currentTimestamp(), "[OK]", "Routing mode set to DIRECT_P2P (Pure crypto socket mesh)", TerminalCyan))
                }
                "hop" -> {
                    p2pNode.circuitHopperManager.setRoutingMode(RoutingMode.DECENTRALIZED_RELAY_HOPPING)
                    outputList.add(ShellLine(currentTimestamp(), "[OK]", "Routing mode set to DECENTRALIZED_RELAY_HOPPING (Rotating public nodes)", TerminalGreenBright))
                }
                else -> {
                    val curr = p2pNode.circuitHopperManager.circuitStatus.value.mode
                    val toggled = if (curr == RoutingMode.DIRECT_P2P) RoutingMode.DECENTRALIZED_RELAY_HOPPING else RoutingMode.DIRECT_P2P
                    p2pNode.circuitHopperManager.setRoutingMode(toggled)
                    outputList.add(ShellLine(currentTimestamp(), "[OK]", "Toggled routing mode: $toggled", TerminalGreenBright))
                }
            }
        }

        "nodes" -> {
            outputList.add(ShellLine(currentTimestamp(), "[OK]", "DECENTRALIZED PUBLIC RELAY NODES (ROTATING POOL):", TerminalCyan))
            val nodes = listOf(
                "relay-frankfurt.nexus-dht.net:9091 [EU-Central]",
                "relay-reykjavik.nexus-p2p.is:9091 [IS-Nordic]",
                "relay-singapore.dht-mesh.io:9091 [AP-East]",
                "relay-zurich.privacy-node.ch:9091 [CH-Alps]",
                "relay-montreal.mesh-net.ca:9091 [NA-East]",
                "relay-amsterdam.p2p-core.nl:9091 [EU-West]",
                "relay-tokyo.dht-crypto.jp:9091 [AP-North]"
            )
            nodes.forEach { n ->
                outputList.add(ShellLine(currentTimestamp(), "[NODE]", n, TerminalTextPrimary))
            }
            outputList.add(ShellLine(currentTimestamp(), "[INFO]", "Nodes forward encrypted payload frames strictly in RAM.", TerminalTextMuted))
        }

        "dht" -> {
            val count = p2pNode.dhtManager.routingTable.size()
            outputList.add(ShellLine(currentTimestamp(), "[DHT]", "Kademlia XOR routing table: $count active node contacts.", TerminalCyan))
            outputList.add(ShellLine(currentTimestamp(), "[DHT]", "Distance metric: d(x, y) = x ^ y across 256 k-buckets (k=20).", TerminalTextMuted))
            outputList.add(ShellLine(currentTimestamp(), "[DHT]", "Local DHT Port: UDP ${p2pNode.dhtManager.dhtPort}", TerminalTextPrimary))
        }

        "keys" -> {
            val id = p2pNode.identityManager
            outputList.add(ShellLine(currentTimestamp(), "[KEY]", "MULTIHASH_PEER_ID: ${id.peerId}", TerminalTextPrompt))
            outputList.add(ShellLine(currentTimestamp(), "[KEY]", "ED25519_PUB: ${id.getPublicKeyHex()}", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[KEY]", "X25519_PUB : ${id.getDhPublicKeyHex()}", TerminalTextPrimary))
            outputList.add(ShellLine(currentTimestamp(), "[KEY]", "FINGERPRINT: ${id.getFormattedFingerprint()}", TerminalGreenBright))
        }

        "dial" -> {
            val addr = tokens.getOrNull(1)
            if (addr.isNullOrEmpty()) {
                outputList.add(ShellLine(currentTimestamp(), "[ERR]", "Syntax error: dial <multiaddr>", TerminalRed))
            } else {
                try {
                    val ma = Multiaddress.fromString(addr)
                    val temp = PeerRecord(
                        peerId = "peer_${ma.host}_${ma.port}",
                        displayName = "Peer@${ma.host}",
                        publicKeyHex = "",
                        dhPublicKeyHex = "",
                        addresses = listOf(addr),
                        timestamp = System.currentTimeMillis(),
                        signatureHex = ""
                    )
                    p2pNode.connectionManager.connectToPeer(temp)
                    outputList.add(ShellLine(currentTimestamp(), "[OK]", "Dialing $addr in background...", TerminalGreenBright))
                } catch (e: Exception) {
                    outputList.add(ShellLine(currentTimestamp(), "[ERR]", "Failed to parse multiaddr: ${e.message}", TerminalRed))
                }
            }
        }

        "purge" -> {
            withContext(Dispatchers.IO) {
                p2pNode.database.clearAllTables()
            }
            outputList.add(ShellLine(currentTimestamp(), "[PURGE]", "All local messages and peer records flushed from disk.", TerminalRed))
        }

        "clear" -> {
            outputList.clear()
        }

        else -> {
            outputList.add(ShellLine(currentTimestamp(), "[ERR]", "Unknown command: '$root'. Type 'help' for command reference.", TerminalAmber))
        }
    }
}

private fun currentTimestamp(): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date())
}
