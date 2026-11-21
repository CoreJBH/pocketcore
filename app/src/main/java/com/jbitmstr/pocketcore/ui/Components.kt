package com.jbitmstr.pocketcore.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.pocketcore.core.NodeStatus

/**
 * Copies [text] to the clipboard.
 *
 * [isSensitive] marks an RPC password or a wallet conf: from Android 13 the
 * system then hides the item's preview (and its content) in the clipboard
 * overlay and keeps it out of the clipboard history, which is what a secret
 * should never appear in. The flag is ignored on older releases.
 */
fun copyToClipboard(context: Context, label: String, text: String, isSensitive: Boolean = false) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (isSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = title,
            color = PocketColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, color = PocketColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    background: Color = PocketColors.Card,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, PocketColors.Hairline, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        content()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = PocketColors.Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp
    )
}

@Composable
fun StatusDot(color: Color, size: Int = 7) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun StatusPill(status: NodeStatus, detail: String?, syncing: Boolean) {
    val (label, color) = when (status) {
        NodeStatus.RUNNING -> if (syncing) "SYNCING" to PocketColors.Accent else "RUNNING" to PocketColors.Running
        NodeStatus.STARTING -> "STARTING" to PocketColors.Accent
        NodeStatus.STOPPING -> "STOPPING" to PocketColors.Warning
        NodeStatus.WAITING -> "WAITING" to PocketColors.Warning
        NodeStatus.ERROR -> "ERROR" to PocketColors.Error
        NodeStatus.STOPPED -> "STOPPED" to PocketColors.Muted
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PocketColors.CardElevated)
            .border(1.dp, PocketColors.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PocketColors.Card)
            .border(1.dp, PocketColors.Hairline, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            color = PocketColors.Text,
            fontSize = 19.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = sub, color = PocketColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = true,
    truncate: Boolean = false,
    copyLabel: String? = null,
    copyValue: String? = null,
    copySensitive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .then(
                if (copyValue != null) Modifier.clickable {
                    copyToClipboard(context, copyLabel ?: label, copyValue, copySensitive)
                } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = PocketColors.Muted, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = PocketColors.Text,
            fontSize = 12.sp,
            fontFamily = if (mono) Mono else null,
            textAlign = TextAlign.End,
            maxLines = if (truncate) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
        if (copyValue != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = "COPY", color = PocketColors.Accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    tint: Color = PocketColors.Text
) {
    val background = when {
        !enabled -> PocketColors.CardElevated
        filled -> PocketColors.Accent
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> PocketColors.Faint
        filled -> PocketColors.OnAccent
        else -> tint
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, if (filled && enabled) background else PocketColors.Hairline, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = content,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun ProgressLine(fraction: Double, color: Color = PocketColors.Accent) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(PocketColors.CardElevated)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PocketColors.Text else Color.Transparent)
            .border(1.dp, if (selected) PocketColors.Text else PocketColors.Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) PocketColors.OnAccent else PocketColors.Muted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, color = PocketColors.Text, fontSize = 14.sp)
            Spacer(Modifier.height(3.dp))
            Text(text = description, color = PocketColors.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = PocketColors.OnAccent,
                checkedTrackColor = PocketColors.Running,
                uncheckedThumbColor = PocketColors.Muted,
                uncheckedTrackColor = PocketColors.CardElevated,
                uncheckedBorderColor = PocketColors.Hairline
            )
        )
    }
}

@Composable
fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PocketColors.Hairline)
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, color: Color = PocketColors.Muted, size: Int = 10) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        lineHeight = (size + 4).sp
    )
}

@Composable
fun MonoText(text: String, size: Int = 12, color: Color = PocketColors.Text, style: TextStyle = TextStyle()) {
    Text(text = text, color = color, fontSize = size.sp, fontFamily = Mono, style = style)
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text = text,
        color = PocketColors.Faint,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
fun SpacerH(height: Int) {
    Spacer(Modifier.height(height.dp))
}

@Composable
fun RowSpacer() = Spacer(Modifier.width(10.dp))

@Composable
fun WrapRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * Input used for block hashes, transaction ids, descriptors and raw
 * transactions: monospaced, dark, and able to hold several lines of hex.
 */
@Composable
fun MonoField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    enabled: Boolean = true,
    showCounter: Boolean = false
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = {
            Text(text = placeholder, color = PocketColors.Faint, fontSize = 11.sp, fontFamily = Mono)
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = PocketColors.Text,
            fontSize = 11.sp,
            fontFamily = Mono
        ),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(12.dp),
        supportingText = if (showCounter) {
            { Text(text = "${value.count { !it.isWhitespace() }} characters", color = PocketColors.Faint, fontSize = 9.sp) }
        } else {
            null
        },
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PocketColors.Accent,
            unfocusedBorderColor = PocketColors.Hairline,
            focusedContainerColor = PocketColors.CardElevated,
            unfocusedContainerColor = PocketColors.CardElevated,
            disabledContainerColor = PocketColors.Card,
            cursorColor = PocketColors.Accent
        )
    )
}

/** Small inline banner for notes and errors inside a card. */
@Composable
fun NoteBanner(text: String, tone: Color = PocketColors.Muted) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PocketColors.CardElevated)
            .padding(10.dp)
    ) {
        Text(text = text, color = tone, fontSize = 11.sp)
    }
}
