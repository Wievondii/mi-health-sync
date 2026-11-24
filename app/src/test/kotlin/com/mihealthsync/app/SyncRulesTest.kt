package com.mihealthsync.app

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRulesTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")

    @Test fun firstSyncIncludesTodayAndCanSelectOnlyToday() {
        assertEquals(Instant.parse("2026-11-17T16:00:00Z"), syncStart(now, 0, null, 7, 48))
        assertEquals(Instant.parse("2026-11-23T16:00:00Z"), syncStart(now, 0, null, 1, 48))
        val selected = Instant.parse("2026-11-22T16:00:00Z")
        assertEquals(selected, syncStart(now, selected.epochSecond, now.epochSecond, 7, 48))
    }

    @Test fun incrementalSyncOverlapsPastRecordsButCapsHistory() {
        val cursor = Instant.parse("2026-11-23T09:30:00Z")
        assertEquals(Instant.parse("2026-11-20T16:00:00Z"), syncStart(now, 0, cursor.epochSecond, 7, 48))
        assertEquals(now.minusSeconds(30L * 86400), syncStart(now, 0, now.minusSeconds(32L * 86400).epochSecond, 7, 48))
    }

    @Test fun todayUsesAnElapsedWindowAndMidnightFinalizesWithoutChangingHistoricalFingerprints() {
        val today = LocalDate.parse("2026-11-24")
        val start = Instant.parse("2026-11-23T16:00:00Z")
        val midnight = Instant.parse("2026-11-24T16:00:00Z")
        val partial = requireNotNull(dailyActivityWindow(today, now))
        assertEquals(DailyActivityWindow(start, now, false), partial)
        assertEquals("partial:1250", partial.revisionContent(1250L))
        assertEquals(partial.revisionContent(1250L),
            dailyActivityWindow(today, now.plusSeconds(60))?.revisionContent(1250L))
        val complete = requireNotNull(dailyActivityWindow(today, midnight.plusSeconds(100)))
        assertEquals(DailyActivityWindow(start, midnight, true), complete)
        assertEquals("1250", complete.revisionContent(1250L))
        assertEquals("95.0", complete.revisionContent(95.0))
        assertNull(dailyActivityWindow(today, start))
    }

    @Test fun crashReplayReusesVersionAndRevisionsAdvanceIt() {
        val first = requireNotNull(pendingRevision("synthetic-id", "same", null))
        assertEquals(0L, first.version)
        assertEquals(first, pendingRevision(first.id, "same", first))
        val applied = first.copy(applied = true, everApplied = true)
        assertNull(pendingRevision(applied.id, "same", applied))
        val revised = requireNotNull(pendingRevision(applied.id, "revised", applied))
        assertEquals(1L, revised.version)
        assertEquals(revised, pendingRevision(revised.id, "revised", revised))
    }

    @Test fun respectsRetryAfterSecondsAndHttpDate() {
        assertEquals(now.plusSeconds(120).toEpochMilli(), refillAt("120", now))
        assertEquals(now.plusSeconds(300).toEpochMilli(), refillAt("Tue, 24 Nov 2026 08:05:00 GMT", now))
        assertEquals(now.plusSeconds(60).toEpochMilli(), refillAt("无效", now))
    }
}
