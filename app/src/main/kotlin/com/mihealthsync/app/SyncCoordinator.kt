package com.mihealthsync.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.health.connect.client.HealthConnectClient
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mihealthsync.cloud.MiCloud
import com.mihealthsync.healthconnect.HeartRateWriter
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MANUAL_WORK = "mi-health-sync-manual"
internal const val PERIODIC_WORK = "mi-health-sync-periodic"
internal const val RETRY_WORK = "mi-health-sync-rate-limit"
internal const val WORK_TAG = "mi-health-sync"
internal const val ROUTE_EVENT = "EXERCISE_ROUTE"
private const val MANUAL = "manual"
private const val FROM = "from"
private const val THROUGH = "through"

class SyncApplication : Application() {
    internal val repository by lazy { SyncCoordinator(this) }
}

internal class SyncCoordinator(private val app: Application) {
    val settings = SyncSettings(app)
    private val sessionStore by lazy { SessionStore(app) }
    private val db by lazy {
        Room.databaseBuilder(app, SyncDatabase::class.java, "mi-sync-index")
            .addMigrations(SyncDatabase.TIMES_MIGRATION).build()
    }
    private val dao get() = db.syncDao()
    private val manager get() = WorkManager.getInstance(app)
    private val lock = Mutex()
    val connected = MutableStateFlow(false)
    val events get() = dao.events()
    val progress = MutableStateFlow<String?>(null)
    val work get() = manager.getWorkInfosByTagFlow(WORK_TAG)

    suspend fun refreshSession() { connected.value = loadSession() != null }

    suspend fun connect(session: MiCloud.Session) = lock.withLock {
        withContext(Dispatchers.IO) { sessionStore.save(session) }
        connected.value = true
        reschedule()
    }

    suspend fun signOut() {
        manager.cancelAllWorkByTag(WORK_TAG)
        lock.withLock {
            withContext(Dispatchers.IO) { sessionStore.clear() }
            connected.value = false
        }
    }

    suspend fun clearDiagnostics() = lock.withLock {
        db.withTransaction {
            dao.clearEvents()
            dao.clearCursors()
        }
    }

