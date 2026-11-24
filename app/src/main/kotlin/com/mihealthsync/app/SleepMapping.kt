package com.mihealthsync.app

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.ZoneOffset

internal data class SleepWindow(val bedtime: Long, val wake: Long) {
    val roundedMinutes get() = (wake - bedtime + 59) / 60

    fun overlaps(other: SleepWindow) = bedtime < other.wake && other.bedtime < wake
}

internal data class ReportedSleep(val window: SleepWindow, val asleepMinutes: Long, val offset: ZoneOffset?, val revision: Long)

internal data class RawSleepTimeline(val window: SleepWindow, val offset: ZoneOffset?, val stages: List<SleepSessionRecord.Stage>)

internal data class ReconciledSleep(val report: ReportedSleep, val offset: ZoneOffset?, val stages: List<SleepSessionRecord.Stage>)

internal fun reconcileSleep(reports: List<ReportedSleep>, timelines: List<RawSleepTimeline>): List<ReconciledSleep> {
    val timelineByWindow = mutableMapOf<SleepWindow, RawSleepTimeline>()
    for (timeline in timelines) {
        val existing = timelineByWindow[timeline.window]
        if (existing == null || (existing.stages.isEmpty() && timeline.stages.isNotEmpty())) {
            timelineByWindow[timeline.window] = timeline
        }
    }
    val selected = mutableListOf<ReconciledSleep>()
    val ordered = reports.sortedWith(compareByDescending<ReportedSleep> { it.revision }
        .thenByDescending { it.asleepMinutes }.thenBy { it.window.bedtime })
    for (report in ordered) {
        if (selected.any { it.report.window.overlaps(report.window) }) continue
        val timeline = timelineByWindow[report.window]
        selected += ReconciledSleep(report, report.offset ?: timeline?.offset, timeline?.stages.orEmpty())
    }
    return selected.sortedBy { it.report.window.bedtime }
}

internal fun sleepTitle(minutes: Long): String = when {
    minutes < 60 -> "实际睡眠 ${minutes} 分钟"
    minutes % 60 == 0L -> "实际睡眠 ${minutes / 60} 小时"
    else -> "实际睡眠 ${minutes / 60} 小时 ${minutes % 60} 分钟"
}
