package com.mihealthsync.app

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRulesTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")

    @Test fun firstSyncIncludesTodayForSamplesAndCompleteDaysForSteps() {
        assertEquals(Instant.parse("2026-11-17T16:00:00Z"), syncStart(now, 0, null, 7, 48, false))
        assertEquals(Instant.parse("2026-11-16T16:00:00Z"), syncStart(now, 0, null, 7, 48, true))
        val selected = Instant.parse("2026-11-22T16:00:00Z")
        assertEquals(selected, syncStart(now, selected.epochSecond, now.epochSecond, 7, 48, false))
    }

    @Test fun incrementalSyncOverlapsPastRecordsButCapsHistory() {
        val cursor = Instant.parse("2026-11-23T09:30:00Z")
        assertEquals(Instant.parse("2026-11-20T16:00:00Z"), syncStart(now, 0, cursor.epochSecond, 7, 48, false))
        assertEquals(now.minusSeconds(30L * 86400), syncStart(now, 0, now.minusSeconds(32L * 86400).epochSecond, 7, 48, false))
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