    suspend fun reschedule() {
        val state = settings.current()
        if (!state.automatic || !connected.value) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(state.interval.toLong(), TimeUnit.MINUTES)
            .setConstraints(network(state.wifiOnly)).setInitialDelay(state.interval.toLong(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).addTag(PERIODIC_WORK).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelSync() {
        manager.cancelUniqueWork(MANUAL_WORK)
        manager.cancelUniqueWork(RETRY_WORK)
        manager.cancelUniqueWork(PERIODIC_WORK)
    }

    fun enqueue(from: Instant? = null, through: Instant? = null) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(MANUAL to true, FROM to (from?.epochSecond ?: 0), THROUGH to (through?.epochSecond ?: 0)))
            .setConstraints(network(false)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG).addTag(MANUAL_WORK).build()
        manager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun runSync(manual: Boolean, from: Long, through: Long, runAttempt: Int): SyncOutcome = lock.withLock {
        val state = settings.current()
        if (!manual && !state.automatic) return@withLock SyncOutcome.DONE
        val session = loadSession() ?: return@withLock logGeneral("请先扫码登录")
        connected.value = true
        if (state.wifiOnly && !isWifi()) return@withLock logGeneral("等待 Wi-Fi")
        if (state.retryUntilMillis > System.currentTimeMillis()) {
            deferredRateLimit(manual, from, through, state.retryUntilMillis)
            return@withLock logGeneral("云端限流，等待重试")
        }
        if (HeartRateWriter.sdkStatus(app) != HealthConnectClient.SDK_AVAILABLE) {
            return@withLock logGeneral("Health Connect 当前不可用")
        }
        val client = HealthConnectClient.getOrCreate(app)
        val end = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val queryEnd = if (through > 0) minOf(end, Instant.ofEpochSecond(through)) else end
        val mapper = CloudMapper(MiCloud(), session)
        var retry = false
        try {
            for (metric in state.enabled.sortedBy { it.ordinal }) {
                currentCoroutineContext().ensureActive()
                val permissions = client.permissionController.getGrantedPermissions()
                if (metric.permission !in permissions) {
                    record(metric, "未授权写入")
                    continue
                }
                progress.value = "同步${metric.title}"
                val previous = dao.cursor(session.accountScope(), metric.name)
                val queryStart = syncStart(queryEnd, from, previous, state.firstDays, state.overlapHours,
                    completedDaysOnly = metric.completedDaysOnly)
                if (queryStart >= queryEnd) continue
                try {
                    val includeRoutes = metric == Metric.EXERCISE && ROUTE_PERMISSION in permissions
                    val batch = mapper.fetch(metric, queryStart, queryEnd, includeRoutes)
                    val pulled = System.currentTimeMillis()
                    val batchSize = when {
                        includeRoutes -> 1
                        metric == Metric.SPEED -> 15
                        else -> HeartRateWriter.MAX_BATCH_SIZE
                    }
                    val counts = write(client, batch, batchSize)
                    val completed = System.currentTimeMillis()
                    db.withTransaction {
                        if (from == 0L && through == 0L) {
                            dao.saveCursor(SyncCursor(session.accountScope(), metric.name, queryEnd.epochSecond))
                        }
                        dao.append(SyncEvent(timeMillis = completed, metric = metric.name,
                            status = if (counts.total == 0) "云端暂无可写数据" else "已同步",
                            added = counts.added, updated = counts.updated, skipped = counts.skipped,
                            rejected = counts.rejected, latestEpochSeconds = counts.latest, pulledAtMillis = pulled,
                            writtenAtMillis = completed.takeIf { counts.added + counts.updated > 0 }))
                        if (includeRoutes) {
                            val routes = batch.records.count { it.fingerprint.startsWith(ROUTE_FINGERPRINT_PREFIX) }
                            val status = if (routes > 0) "已同步或核对 ${routes} 条路线" else "暂无可写路线"
                            dao.append(SyncEvent(timeMillis = completed, metric = ROUTE_EVENT, status = status))
                        }
                        dao.pruneEvents()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MiCloud.ProbeFailure) {
                    record(metric, error.message ?: "云端读取失败")
                    when (error.status) {
                        401 -> {
                            withContext(Dispatchers.IO) { sessionStore.clear() }
                            connected.value = false
                            manager.cancelUniqueWork(PERIODIC_WORK)
                            return@withLock logGeneral("小米会话已过期，请重新扫码")
                        }
                        429 -> {
                            val retryAt = refillAt(error.retryAfter, Instant.now())
                            settings.update { it.copy(retryUntilMillis = retryAt) }
                            if (runAttempt < 3) deferredRateLimit(manual, from, through, retryAt, runAttempt + 1)
                            return@withLock SyncOutcome.DONE
                        }
                        in 500..599 -> retry = true
                    }
                } catch (error: IOException) {
                    record(metric, "网络或 Health Connect 暂不可用")
                    retry = true
                } catch (_: SecurityException) {
                    record(metric, "写入权限已撤销")
                } catch (_: IncompletePage) {
                    record(metric, "分页不完整，下次重试")
                    retry = true
                } catch (_: Exception) {
                    record(metric, "数据解析或写入失败")
                }
            }
            if (retry && runAttempt < 3) SyncOutcome.RETRY else SyncOutcome.DONE
        } finally {
            progress.value = null
        }
    }

    private suspend fun write(client: HealthConnectClient, batch: MappedBatch, batchSize: Int): WriteCounts {
        var added = 0
        var updated = 0
        var skipped = 0
        for (group in batch.records.chunked(batchSize)) {
            currentCoroutineContext().ensureActive()
            val pending = db.withTransaction {
                val stored = dao.indices(group.map { it.id }).associateBy { it.id }
                group.mapNotNull { entry ->
                    val previous = stored[entry.id]
                    if (entry.preserveRoute && previous?.fingerprint?.startsWith(ROUTE_FINGERPRINT_PREFIX) == true) {
                        return@mapNotNull null
                    }
                    val reserved = pendingRevision(entry.id, entry.fingerprint, previous) ?: return@mapNotNull null
                    entry.record(reserved.version) to reserved
                }.also { if (it.isNotEmpty()) dao.reserve(it.map { (_, row) -> row }) }
            }
            skipped += group.size - pending.size
            if (pending.isEmpty()) continue
            client.insertRecords(pending.map { it.first })
            dao.markApplied(pending.map { it.second.id })
            val changes = pending.count { it.second.everApplied }
            updated += changes
            added += pending.size - changes
        }
        return WriteCounts(batch.records.size, added, updated, skipped, batch.rejected, batch.latest)
    }

    private suspend fun loadSession() = withContext(Dispatchers.IO) { sessionStore.load() }

    private suspend fun record(metric: Metric?, status: String) = db.withTransaction {
        dao.append(SyncEvent(timeMillis = System.currentTimeMillis(), metric = metric?.name ?: "", status = status))
        dao.pruneEvents()
    }

    private suspend fun logGeneral(status: String): SyncOutcome {
        record(null, status)
        return SyncOutcome.DONE
    }

    private fun deferredRateLimit(manual: Boolean, from: Long, through: Long, time: Long, attempt: Int = 0) {
        val wait = (time - System.currentTimeMillis()).coerceAtLeast(1000)
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network(false))
            .setInitialDelay(wait, TimeUnit.MILLISECONDS).addTag(WORK_TAG).addTag(RETRY_WORK)
            .setInputData(workDataOf(MANUAL to manual, FROM to from, THROUGH to through, "rate_attempt" to attempt)).build()
        manager.enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun isWifi(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun network(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

}

internal enum class SyncOutcome { DONE, RETRY }

private data class WriteCounts(val total: Int, val added: Int, val updated: Int, val skipped: Int, val rejected: Int, val latest: Long?)

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SyncApplication
        val outcome = app.repository.runSync(inputData.getBoolean(MANUAL, false), inputData.getLong(FROM, 0),
            inputData.getLong(THROUGH, 0), runAttemptCount + inputData.getInt("rate_attempt", 0))
        return if (outcome == SyncOutcome.RETRY) Result.retry() else Result.success()
    }
}
