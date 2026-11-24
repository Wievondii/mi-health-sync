package com.mihealthsync.app

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Velocity
import com.mihealthsync.cloud.MiCloud
import com.mihealthsync.healthconnect.HeartRateSample
import com.mihealthsync.healthconnect.HeartRateSeries
import com.mihealthsync.healthconnect.toHealthConnectRecord
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.json.JSONObject

internal val CHINA: ZoneId = ZoneId.of("Asia/Shanghai")
internal const val ROUTE_PERMISSION = "android.permission.health.WRITE_EXERCISE_ROUTE"
internal const val ROUTE_FINGERPRINT_PREFIX = "route:"

internal enum class Metric(val title: String, val permission: String) {
    HEART("心率", HealthPermission.getWritePermission(HeartRateRecord::class)),
    RESTING("静息心率", HealthPermission.getWritePermission(RestingHeartRateRecord::class)),
    OXYGEN("血氧", HealthPermission.getWritePermission(OxygenSaturationRecord::class)),
    STEPS("步数", HealthPermission.getWritePermission(StepsRecord::class)),
    SLEEP("睡眠", HealthPermission.getWritePermission(SleepSessionRecord::class)),
    EXERCISE("锻炼", HealthPermission.getWritePermission(ExerciseSessionRecord::class)),
    DISTANCE("锻炼距离", HealthPermission.getWritePermission(DistanceRecord::class)),
    ELEVATION("爬升高度", HealthPermission.getWritePermission(ElevationGainedRecord::class)),
    ACTIVE_CALORIES("活动热量", HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)),
    SPEED("锻炼速度", HealthPermission.getWritePermission(SpeedRecord::class)),
    TOTAL_CALORIES("锻炼总热量", HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)),
    WEIGHT("体重", HealthPermission.getWritePermission(WeightRecord::class));

    val completedDaysOnly get() = this == STEPS || this == ACTIVE_CALORIES
}

internal data class CloudRow(
    val time: Long, val revision: Long, val offset: ZoneOffset?, val value: JSONObject,
    val sourceId: String = "", val key: String = "",
)
internal data class CloudRows(val rows: List<CloudRow>, val rejected: Int)
internal data class MappedRecord(
    val id: String, val fingerprint: String, val preserveRoute: Boolean = false, val record: (Long) -> Record,
)
internal data class MappedBatch(val records: List<MappedRecord>, val rejected: Int, val latest: Long?)

internal class CloudMapper(private val cloud: MiCloud, private val account: MiCloud.Session) {
    private val sportPages = mutableMapOf<Pair<Instant, Instant>, CloudRows>()

    suspend fun fetch(metric: Metric, start: Instant, end: Instant, includeRoutes: Boolean = false): MappedBatch {
        val firstDay = start.atZone(CHINA).toLocalDate()
        val lastDay = end.minusNanos(1).atZone(CHINA).toLocalDate()
        return when (metric) {
            Metric.HEART -> heart(raw(MiCloud.Metric.HEART_RATE, start.minus(1, ChronoUnit.DAYS), end), start, end)
            Metric.OXYGEN -> {
                val from = start.minus(1, ChronoUnit.DAYS)
                val automatic = raw(MiCloud.Metric.SPO2, from, end)
                val manual = raw(MiCloud.Metric.SINGLE_SPO2, from, end)
                oxygen(CloudRows(automatic.rows + manual.rows, automatic.rejected + manual.rejected), start, end)
            }
            Metric.STEPS, Metric.ACTIVE_CALORIES -> {
                val completedDay = minOf(lastDay, end.atZone(CHINA).toLocalDate().minusDays(1))
                if (completedDay < firstDay) MappedBatch(emptyList(), 0, null)
                else {
                    val kind = if (metric == Metric.STEPS) MiCloud.Metric.STEPS else MiCloud.Metric.CALORIES_DAILY
                    val source = dayRows(kind, firstDay, completedDay)
                    if (metric == Metric.STEPS) steps(source, firstDay, completedDay)
                    else activeCalories(source, firstDay, completedDay)
                }
            }
            Metric.RESTING -> resting(dayRows(MiCloud.Metric.RESTING_HEART_RATE, firstDay, lastDay), firstDay, lastDay)
            Metric.SLEEP -> sleep(firstDay, lastDay, start, end)
            Metric.WEIGHT -> weight(raw(MiCloud.Metric.WEIGHT, start.minus(1, ChronoUnit.DAYS), end), start, end)
            Metric.EXERCISE, Metric.DISTANCE, Metric.ELEVATION, Metric.SPEED, Metric.TOTAL_CALORIES ->
                workouts(metric, start, end, includeRoutes)
        }
    }

