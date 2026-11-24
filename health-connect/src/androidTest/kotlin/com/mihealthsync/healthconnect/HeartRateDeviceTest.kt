package com.mihealthsync.healthconnect

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class HealthConnectTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "心率写入验证，使用合成数据" })
    }
}

@RunWith(AndroidJUnit4::class)
class HeartRateDeviceTest {
    @Test fun repeatsUpdatesAndCleansUpSyntheticRecord() = runBlocking {
        check(Build.HARDWARE == "ranchu" && Build.PRODUCT.startsWith("sdk_gphone")) {
            "合成记录测试只允许在 Android 模拟器执行。"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(HealthConnectClient.SDK_AVAILABLE, HeartRateWriter.sdkStatus(context))
        val readPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
        val permissions = setOf(readPermission, HeartRateWriter.WRITE_PERMISSION)
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val activity = instrumentation.startActivitySync(
            Intent(context, HealthConnectTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        val client = HealthConnectClient.getOrCreate(context)
        val writer = HeartRateWriter(client)
        val start = Instant.now().minusSeconds(300)
        val end = start.plusSeconds(60)
        val sampleTime = start.plusSeconds(8)
        val series = HeartRateSeries(
            accountScope = "合成测试账号",
            sourceKey = "模拟器专用心率写入",
            version = 1,
            startTime = start,
            endTime = end,
            startZoneOffset = ZoneOffset.ofHours(8),
            endZoneOffset = ZoneOffset.ofHours(8),
            samples = listOf(HeartRateSample(sampleTime, 64)),
        )
        val clientId = requireNotNull(series.toHealthConnectRecord().metadata.clientRecordId)
        val read = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(1)),
            dataOriginFilter = setOf(DataOrigin(context.packageName)),
        )
        suspend fun deleteFixture() = client.deleteRecords(HeartRateRecord::class, emptyList(), listOf(clientId))
        try {
            assertTrue(client.permissionController.getGrantedPermissions().containsAll(permissions))
            deleteFixture()
            writer.insertBatch(listOf(series))
            writer.insertBatch(listOf(series))
            val duplicateCheck = client.readRecords(read).records.single()
            assertEquals(clientId, duplicateCheck.metadata.clientRecordId)
            assertEquals(sampleTime.toEpochMilli(), duplicateCheck.samples.single().time.toEpochMilli())
            assertEquals(64L, duplicateCheck.samples.single().beatsPerMinute)
            writer.insertBatch(listOf(series.copy(version = 2, samples = listOf(HeartRateSample(sampleTime, 66)))))
            val updated = client.readRecords(read).records.single()
            assertEquals(2L, updated.metadata.clientRecordVersion)
            assertEquals(66L, updated.samples.single().beatsPerMinute)
        } finally {
            try {
                deleteFixture()
                assertTrue(client.readRecords(read).records.isEmpty())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
