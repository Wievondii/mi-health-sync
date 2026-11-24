package com.mihealthsync.healthconnect

import android.content.Intent
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutDeviceTest {
    @Test fun writesWorkoutRouteQuantitiesAndWeight() = runBlocking {
        check(Build.HARDWARE == "ranchu" && Build.PRODUCT.startsWith("sdk_gphone")) {
            "合成记录测试只允许在 Android 模拟器执行。"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val start = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS)
        val end = start.plusSeconds(120)
        val zone = ZoneOffset.ofHours(8)
        val route = ExerciseRoute(listOf(
            ExerciseRoute.Location(start.plusSeconds(3), 10.0, 20.0),
            ExerciseRoute.Location(start.plusSeconds(29), 10.001, 20.001),
        ))
        val prefix = "模拟器锻炼验证-${start.epochSecond}"
        fun meta(kind: String) = Metadata.unknownRecordingMethod("$prefix-$kind")
        val exercise = ExerciseSessionRecord(startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
            metadata = meta("exercise"), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, exerciseRoute = route)
        val records = listOf(
            exercise,
            DistanceRecord(start, zone, end, zone, Length.meters(105.0), meta("distance")),
            ElevationGainedRecord(start, zone, end, zone, Length.meters(5.0), meta("elevation")),
            ActiveCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(10.0), meta("active")),
            TotalCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(14.0), meta("total")),
            SpeedRecord(start, zone, end, zone, listOf(SpeedRecord.Sample(start, Velocity.metersPerSecond(3.0))), meta("speed")),
            WeightRecord(start, zone, Mass.kilograms(68.5), meta("weight")),
        )
        val requested = records.map { HealthPermission.getWritePermission(it::class) } +
            HealthPermission.getReadPermission(ExerciseSessionRecord::class) + "android.permission.health.WRITE_EXERCISE_ROUTE"
        requested.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val activity = instrumentation.startActivitySync(
            Intent(context, HealthConnectTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        val client = HealthConnectClient.getOrCreate(context)
        try {
            val ids = client.insertRecords(records).recordIdsList
            assertEquals(records.size, ids.size)
            val saved = client.readRecord(ExerciseSessionRecord::class, ids.first()).record
            assertEquals(start, saved.startTime)
            assertEquals(route, (saved.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute)
        } finally {
            try {
                for (record in records) {
                    client.deleteRecords(record::class, emptyList(), listOf(requireNotNull(record.metadata.clientRecordId)))
                }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