    private suspend fun dayRows(kind: MiCloud.Metric, first: LocalDate, last: LocalDate) = raw(
        kind, first.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), last.atStartOfDay(ZoneOffset.UTC).toInstant(),
    )

    private suspend fun raw(kind: MiCloud.Metric, start: Instant, end: Instant): CloudRows =
        paged(end) { cursor -> cloud.fetch(account, kind, start.epochSecond, end.epochSecond, cursor) }

    private suspend fun paged(end: Instant, load: (String?) -> MiCloud.Page): CloudRows {
        val rows = mutableListOf<CloudRow>()
        val seen = mutableSetOf<String>()
        var rejected = 0
        var cursor: String? = null
        repeat(40) {
            kotlinx.coroutines.delay(1000)
            val page = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                load(cursor)
            }
            for (index in 0 until page.rows.length()) {
                val item = page.rows.optJSONObject(index)
                if (item?.optBoolean("deleted") == true) continue
                val value = item?.optString("value")?.let { runCatching { JSONObject(it) }.getOrNull() }
                val time = item?.whole("time")
                if (item == null || value == null || time == null || time !in 1..end.epochSecond) {
                    rejected++
                    continue
                }
                val offset = item.whole("zone_offset")?.takeIf { it in -64800..64800 }
                    ?.let { ZoneOffset.ofTotalSeconds(it.toInt()) }
                rows += CloudRow(time, item.whole("update_time") ?: 0, offset, value,
                    item.optString("sid"), item.optString("key"))
            }
            if (!page.hasMore) return CloudRows(rows, rejected)
            if (page.next.isBlank() || !seen.add(page.next)) throw IncompletePage()
            cursor = page.next
        }
        throw IncompletePage()
    }

    private fun heart(source: CloudRows, start: Instant, end: Instant): MappedBatch {
        val window = source.rows.inWindow(start, end)
        val usable = window.filter { it.value.whole("bpm") in 1L..300L && it.value.whole("time") == it.time }
        val buckets = usable.latestPerTime().groupBy { it.time / 60 }
        val records = buckets.map { (minute, rows) ->
            val from = Instant.ofEpochSecond(minute * 60)
            val ordered = rows.sortedBy { it.time }
            val input = HeartRateSeries(account.accountScope(), "minute:$minute", 0, from, from.plusSeconds(60),
                ordered.first().offset, ordered.last().offset,
                ordered.map { HeartRateSample(Instant.ofEpochSecond(it.time), it.value.whole("bpm")!!) })
            val id = requireNotNull(input.toHealthConnectRecord().metadata.clientRecordId)
            MappedRecord(id, fingerprint(ordered.joinToString { "${it.time}:${it.value.whole("bpm")}:${it.offset}" })) {
                input.copy(version = it).toHealthConnectRecord()
            }
        }
        return MappedBatch(records, source.rejected + window.size - usable.size, usable.maxOfOrNull { it.time })
    }

    private fun oxygen(source: CloudRows, start: Instant, end: Instant): MappedBatch {
        val window = source.rows.inWindow(start, end)
        val usable = window.filter { it.value.whole("spo2") in 1L..100L }
        val records = usable.latestPerTime().map { row ->
            val percent = row.value.whole("spo2")!!
            candidate(Metric.OXYGEN, row.time.toString(), "$percent:${row.offset}") { metadata ->
                OxygenSaturationRecord(Instant.ofEpochSecond(row.time), row.offset, Percentage(percent.toDouble()), metadata)
            }
        }
        return MappedBatch(records, source.rejected + window.size - usable.size, usable.maxOfOrNull { it.time })
    }

    private fun steps(source: CloudRows, first: LocalDate, last: LocalDate): MappedBatch {
        val rows = source.rows.inDays(first, last)
        val usable = rows.filter { it.value.whole("steps") in 1L..1_000_000L }
        val records = usable.latestPerTime().map { row ->
            val day = row.day()
            val start = day.atStartOfDay(CHINA)
            val end = day.plusDays(1).atStartOfDay(CHINA)
            val count = row.value.whole("steps")!!
            candidate(Metric.STEPS, day.toString(), count.toString()) { metadata ->
                StepsRecord(start.toInstant(), start.offset, end.toInstant(), end.offset, count, metadata)
            }
        }
        val zeroDays = rows.count { it.value.whole("steps") == 0L }
        val latestDayEnd = usable.maxOfOrNull { it.day().plusDays(1).atStartOfDay(CHINA).toEpochSecond() }
        return MappedBatch(records, source.rejected + rows.size - usable.size - zeroDays, latestDayEnd)
    }

    private fun resting(source: CloudRows, first: LocalDate, last: LocalDate): MappedBatch {
        val rows = source.rows.inDays(first, last)
        val usable = rows.filter { it.value.whole("bpm") in 1L..300L && it.value.whole("date_time") == it.time }
        val records = usable.latestPerTime().map { row ->
            val day = row.day()
            val offset = row.offset ?: day.atStartOfDay(CHINA).offset
            val dateMarker = day.atStartOfDay().toInstant(offset)
            val bpm = row.value.whole("bpm")!!
            candidate(Metric.RESTING, day.toString(), "$bpm:$offset") { metadata ->
                RestingHeartRateRecord(dateMarker, offset, bpm, metadata)
            }
        }
        val latestDay = usable.maxOfOrNull { row ->
            val day = row.day()
            day.atStartOfDay().toEpochSecond(row.offset ?: day.atStartOfDay(CHINA).offset)
        }
        return MappedBatch(records, source.rejected + rows.size - usable.size, latestDay)
    }

    private fun activeCalories(source: CloudRows, first: LocalDate, last: LocalDate): MappedBatch {
        val rows = source.rows.inDays(first, last).latestPerTime()
        var invalid = source.rejected
        var latest: Long? = null
        val records = rows.mapNotNull { row ->
            val kcal = row.value.number("calories")
            if (kcal == null || kcal !in 0.0..100_000.0) {
                invalid++
                return@mapNotNull null
            }
            if (kcal == 0.0) return@mapNotNull null
            val day = row.day()
            val start = day.atStartOfDay(CHINA)
            val end = day.plusDays(1).atStartOfDay(CHINA)
            latest = maxOf(latest ?: 0, end.toEpochSecond())
            candidate(Metric.ACTIVE_CALORIES, day.toString(), kcal.toString()) { metadata ->
                ActiveCaloriesBurnedRecord(start.toInstant(), start.offset, end.toInstant(), end.offset,
                    Energy.kilocalories(kcal), metadata)
            }
        }
        return MappedBatch(records, invalid, latest)
    }

    private fun weight(source: CloudRows, start: Instant, end: Instant): MappedBatch {
        val rows = source.rows.inWindow(start, end).latestPerTime()
        var invalid = source.rejected
        var latest: Long? = null
        val records = rows.mapNotNull { row ->
            val kg = row.value.number("weight")
            if (kg == null || kg <= 0 || kg > 1000) {
                invalid++
                return@mapNotNull null
            }
            latest = maxOf(latest ?: 0, row.time)
            candidate(Metric.WEIGHT, row.time.toString(), "${kg}:${row.offset}") { metadata ->
                WeightRecord(Instant.ofEpochSecond(row.time), row.offset, Mass.kilograms(kg), metadata)
            }
        }
        return MappedBatch(records, invalid, latest)
    }

    private suspend fun workouts(metric: Metric, start: Instant, end: Instant, includeRoutes: Boolean): MappedBatch {
        val bounds = start to end
        val source = sportPages[bounds] ?: paged(end) { cursor ->
            cloud.fetchSports(account, start.epochSecond - 1, end.epochSecond, cursor)
        }.also { sportPages[bounds] = it }
        val rows = source.rows.inWindow(start, end).groupBy { it.time to it.sourceId }.values
            .map { copies -> copies.maxBy { it.revision } }
        val records = mutableListOf<MappedRecord>()
        var invalid = source.rejected
        var latest: Long? = null
        var requestedDetails = 0
        for (row in rows) {
            val value = row.value
            val finish = value.whole("end_time")
            if (value.whole("start_time") != row.time || finish == null ||
                finish - row.time !in 1L..2_678_400L || finish > Instant.now().epochSecond) {
                invalid++
                continue
            }
            val from = Instant.ofEpochSecond(row.time)
            val to = Instant.ofEpochSecond(finish)
            val zone = value.quarterHourOffset() ?: row.offset
            val identity = "${row.time}|${row.sourceId}"
            val shape = "${finish}|${zone}"
            val reference = row.workoutFileRef()
            val wantsFile = metric == Metric.SPEED || (metric == Metric.EXERCISE && includeRoutes)
            val file = if (reference != null && wantsFile && requestedDetails < 10) {
                requestedDetails++
                val kind = if (metric == Metric.SPEED) MiCloud.SportFile.SAMPLES else MiCloud.SportFile.ROUTE
                fetchWorkoutFile(row.sourceId, reference, kind)
            } else null
            val before = records.size
            when (metric) {
                Metric.EXERCISE -> {
                    val type = exerciseType(value.whole("sport_type"), row.key)
                    val positions = if (file != null && reference != null) workoutPositions(file, reference, row.time, finish)
                        else emptyList()
                    val selected = if (positions.size <= 4000) positions else (0..3999).map { index ->
                        positions[(index * positions.lastIndex.toLong() / 3999).toInt()]
                    }
                    val route = selected.takeIf { it.isNotEmpty() }?.let { points ->
                        ExerciseRoute(points.map { point -> ExerciseRoute.Location(Instant.ofEpochSecond(point.time),
                            point.latitude, point.longitude) })
                    }
                    val entry = candidate(metric, identity, "$shape|$type|$selected") { metadata ->
                        ExerciseSessionRecord(startTime = from, startZoneOffset = zone, endTime = to,
                            endZoneOffset = zone, metadata = metadata, exerciseType = type, exerciseRoute = route)
                    }
                    records += if (route != null) entry.copy(fingerprint = ROUTE_FINGERPRINT_PREFIX + entry.fingerprint)
                        else entry.copy(preserveRoute = true)
                }
                Metric.DISTANCE, Metric.ELEVATION, Metric.TOTAL_CALORIES -> {
                    val quantity = workoutQuantity(metric, value) ?: continue
                    records += candidate(metric, identity, "$shape|$quantity") { metadata ->
                        when (metric) {
                            Metric.DISTANCE -> DistanceRecord(from, zone, to, zone, Length.meters(quantity), metadata)
                            Metric.ELEVATION -> ElevationGainedRecord(from, zone, to, zone, Length.meters(quantity), metadata)
                            else -> TotalCaloriesBurnedRecord(from, zone, to, zone, Energy.kilocalories(quantity), metadata)
                        }
                    }
                }
                Metric.SPEED -> {
                    if (file == null || reference == null) continue
                    for ((bucket, samples) in workoutSpeeds(file, reference, row.time, finish - 1).groupBy { it.time / 600 }) {
                        val first = Instant.ofEpochSecond(samples.first().time)
                        val last = Instant.ofEpochSecond(samples.last().time + 1)
                        records += candidate(metric, "$identity|$bucket", "$shape|$samples") { metadata ->
                            SpeedRecord(first, zone, last, zone, samples.map { sample ->
                                SpeedRecord.Sample(Instant.ofEpochSecond(sample.time), Velocity.metersPerSecond(sample.metersPerSecond))
                            }, metadata)
                        }
                    }
                }
                else -> error("不是锻炼指标")
            }
            if (records.size > before) latest = maxOf(latest ?: 0, finish)
        }
        return MappedBatch(records, invalid, latest)
    }

    private suspend fun fetchWorkoutFile(sourceId: String, ref: WorkoutFileRef, type: MiCloud.SportFile): ByteArray? {
        kotlinx.coroutines.delay(1000)
        return try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                cloud.fetchSportFile(account, sourceId, ref.timestamp, ref.zone, ref.format, type)
            }
        } catch (error: MiCloud.ProbeFailure) {
            if (error.status == 401 || error.status == 429 || error.status >= 500) throw error
            null
        } catch (error: IOException) {
            if (type == MiCloud.SportFile.SAMPLES) throw error
            null
        }
    }

    private suspend fun sleep(first: LocalDate, last: LocalDate, start: Instant, end: Instant): MappedBatch {
        val daily = dayRows(MiCloud.Metric.SLEEP_DAILY, first, last)
        val reported = mutableListOf<ReportedSleep>()
        var rejected = daily.rejected
        for (row in daily.rows.inDays(first, last).latestPerTime()) {
            val total = row.value.whole("total_duration")
            val segments = row.value.optJSONArray("segment_details")
            if (total == null || total < 0 || (total > 0 && (segments == null || segments.length() == 0))) {
                rejected++
                continue
            }
            if (segments == null) continue
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index)
                val window = segment?.sleepWindow()
                val duration = segment?.whole("duration")
                if (window == null || duration == null || duration !in 1..window.roundedMinutes) {
                    rejected++
                    continue
                }
                if (window.wake !in start.epochSecond until end.epochSecond) continue
                reported += ReportedSleep(window, duration, segment.sleepOffset() ?: row.offset, row.revision)
            }
        }
        if (reported.isEmpty()) return MappedBatch(emptyList(), rejected, null)
        val rawSource = raw(MiCloud.Metric.SLEEP, start.minus(1, ChronoUnit.DAYS), end.plus(1, ChronoUnit.DAYS))
        val timelines = rawSource.rows.filterNot { it.value.optBoolean("deleted") }.mapNotNull { row ->
            val window = row.value.sleepWindow() ?: return@mapNotNull null
            RawSleepTimeline(window, row.value.sleepOffset() ?: row.offset, readStages(row.value, window))
        }
        val sessions = reconcileSleep(reported, timelines)
        val records = sessions.map { session ->
            val window = session.report.window
            val stages = session.stages
            val duration = session.report.asleepMinutes
            val offset = session.offset
            val content = listOf(window.wake, offset, duration, stages.joinToString { "${it.startTime},${it.endTime},${it.stage}" })
                .joinToString("|")
            candidate(Metric.SLEEP, window.bedtime.toString(), content) { metadata ->
                SleepSessionRecord(Instant.ofEpochSecond(window.bedtime), offset, Instant.ofEpochSecond(window.wake),
                    offset, metadata = metadata, title = sleepTitle(duration), stages = stages)
            }
        }
        return MappedBatch(records, rejected + rawSource.rejected, sessions.maxOfOrNull { it.report.window.wake })
    }

    private fun readStages(value: JSONObject, window: SleepWindow): List<SleepSessionRecord.Stage> {
        val array = value.optJSONArray("items") ?: return emptyList()
        val stages = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val from = item.whole("start_time") ?: item.whole("start") ?: return@mapNotNull null
            val to = item.whole("end_time") ?: item.whole("end") ?: return@mapNotNull null
            if (from < window.bedtime || to > window.wake || to <= from) return@mapNotNull null
            val kind = when (item.whole("state")) {
                2L -> SleepSessionRecord.STAGE_TYPE_DEEP
                3L -> SleepSessionRecord.STAGE_TYPE_LIGHT
                4L -> SleepSessionRecord.STAGE_TYPE_REM
                5L -> SleepSessionRecord.STAGE_TYPE_AWAKE
                else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
            }
            SleepSessionRecord.Stage(Instant.ofEpochSecond(from), Instant.ofEpochSecond(to), kind)
        }.distinct().sortedBy { it.startTime }
        return if (stages.zipWithNext().any { (left, right) -> left.endTime > right.startTime }) emptyList() else stages
    }

    private fun candidate(metric: Metric, key: String, content: String, build: (Metadata) -> Record): MappedRecord {
        val id = "mi-${metric.name.lowercase()}-" + fingerprint("${account.accountScope()}|$key")
        return MappedRecord(id, fingerprint(content)) { version -> build(Metadata.unknownRecordingMethod(id, version)) }
    }

}

