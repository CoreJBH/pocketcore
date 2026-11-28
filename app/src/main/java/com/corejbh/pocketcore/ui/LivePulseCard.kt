package com.corejbh.pocketcore.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.pocketcore.core.LivePulse
import com.corejbh.pocketcore.core.PeerPulse
import com.corejbh.pocketcore.core.PulseDirection
import com.corejbh.pocketcore.core.PulseEvent
import com.corejbh.pocketcore.core.PulseText

/** How long a peer's dot and numbers stay lit after the last packet. */
private const val ACTIVITY_WINDOW_MS = 1_600L

/** The pulse card draws this many lines; the engine keeps a couple more. */
private const val SHOWN_EVENTS = 4

/**
 * `LIVE NODE PULSE`: this node's P2P traffic, one sample a second, and the
 * messages bitcoind logged for it.
 *
 * The card sits between the START / STOP buttons and the peer card because it
 * answers the question those two raise: the node is up - is it doing anything?
 * Every figure comes from the daemon (getnettotals, getpeerinfo, debug.log);
 * nothing here is a placeholder, so an idle node shows an empty log and a flat
 * line rather than an animation.
 */
@Composable
fun LivePulseCard(pulse: LivePulse, running: Boolean) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Live node pulse", Modifier.weight(1f))
            Text(
                text = "P2P THROUGHPUT",
                color = PocketColors.Faint,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
        SpacerH(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "IN",
                    color = PocketColors.Accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = formatRate(pulse.inBps),
                    color = PocketColors.Text,
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    maxLines = 1
                )
                Text(
                    text = "  \u00b7  ",
                    color = PocketColors.Faint,
                    fontSize = 10.sp
                )
                Text(
                    text = "OUT",
                    color = PocketColors.Accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = formatRate(pulse.outBps),
                    color = PocketColors.Text,
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "TX RATE",
                    color = PocketColors.Faint,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                SpacerH(2)
                Text(
                    text = "${formatTxRate(pulse.txPerSecond)} TX/s",
                    color = PocketColors.Text,
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    maxLines = 1
                )
            }
        }
        SpacerH(10)
        PulseGraph(pulse)
        SpacerH(10)
        val events = pulse.events.take(SHOWN_EVENTS)
        if (events.isEmpty()) {
            Muted(
                if (running) "Waiting for the next P2P message." else "Start the node to see live traffic.",
                color = PocketColors.Faint
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                events.forEach { event -> PulseLogRow(event) }
            }
            SpacerH(8)
            Muted(
                "Each line is a message bitcoind actually sent or received while this card was open " +
                    "(debug.log, net + mempool logging); the time is that peer's measured round trip.",
                color = PocketColors.Faint,
                size = 8
            )
        }
    }
}

/**
 * The sparkline: total P2P bytes a second over the window, cyan line over a
 * gradient fill, scaled to the window's own peak.
 *
 * Drawn from [LivePulse.samples] alone, so what is on screen is the recorded
 * series and not a second, smoother estimate of it.
 */
@Composable
private fun PulseGraph(pulse: LivePulse) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val values = pulse.samples
        val width = size.width
        val height = size.height
        drawLine(
            color = PocketColors.Hairline,
            start = Offset(0f, height),
            end = Offset(width, height),
            strokeWidth = 1.dp.toPx()
        )
        if (values.size < 2) return@Canvas
        val top = if (pulse.scale > 1.0) pulse.scale else 1.0
        val stepX = width / (values.size - 1)
        val usableHeight = height - 2.dp.toPx()
        fun y(value: Double): Float =
            (height - (value.coerceIn(0.0, top) / top * usableHeight).toFloat()).coerceIn(0f, height)
        val line = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            if (index == 0) line.moveTo(x, y(value)) else line.lineTo(x, y(value))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    PocketColors.Accent.copy(alpha = 0.38f),
                    PocketColors.AccentDeep.copy(alpha = 0.02f)
                )
            )
        )
        drawPath(
            path = line,
            color = PocketColors.Accent,
            style = Stroke(
                width = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/** One message line: the mark coloured by direction, the rest in muted mono. */
@Composable
private fun PulseLogRow(event: PulseEvent) {
    val markColor = when (event.direction) {
        PulseDirection.ACCEPTED -> PocketColors.Running
        PulseDirection.IN, PulseDirection.ROUND_TRIP -> PocketColors.Accent
        PulseDirection.OUT -> PocketColors.Faint
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = PulseText.mark(event),
            color = markColor,
            fontSize = 9.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = PulseText.body(event),
            color = if (event.direction == PulseDirection.ACCEPTED) PocketColors.Text else PocketColors.Muted,
            fontSize = 9.sp,
            fontFamily = Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A peer's live numbers, left of its `IN`/`OUT` mark: what it is sending this
 * phone and what the phone is sending it, with a dot that lights while it is
 * moving something.
 *
 * [now] is the pulse's own clock (monotonic), so the dot goes out by itself if
 * the sampler stops: a figure that stays lit after the node died would be the
 * one thing this card must not do.
 */
@Composable
internal fun PeerPulseCell(peer: PeerPulse?, now: Long) {
    val active = peer != null && peer.lastActivityAt > 0L &&
        now - peer.lastActivityAt in 0..ACTIVITY_WINDOW_MS
    val glow by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "peer-pulse"
    )
    val color by animateColorAsState(
        targetValue = lerp(PocketColors.Muted, PocketColors.Text, glow),
        animationSpec = tween(durationMillis = 450),
        label = "peer-pulse-text"
    )
    Text(
        text = "\u2193${formatRateShort(peer?.inBps ?: 0.0)} \u2191${formatRateShort(peer?.outBps ?: 0.0)}",
        color = color,
        fontSize = 9.sp,
        fontFamily = Mono,
        maxLines = 1
    )
}

/** The dot beside a peer's `IN`/`OUT` mark, lit for a moment after a packet. */
@Composable
internal fun PeerActivityDot(peer: PeerPulse?, now: Long, size: Int = 5) {
    val active = peer != null && peer.lastActivityAt > 0L &&
        now - peer.lastActivityAt in 0..ACTIVITY_WINDOW_MS
    val glow by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "peer-dot"
    )
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(lerp(PocketColors.Faint.copy(alpha = 0.35f), PocketColors.Running, glow))
    )
}
