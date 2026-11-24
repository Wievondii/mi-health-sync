package com.mihealthsync.healthconnect

import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HeartRateWriterTest {
    private val start = Instant.parse("2026-01-01T15:59:00Z")
    private val first = HeartRateSample(start.plusSeconds(2), 61)
    private val second = HeartRateSample(start.plusSeconds(70), 62)
    private val series = HeartRateSeries(
        accountScope = "合成测试账号",
        sourceKey = "合成测试记录",
        version = 3,
        startTime = start,
        endTime = start.plusSeconds(120),
        startZoneOffset = ZoneOffset.ofHours(8),
        endZoneOffset = ZoneOffset.ofHours(8),
        samples = listOf(second, first),
    )

    @Test fun preservesSamplesAndOffsetsWithoutInventingDevice() {
        val record = series.toHealthConnectRecord()
        assertEquals(start, record.startTime)
        assertEquals(series.endTime, record.endTime)
        assertEquals(ZoneOffset.ofHours(8), record.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(8), record.endZoneOffset)
        assertEquals(listOf(first.time, second.time), record.samples.map { it.time })
        assertEquals(listOf(61L, 62L), record.samples.map { it.beatsPerMinute })
        assertNull(record.metadata.device)
        assertEquals(Metadata.RECORDING_METHOD_UNKNOWN, record.metadata.recordingMethod)
    }

    @Test fun replacementKeepsIdentityAndCarriesNewVersion() {
        val record = series.toHealthConnectRecord()
        val changed = series.copy(version = 4, samples = listOf(first.copy(beatsPerMinute = 63)))
            .toHealthConnectRecord()
        assertEquals(record.metadata.clientRecordId, changed.metadata.clientRecordId)
        assertEquals(4L, changed.metadata.clientRecordVersion)
        assertNotEquals(record.metadata.clientRecordId, series.copy(accountScope = "其他测试账号")
            .toHealthConnectRecord().metadata.clientRecordId)
        assertNotEquals(record.metadata.clientRecordId, series.copy(sourceKey = "其他测试记录")
            .toHealthConnectRecord().metadata.clientRecordId)
    }

    @Test fun rejectsInvalidSamplesAndIntervals() {
        assertThrows(IllegalArgumentException::class.java) {
            series.copy(endTime = start).toHealthConnectRecord()
        }
        assertThrows(IllegalArgumentException::class.java) {
            series.copy(samples = listOf(first.copy(time = start.minusSeconds(1)))).toHealthConnectRecord()
        }
        assertThrows(IllegalArgumentException::class.java) {
            series.copy(samples = listOf(first.copy(beatsPerMinute = 301))).toHealthConnectRecord()
        }
        assertThrows(IllegalArgumentException::class.java) {
            series.copy(samples = listOf(first, first)).toHealthConnectRecord()
        }
    }
}
