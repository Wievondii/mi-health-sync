package com.mihealthsync.app

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal fun syncStart(end: Instant, from: Long, cursor: Long?, firstDays: Int, overlapHours: Int,
                       completedDaysOnly: Boolean): Instant {
    if (from > 0) return Instant.ofEpochSecond(from)
    if (cursor == null) {
        val last = end.atZone(CHINA).toLocalDate()
        val daysBefore = if (completedDaysOnly) firstDays else firstDays - 1
        return last.minusDays(daysBefore.toLong()).atStartOfDay(CHINA).toInstant()
    }
    val overlap = Instant.ofEpochSecond(cursor).minus(overlapHours.toLong(), ChronoUnit.HOURS)
        .atZone(CHINA).toLocalDate().atStartOfDay(CHINA).toInstant()
    return maxOf(end.minus(30, ChronoUnit.DAYS), overlap)
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
