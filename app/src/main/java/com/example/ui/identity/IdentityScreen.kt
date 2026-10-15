package com.example.ui.identity

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun IdentityScreen(
    p2pNode: P2PNode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val identityManager = p2pNode.identityManager
    var displayNameInput by remember { mutableStateOf(identityManager.displayName) }
    var isEditingName by remember { mutableStateOf(false) }

    val copyToClipboard: (String, String) -> Unit = { label, text ->
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "[CLIPBOARD] $label COPIED", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("identity_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "CRYPTOGRAPHIC_KEYRING",
            fontFamily = RobotoMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TerminalTextPrompt
        )
        Text(
            text = "HARDWARE_HSM: AndroidKeyStore AES-256-GCM • ZERO_LEAKAGE",
            fontFamily = RobotoMonoFontFamily,
            fontSize = 10.sp,
            color = TerminalTextMuted
        )

        // Node Alias
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "NODE_ALIAS_IDENTIFIER",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    color = TerminalTextMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (isEditingName) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = displayNameInput,
                            onValueChange = { displayNameInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("display_name_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(2.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = TerminalSurface,
                                focusedContainerColor = TerminalSurface,
                                unfocusedBorderColor = TerminalBorder,
                                focusedBorderColor = TerminalGreenBright
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = {
                                identityManager.displayName = displayNameInput.trim()
                                isEditingName = false
                                Toast.makeText(context, "[CONFIG] ALIAS_UPDATED", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, TerminalGreen),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalGreenBright),
                            modifier = Modifier.testTag("save_display_name_button")
                        ) {
                            Text("$ save", fontFamily = RobotoMonoFontFamily, fontSize = 11.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = identityManager.displayName,
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TerminalTextPrimary
                        )
                        IconButton(
                            onClick = { isEditingName = true },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("edit_display_name_button")
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = TerminalTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Hardware Vault Console Box
        Surface(
            shape = RoundedCornerShape(2.dp),
            color = TerminalSurface,
            border = BorderStroke(0.5.dp, TerminalBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "[STATUS: HARDWARE_KEYSTORE_SECURED]",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TerminalGreenBright
                )
                Text(
                    text = "Private master seeds encrypted inside hardware StrongBox / TEE enclave.",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    color = TerminalTextMuted
                )
            }
        }

        // PeerID
        TerminalIdentityKeyCard(
            title = "MULTIHASH_PEER_ID",
            value = identityManager.peerId,
            desc = "libp2p sha256 multihash standard (0x12 0x20 ...)",
            onCopy = { copyToClipboard("PEER_ID", identityManager.peerId) }
        )

        // Ed25519
        TerminalIdentityKeyCard(
            title = "PUB_KEY_ED25519 (SIGNING)",
            value = identityManager.getPublicKeyHex(),
            desc = "RFC 8032 signature verification key",
            onCopy = { copyToClipboard("ED25519_PUB", identityManager.getPublicKeyHex()) }
        )

        // X25519
        TerminalIdentityKeyCard(
            title = "PUB_KEY_X25519 (AGREEMENT)",
            value = identityManager.getDhPublicKeyHex(),
            desc = "RFC 7748 Montgomery Diffie-Hellman key",
            onCopy = { copyToClipboard("X25519_PUB", identityManager.getDhPublicKeyHex()) }
        )

        // Formatted Fingerprint
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
            border = BorderStroke(1.dp, TerminalBorder)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "VISUAL_FINGERPRINT_BLOCK",
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TerminalTextPrompt
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = TerminalSurface,
                    border = BorderStroke(0.5.dp, TerminalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = identityManager.getFormattedFingerprint(),
                        fontFamily = RobotoMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = TerminalGreenBright,
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalIdentityKeyCard(
    title: String,
    value: String,
    desc: String,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalBg),
        border = BorderStroke(1.dp, TerminalBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TerminalTextPrompt
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = TerminalTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = TerminalSurface,
                border = BorderStroke(0.5.dp, TerminalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 10.sp,
                    color = TerminalTextPrimary,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = desc,
                fontFamily = RobotoMonoFontFamily,
                fontSize = 9.sp,
                color = TerminalTextMuted
            )
        }
    }
}
