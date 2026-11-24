package com.mihealthsync.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal fun syncStart(end: Instant, from: Long, cursor: Long?, firstDays: Int, overlapHours: Int): Instant {
    if (from > 0) return Instant.ofEpochSecond(from)
    if (cursor == null) {
        val last = end.atZone(CHINA).toLocalDate()
        return last.minusDays(firstDays.toLong() - 1).atStartOfDay(CHINA).toInstant()
    }
    val overlap = Instant.ofEpochSecond(cursor).minus(overlapHours.toLong(), ChronoUnit.HOURS)
        .atZone(CHINA).toLocalDate().atStartOfDay(CHINA).toInstant()
    return maxOf(end.minus(30, ChronoUnit.DAYS), overlap)
}

internal data class DailyActivityWindow(val start: Instant, val end: Instant, val complete: Boolean) {
    // 当日累计值不变时跳过重复写入，过零点后仍会用同一标识补齐全天区间。
    fun revisionContent(amount: Number): String = if (complete) amount.toString() else "partial:$amount"
}

internal fun dailyActivityWindow(day: LocalDate, through: Instant): DailyActivityWindow? {
    val start = day.atStartOfDay(CHINA).toInstant()
    if (through <= start) return null
    val midnight = day.plusDays(1).atStartOfDay(CHINA).toInstant()
    return DailyActivityWindow(start, minOf(through, midnight), through >= midnight)
}

internal fun pendingRevision(id: String, content: String, previous: RecordIndex?): RecordIndex? {
    if (previous?.fingerprint == content && previous.applied) return null
    val version = when {
        previous == null -> 0L
        previous.fingerprint == content -> previous.version
        else -> previous.version + 1
    }
    return RecordIndex(id, content, version, applied = false, everApplied = previous?.everApplied ?: false)
}

internal fun refillAt(header: String?, now: Instant): Long {
    val value = header.orEmpty().trim()
    val seconds = value.toLongOrNull()?.takeIf { it >= 0 }
    val requested = if (seconds != null) {
        runCatching { now.plusSeconds(seconds).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
    } else {
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(now.plusSeconds(60).toEpochMilli())
    }
    return maxOf(requested, now.plusSeconds(30).toEpochMilli())
}
