package com.example.ui.transfers

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.example.data.TransferEntity
import com.example.p2p.P2PNode
import com.example.ui.theme.Accent
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorderSubtle
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TransfersScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers by p2pNode.database.transferDao().getAllTransfersFlow().collectAsState(initial = emptyList())
    val peers by p2pNode.database.peerDao().getAllPeersFlow().collectAsState(initial = emptyList())
    val nameById = remember(peers) { peers.associate { it.peerId to it.displayName } }
    var pendingSave by remember { mutableStateOf<TransferEntity?>(null) }

    // Received files live in the app's private storage; this lets the user copy one anywhere they like.
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val transfer = pendingSave
        pendingSave = null
        if (uri != null && transfer != null) {
            scope.launch(Dispatchers.IO) {
                val message = try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        File(transfer.localFilePath).inputStream().use { input -> input.copyTo(out) }
                    }
                    "Saved ${transfer.fileName}"
                } catch (e: Exception) {
                    "Could not save the file: ${e.message}"
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("transfers_screen")
    ) {
        Text(
            text = "Files",
            style = MaterialTheme.typography.headlineMedium,
            color = TerminalTextPrimary
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
                        text = "No files yet",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Send a file from a chat with the paperclip button. " +
                            "Files you receive show up here, and you can save them to your phone.",
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        color = TerminalTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(transfers, key = { it.id }) { transfer ->
                    TransferCard(
                        transfer = transfer,
                        peerName = nameById[transfer.peerId] ?: "Unknown contact",
                        onResume = { p2pNode.fileTransferManager.resumeTransfer(transfer.fileId) },
                        onCancel = { p2pNode.fileTransferManager.cancelTransfer(transfer.fileId) },
                        onSave = {
                            pendingSave = transfer
                            saveLauncher.launch(transfer.fileName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: TransferEntity,
    peerName: String,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val progress = if (transfer.chunksTotal > 0) {
        (transfer.chunksTransferred.toFloat() / transfer.chunksTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val percent = (progress * 100).toInt()

    val formattedSize = remember(transfer.fileSizeBytes) {
        val kb = transfer.fileSizeBytes / 1024.0
        val mb = kb / 1024.0
        if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", kb.coerceAtLeast(1.0))
    }

    val statusText = when (transfer.status) {
        "COMPLETED" -> if (transfer.isOutgoing) "Sent" else "Received"
        "TRANSFERRING" -> (if (transfer.isOutgoing) "Sending" else "Receiving") + " $percent%"
        "PENDING", "ACCEPTED" -> "Waiting..."
        "PAUSED" -> "Paused at $percent%"
        "FAILED" -> "Failed"
        "CANCELLED" -> "Cancelled"
        else -> transfer.status.lowercase().replaceFirstChar { it.uppercase() }
    }
    val statusColor = when (transfer.status) {
        "COMPLETED" -> TerminalGreenBright
        "TRANSFERRING" -> Accent
        "PAUSED" -> TerminalAmber
        "FAILED" -> TerminalRed
        else -> TerminalTextMuted
    }
    val showProgress = transfer.status != "COMPLETED" && transfer.status != "CANCELLED"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transfer_card_${transfer.fileId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        border = BorderStroke(1.dp, TerminalBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = (if (transfer.isOutgoing) "To " else "From ") + peerName + "  \u2022  " + formattedSize,
                        fontSize = 13.sp,
                        color = TerminalTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }

            if (showProgress) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (transfer.status == "FAILED") TerminalRed else Accent,
                    trackColor = TerminalSurface
                )
            }

            val canResume = transfer.status == "PAUSED" || transfer.status == "FAILED"
            val canCancel = transfer.status == "TRANSFERRING" || transfer.status == "PENDING"
            val canSave = transfer.status == "COMPLETED" && !transfer.isOutgoing
            if (canResume || canCancel || canSave) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (canResume) {
                        TextButton(onClick = onResume) {
                            Text("Resume", fontSize = 14.sp, color = Accent)
                        }
                    }
                    if (canCancel) {
                        TextButton(onClick = onCancel) {
                            Text("Cancel", fontSize = 14.sp, color = TerminalRed)
                        }
                    }
                    if (canSave) {
                        Button(
                            onClick = onSave,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.testTag("save_file_${transfer.fileId}")
                        ) {
                            Text("Save to phone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
