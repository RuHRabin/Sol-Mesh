package com.example.ui.transfers

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TransferEntity
import com.example.p2p.P2PNode
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
fun TransfersScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier
) {
    val transfers by p2pNode.database.transferDao().getAllTransfersFlow().collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("transfers_screen")
    ) {
        Text(
            text = "DIRECT_STREAM_CHUNKS",
            fontFamily = RobotoMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TerminalTextPrompt
        )
        Text(
            text = "PROTOCOL: /p2p-file/1.0.0 • CHUNK_BUFFER: 65,536 BYTES • ZERO_CLOUD",
            fontFamily = RobotoMonoFontFamily,
            fontSize = 10.sp,
            color = TerminalTextMuted
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (transfers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "[NO_ACTIVE_FILE_PIPELINES]",
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TerminalAmber
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Use '$ attach' in chat session to initiate direct chunked transfer.",
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
                items(transfers, key = { it.id }) { transfer ->
                    TerminalTransferCard(
                        transfer = transfer,
                        onResume = { p2pNode.fileTransferManager.resumeTransfer(transfer.fileId) },
                        onCancel = { p2pNode.fileTransferManager.cancelTransfer(transfer.fileId) }
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalTransferCard(
    transfer: TransferEntity,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (transfer.chunksTotal > 0) {
        (transfer.chunksTransferred.toFloat() / transfer.chunksTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val percent = (progress * 100).toInt()

    val formattedSize = remember(transfer.fileSizeBytes) {
        val kb = transfer.fileSizeBytes / 1024.0
        val mb = kb / 1024.0
        if (mb >= 1.0) String.format("%.2f MB", mb) else String.format("%.1f KB", kb)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transfer_card_${transfer.fileId}"),
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
                    text = if (transfer.isOutgoing) "[SCP_TX]" else "[SCP_RX]",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (transfer.isOutgoing) TerminalCyan else TerminalGreenBright
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = transfer.fileName,
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = TerminalTextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "[${transfer.status}]",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = when (transfer.status) {
                        "COMPLETED" -> TerminalGreenBright
                        "TRANSFERRING" -> TerminalCyan
                        "PAUSED" -> TerminalAmber
                        "FAILED" -> TerminalRed
                        else -> TerminalTextMuted
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$formattedSize • target: ${transfer.peerId.take(12)}...",
                fontFamily = RobotoMonoFontFamily,
                fontSize = 10.sp,
                color = TerminalTextMuted
            )

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = when (transfer.status) {
                    "COMPLETED" -> TerminalGreenBright
                    "FAILED" -> TerminalRed
                    else -> TerminalGreen
                },
                trackColor = TerminalSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CHUNKS: ${transfer.chunksTransferred}/${transfer.chunksTotal} ($percent%)",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    color = TerminalTextSecondary
                )

                Row {
                    if (transfer.status == "PAUSED" || transfer.status == "FAILED") {
                        IconButton(onClick = onResume, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                tint = TerminalGreenBright,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (transfer.status == "TRANSFERRING" || transfer.status == "PENDING") {
                        IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = TerminalRed,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            if (transfer.sha256Hash.isNotEmpty()) {
                Text(
                    text = "SHA256: ${transfer.sha256Hash.take(24)}...",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 9.sp,
                    color = TerminalTextMuted
                )
            }
        }
    }
}
