package com.mihealthsync.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mihealthsync.cloud.MiCloud
import com.mihealthsync.healthconnect.HeartRateWriter
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repository = (app as SyncApplication).repository
    val settings = repository.settings.values.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val permitted = MutableStateFlow<Set<String>>(emptySet())
    val healthStatus = MutableStateFlow(HealthConnectClient.SDK_UNAVAILABLE)
    val qr = MutableStateFlow<Bitmap?>(null)
    val approvalPage = MutableStateFlow<Uri?>(null)
    val authenticating = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            repository.refreshSession()
            repository.reschedule()
        }
    }

    fun refreshLogin() = viewModelScope.launch { repository.refreshSession() }

    fun openRelogin() = viewModelScope.launch {
        repository.refreshSession()
        if (repository.accountState.value == AccountState.EXPIRED) login()
    }

    fun refreshHealth() = viewModelScope.launch {
        val app: Application = getApplication()
        val status = HeartRateWriter.sdkStatus(app)
        healthStatus.value = status
        permitted.value = if (status == HealthConnectClient.SDK_AVAILABLE) {
            try { HealthConnectClient.getOrCreate(app).permissionController.getGrantedPermissions() }
            catch (_: IOException) { emptySet() }
            catch (_: SecurityException) { emptySet() }
        } else emptySet()
    }

    fun login() {
        if (authenticating.value) return
        authenticating.value = true
        notice.value = "正在获取小米登录二维码"
        loginJob = viewModelScope.launch {
            try {
                val client = MiCloud()
                val challenge = runInterruptible(Dispatchers.IO) { client.start() }
                qr.value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(challenge.qrImage, 0, challenge.qrImage.size)
                } ?: throw IOException("二维码图片无效")
                approvalPage.value = challenge.approvalPage
                notice.value = "请在小米官方页面确认本人登录"
                val retainRenewal = repository.settings.current().autoRenewal
                val session = runInterruptible(Dispatchers.IO) { client.complete(challenge, retainRenewal) }
                repository.connect(session)
                notice.value = when {
                    repository.renewalReady.value -> "小米账号已连接，自动续期已就绪"
                    retainRenewal -> "小米账号已连接，本次授权未取得续期凭据"
                    else -> "小米账号已连接"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: MiCloud.ProbeFailure) {
                notice.value = error.message ?: "小米登录未通过"
            } catch (_: SocketTimeoutException) {
                notice.value = "二维码已超时，请重新获取"
            } catch (_: Exception) {
                notice.value = "连接或安全存储失败，请重试"
            } finally {
                approvalPage.value = null
                qr.value = null
                authenticating.value = false
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        notice.value = "已取消扫码登录"
    }

    fun signOut() = viewModelScope.launch {
        loginJob?.cancel()
        repository.signOut()
        notice.value = "已退出小米账号"
    }

    fun updateSettings(change: (Settings) -> Settings) = viewModelScope.launch {
        repository.settings.update(change)
        repository.reschedule()
    }

    fun setAutoRenewal(enabled: Boolean) = viewModelScope.launch {
        try {
            repository.setAutoRenewal(enabled)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            notice.value = "无法保存自动续期设置，请重试"
        }
    }

    fun syncNow(from: Instant? = null, through: Instant? = null) {
        val account = repository.accountState.value
        if (account != AccountState.CONNECTED) {
            notice.value = if (account == AccountState.EXPIRED) "小米账号登录已过期，请重新扫码登录" else "请先扫码登录"
            return
        }
        if (Metric.entries.none { it in settings.value.enabled && it.permission in permitted.value }) {
            notice.value = "请为至少一种数据开启 Health Connect 写入权限"
            return
        }
        repository.enqueue(from, through)
        notice.value = "同步任务已加入队列"
    }

    fun cancelSync() = viewModelScope.launch {
        repository.cancelSync()
        repository.reschedule()
        notice.value = "已请求取消当前同步"
    }

    fun clearDiagnostics() = viewModelScope.launch {
        repository.clearDiagnostics()
        notice.value = "已清理本地日志和同步进度"
    }
}