internal class IncompletePage : Exception("云端分页未完整返回，未提交同步进度。")

private fun List<CloudRow>.inWindow(start: Instant, end: Instant) = filter { it.time in start.epochSecond until end.epochSecond }
private fun List<CloudRow>.inDays(first: LocalDate, last: LocalDate) = filter { it.day() in first..last }
private fun List<CloudRow>.latestPerTime() = groupBy { it.time }.values.map { rows -> rows.maxBy { it.revision } }
private fun CloudRow.day() = Instant.ofEpochSecond(time).atZone(ZoneOffset.UTC).toLocalDate()

private fun JSONObject.sleepWindow(): SleepWindow? {
    val bedtime = whole("bedtime") ?: return null
    val wake = whole("wake_up_time") ?: return null
    if (bedtime <= 0 || wake <= bedtime || wake - bedtime > 86_400L) return null
    return SleepWindow(bedtime, wake)
}

private fun JSONObject.sleepOffset() = quarterHourOffset()

private fun JSONObject.quarterHourOffset() = whole("timezone")?.takeIf { it in -72..72 }
    ?.let { ZoneOffset.ofTotalSeconds(it.toInt() * 900) }

private fun CloudRow.workoutFileRef(): WorkoutFileRef? {
    val timestamp = value.whole("time")?.takeIf { it in 1L..0xffffffffL } ?: return null
    val zone = value.whole("timezone")?.takeIf { it in -72..72 } ?: return null
    val format = value.whole("proto_type")?.takeIf { it == 22L || it == 23L } ?: return null
    if (sourceId.isBlank()) return null
    return WorkoutFileRef(timestamp, zone.toInt(), format.toInt())
}

private fun workoutQuantity(metric: Metric, value: JSONObject): Double? {
    val field = when (metric) {
        Metric.DISTANCE -> "distance"
        Metric.ELEVATION -> "rise_height"
        Metric.TOTAL_CALORIES -> "total_cal"
        else -> return null
    }
    val amount = value.number(field)?.takeIf { it > 0 } ?: return null
    val max = when (metric) {
        Metric.DISTANCE -> 1_000_000.0
        Metric.ELEVATION -> 100_000.0
        else -> 100_000.0
    }
    return amount.takeIf { it <= max }
}

internal fun exerciseType(code: Long?, name: String): Int = when (code) {
    1L -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    6L -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    300L -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
    308L -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    else -> when (name) {
        "outdoor_running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "outdoor_riding", "outdoor_biking" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "outdoor_walking" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "pool_swimming" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }
}

private fun JSONObject.number(name: String): Double? = (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun JSONObject.whole(name: String): Long? = when (val value = opt(name)) {
    is Int -> value.toLong()
    is Long -> value
    is String -> value.toLongOrNull()
    else -> null
}

internal fun fingerprint(text: String) = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
