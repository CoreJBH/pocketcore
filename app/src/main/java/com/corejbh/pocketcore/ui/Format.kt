package com.corejbh.pocketcore.ui

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

private val integerFormat = NumberFormat.getIntegerInstance(Locale.US)
private val oneDecimalFormat = NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}

fun formatCount(value: Long): String = integerFormat.format(value)

fun formatPercent(fraction: Double): String {
    val percent = (fraction * 100.0).coerceIn(0.0, 100.0)
    return if (percent >= 99.995) "100.00%" else String.format(Locale.US, "%.2f%%", percent)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (value >= 100 || index == 0) {
        "${value.roundToLong()} ${units[index]}"
    } else {
        "${oneDecimalFormat.format(value)} ${units[index]}"
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "-"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

fun shortHash(hash: String): String = if (hash.length > 20) "${hash.take(10)}...${hash.takeLast(8)}" else hash

fun middleHash(hash: String, head: Int = 14, tail: Int = 10): String =
    if (hash.length <= head + tail + 3) hash else "${hash.take(head)}...${hash.takeLast(tail)}"

/** BTC amount with the trailing zeros of an 8-decimal number removed. */
fun formatBtc(amount: Double, withUnit: Boolean = true): String {
    val text = String.format(Locale.US, "%.8f", amount)
        .trimEnd('0')
        .let { if (it.endsWith('.')) "${it}0" else it }
    return if (withUnit) "$text BTC" else text
}

fun formatSats(amount: Double): String =
    formatCount(kotlin.math.round(amount * 100_000_000.0).toLong()) + " sat"

private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
    timeZone = java.util.TimeZone.getDefault()
}

fun formatDateTime(seconds: Long): String {
    if (seconds <= 0) return "-"
    return runCatching { dateFormat.format(java.util.Date(seconds * 1000L)) }.getOrDefault("-")
}

fun formatRelative(seconds: Long): String = when {
    seconds <= 0 -> "-"
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3_600}h"
    else -> "${seconds / 86_400}d"
}

fun formatFeeRate(btcPerKvB: Double): String {
    val sats = (btcPerKvB * 100_000.0).roundToLong()
    return "${formatCount(sats)} sat/vB"
}
