package com.mihealthsync.app

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepMappingTest {
    private val night = SleepWindow(epoch("2026-11-23T16:30:00Z"), epoch("2026-11-23T23:45:00Z"))
    private val nap = SleepWindow(epoch("2026-11-24T05:00:00Z"), epoch("2026-11-24T05:35:00Z"))
    private val offset = ZoneOffset.ofHours(8)

    @Test fun dailySessionsDefineCountAndKeepCrossMidnightTimes() {
        val orphan = SleepWindow(night.wake, night.wake + 600)
        val deep = SleepSessionRecord.Stage(Instant.ofEpochSecond(night.bedtime), Instant.ofEpochSecond(night.bedtime + 3600),
            SleepSessionRecord.STAGE_TYPE_DEEP)

        val result = reconcileSleep(
            listOf(ReportedSleep(night, 410, offset, 1), ReportedSleep(nap, 35, offset, 1)),
            listOf(RawSleepTimeline(orphan, offset, emptyList()), RawSleepTimeline(night, offset, listOf(deep))),
        )

        assertEquals(listOf(night, nap), result.map { it.report.window })
        assertEquals(listOf(deep), result.first().stages)
        assertTrue(result.last().stages.isEmpty())
        assertEquals(435L, result.first().report.window.roundedMinutes)
        assertEquals("实际睡眠 6 小时 50 分钟", sleepTitle(result.first().report.asleepMinutes))
    }

    @Test fun onlyExactlyMatchingTimelinesAreAttachedAndNewerOverlappingReportWins() {
        val revised = SleepWindow(night.bedtime + 120, night.wake)
        val deep = SleepSessionRecord.Stage(Instant.ofEpochSecond(night.bedtime), Instant.ofEpochSecond(night.bedtime + 3600),
            SleepSessionRecord.STAGE_TYPE_DEEP)

        val result = reconcileSleep(
            listOf(ReportedSleep(night, 410, offset, 1), ReportedSleep(revised, 408, null, 2)),
            listOf(RawSleepTimeline(night, offset, listOf(deep))),
        )

        assertEquals(revised, result.single().report.window)
        assertTrue(result.single().stages.isEmpty())
    }

    private fun epoch(value: String) = Instant.parse(value).epochSecond
}
