package com.mihealthsync.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

data class HeartRateSample(val time: Instant, val beatsPerMinute: Long)

/** 来源标识应独立于读数及同步时间。版本由调用方按来源更新维护。 */
data class HeartRateSeries(
    val accountScope: String,
    val sourceKey: String,
    val version: Long,
    val startTime: Instant,
    val endTime: Instant,
    val startZoneOffset: ZoneOffset?,
    val endZoneOffset: ZoneOffset?,
    val samples: List<HeartRateSample>,
)

class HeartRateWriter(private val client: HealthConnectClient) {
    /** 单批最多 100 条。只有 SDK 成功返回后，调用方才应提交云端进度。 */
    suspend fun insertBatch(series: List<HeartRateSeries>): List<String> {
        require(series.size <= MAX_BATCH_SIZE) { "单批最多写入 $MAX_BATCH_SIZE 条心率记录。" }
        if (series.isEmpty()) return emptyList()
        val records = series.map(HeartRateSeries::toHealthConnectRecord)
        val clientIds = records.map { it.metadata.clientRecordId }
        require(clientIds.toSet().size == clientIds.size) { "单批包含重复的心率记录标识。" }
        if (WRITE_PERMISSION !in client.permissionController.getGrantedPermissions()) {
            throw SecurityException("缺少 Health Connect 心率写入权限。")
        }
        return client.insertRecords(records).recordIdsList
    }

    companion object {
        const val MAX_BATCH_SIZE = 100
        val WRITE_PERMISSION = HealthPermission.getWritePermission(HeartRateRecord::class)

        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)
    }
}

fun HeartRateSeries.toHealthConnectRecord(): HeartRateRecord {
    require(accountScope.isNotBlank() && sourceKey.isNotBlank()) { "缺少稳定的账号或心率来源标识。" }
    require(version >= 0) { "心率记录版本必须为非负数。" }
    require(startTime < endTime) { "心率记录的结束时间必须晚于开始时间。" }
    require(samples.isNotEmpty()) { "心率记录至少要有一个有效采样。" }
    val ordered = samples.sortedBy { it.time }
    require(ordered.zipWithNext().none { (first, second) -> first.time == second.time }) {
        "同一心率记录中出现重复采样时间。"
    }
    val mapped = ordered.map { sample ->
        require(sample.time in startTime..endTime) { "采样时刻超出心率记录时间范围。" }
        require(sample.beatsPerMinute in 1L..300L) { "心率读数超出 Health Connect 支持范围。" }
        HeartRateRecord.Sample(sample.time, sample.beatsPerMinute)
    }
    val naturalKey = "${accountScope.length}:$accountScope${sourceKey.length}:$sourceKey"
    val hash = MessageDigest.getInstance("SHA-256").digest(naturalKey.toByteArray(Charsets.UTF_8))
    val id = "hr-" + hash.joinToString("") { "%02x".format(it) }
    return HeartRateRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        samples = mapped,
        metadata = Metadata.unknownRecordingMethod(clientRecordId = id, clientRecordVersion = version),
    )
}
