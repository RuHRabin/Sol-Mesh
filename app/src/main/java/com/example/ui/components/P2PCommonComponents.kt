package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary
import com.example.ui.theme.TerminalTextSecondary

@Composable
fun StatusBadge(
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val statusColor = if (isOnline) TerminalGreenBright else TerminalAmber
    val text = if (isOnline) "[DAEMON_ONLINE]" else "[DAEMON_HALTED]"

    Surface(
        modifier = modifier.testTag("status_badge"),
        shape = RoundedCornerShape(2.dp),
        color = TerminalSurface,
        border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = statusColor,
                fontFamily = RobotoMonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SecurityShieldBanner(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("security_banner"),
        shape = RoundedCornerShape(2.dp),
        color = TerminalSurface,
        border = BorderStroke(0.5.dp, TerminalBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[E2EE: ACTIVE] X25519-DoubleRatchet • ZERO_CLOUD • RAM_FORWARDING",
                fontFamily = RobotoMonoFontFamily,
                color = TerminalTextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun TruncatedPeerId(
    peerId: String,
    modifier: Modifier = Modifier,
    enableCopy: Boolean = true
) {
    val context = LocalContext.current
    val display = if (peerId.length > 16) {
        "${peerId.take(8)}...${peerId.takeLast(8)}"
    } else {
        peerId
    }

    Surface(
        modifier = modifier
            .testTag("peer_id_text")
            .then(
                if (enableCopy) {
                    Modifier.clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PeerID", peerId))
                        Toast.makeText(context, "[CLIPBOARD] PEER_ID_COPIED", Toast.LENGTH_SHORT).show()
                    }
                } else Modifier
            ),
        shape = RoundedCornerShape(2.dp),
        color = TerminalSurface,
        border = BorderStroke(0.5.dp, TerminalBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = display,
                fontFamily = RobotoMonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = TerminalTextPrimary
            )
            if (enableCopy) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = TerminalTextMuted,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}
