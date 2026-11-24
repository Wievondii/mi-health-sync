package com.mihealthsync.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class WorkoutFileRef(val timestamp: Long, val zone: Int, val format: Int)
internal data class WorkoutPosition(val time: Long, val latitude: Double, val longitude: Double)
internal data class WorkoutSpeed(val time: Long, val metersPerSecond: Double)

internal fun workoutPositions(bytes: ByteArray, ref: WorkoutFileRef, start: Long, end: Long): List<WorkoutPosition> {
    val buffer = workoutBuffer(bytes, ref, 2) ?: return emptyList()
    if (!buffer.hasRemaining() || buffer.get().toInt() and 0xe0 != 0xe0) return emptyList()
    val pointSize = 18
    if (buffer.remaining() % pointSize != 0 || buffer.remaining() / pointSize > MAX_POINTS) return emptyList()
    val positions = mutableListOf<WorkoutPosition>()
    while (buffer.remaining() >= pointSize) {
        val time = buffer.int.toUInt().toLong()
        val longitude = buffer.float.toDouble()
        val latitude = buffer.float.toDouble()
        buffer.position(buffer.position() + 6)
        if (time in start..end && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (latitude != 0.0 || longitude != 0.0)) {
            positions += WorkoutPosition(time, latitude, longitude)
        }
    }
    return positions.distinctBy { it.time }.sortedBy { it.time }.takeIf { it.size >= 2 }.orEmpty()
}

internal fun workoutSpeeds(bytes: ByteArray, ref: WorkoutFileRef, start: Long, end: Long): List<WorkoutSpeed> {
    val running = ref.format == 22
    if (!running && ref.format != 23) return emptyList()
    val buffer = workoutBuffer(bytes, ref, if (running) 8 else 5) ?: return emptyList()
    // 文件头每个半字节标记一个字段。存在但无效的字段仍占用字节。
    val sizes = if (running) intArrayOf(1, 1, 1, 1, 1, 4, 1, 1, 2, 2, 2, 2, 2)
        else intArrayOf(1, 1, 1, 1, 2, 1)
    val validityBytes = if (running) 7 else 3
    if (buffer.remaining() < validityBytes) return emptyList()
    val validity = ByteArray(validityBytes).also { buffer.get(it) }
    val speedIndex = if (running) 8 else 4
    var pointSize = 0
    var speedOffset = -1
    for ((index, size) in sizes.withIndex()) {
        val bits = (validity[index / 2].toInt() ushr (if (index % 2 == 0) 4 else 0)) and 15
        if (bits and 8 == 0) continue
        if (index == speedIndex && bits and 4 != 0) speedOffset = pointSize
        pointSize += size
    }
    if (speedOffset < 0) return emptyList()
    val segmentHeader = if (running) 27 else 23
    val samples = mutableListOf<WorkoutSpeed>()
    var seen = 0L
    while (buffer.hasRemaining()) {
        if (buffer.remaining() < segmentHeader) return emptyList()
        val headerStart = buffer.position()
        val count = buffer.getInt(headerStart + 4).toUInt().toLong()
        val resume = buffer.getInt(headerStart + 8).toUInt().toLong()
        buffer.position(headerStart + segmentHeader)
        seen += count
        if (seen > MAX_POINTS || count * pointSize > buffer.remaining()) return emptyList()
        repeat(count.toInt()) { index ->
            val raw = buffer.getShort(buffer.position() + speedOffset).toUShort().toInt()
            buffer.position(buffer.position() + pointSize)
            val time = resume + index
            // 跑步文件记录秒/公里，骑行文件记录 0.1 公里/小时。
            val speed = if (running) 1000.0 / raw else raw / 36.0
            if (time in start..end && speed in 0.0..100.0) samples += WorkoutSpeed(time, speed)
        }
    }
    return samples.distinctBy { it.time }.sortedBy { it.time }
}

private const val MAX_POINTS = 100_000

private fun workoutBuffer(bytes: ByteArray, ref: WorkoutFileRef, version: Int): ByteBuffer? {
    if (bytes.size < 8) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.int.toUInt().toLong() != ref.timestamp || buffer.get().toInt() != ref.zone ||
        buffer.get().toInt() != version || buffer.get().toInt() != ref.format || buffer.get().toInt() != 0) {
        return null
    }
    return buffer
}
