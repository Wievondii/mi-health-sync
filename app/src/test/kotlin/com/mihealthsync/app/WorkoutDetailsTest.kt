package com.mihealthsync.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutDetailsTest {
    @Test fun readsGpsV2InLongitudeLatitudeOrderAndRejectsAnUnmatchedHeader() {
        val reference = WorkoutFileRef(100, 32, 22)
        val buffer = file(reference, version = 2, size = 45).put(0xe0.toByte())
        buffer.putInt(102).putFloat(111f).putFloat(30f).putInt(0).putShort(0)
        buffer.putInt(104).putFloat(112f).putFloat(31f).putInt(0).putShort(0)
        assertEquals(listOf(WorkoutPosition(102, 30.0, 111.0), WorkoutPosition(104, 31.0, 112.0)),
            workoutPositions(buffer.array(), reference, 102, 105))
        assertTrue(workoutPositions(buffer.array(), reference.copy(timestamp = 101), 102, 105).isEmpty())
        assertTrue(workoutPositions(buffer.array().dropLast(1).toByteArray(), reference, 102, 105).isEmpty())
    }

    @Test fun decodesRideSpeedIncludingARealStopAndPreservesPauses() {
        val reference = WorkoutFileRef(100, 32, 23)
        val buffer = file(reference, version = 5, size = 63)
            .put(0).put(0).put(0xc0.toByte())
        rideSegment(buffer, count = 2, resume = 102)
        buffer.putShort(0).putShort(360)
        rideSegment(buffer, count = 1, resume = 110)
        buffer.putShort(180)
        assertEquals(listOf(WorkoutSpeed(102, 0.0), WorkoutSpeed(103, 10.0), WorkoutSpeed(110, 5.0)),
            workoutSpeeds(buffer.array(), reference, 100, 115))
    }

    @Test fun convertsRunPaceWithoutInventingSpeedForZeroPace() {
        val reference = WorkoutFileRef(100, 32, 22)
        val buffer = file(reference, version = 8, size = 46)
            .put(byteArrayOf(0, 0, 0, 0, 0xc0.toByte(), 0, 0))
        buffer.putFloat(0f).putInt(2).putInt(102).put(0).putInt(0).putInt(0).putInt(0).putShort(0)
        buffer.putShort(400).putShort(0)
        assertEquals(listOf(WorkoutSpeed(102, 2.5)), workoutSpeeds(buffer.array(), reference, 100, 108))
    }

    private fun file(reference: WorkoutFileRef, version: Int, size: Int) =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putInt(reference.timestamp.toInt())
            .put(reference.zone.toByte()).put(version.toByte()).put(reference.format.toByte()).put(0)

    private fun rideSegment(buffer: ByteBuffer, count: Int, resume: Int) {
        buffer.putFloat(0f).putInt(count).putInt(resume).put(0).putInt(0).putInt(0).putShort(0)
    }
}
