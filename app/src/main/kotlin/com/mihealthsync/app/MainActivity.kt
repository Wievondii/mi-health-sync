package com.mihealthsync.app

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        model.refreshHealth()
    }
    private var privacy by mutableStateOf(false)
    private var backgroundRestricted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPrivacyIfRequested(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) GlassDark else GlassLight,
                shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(19.dp),
                    large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(30.dp)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    GlassBackdrop(dark)
                    SyncScreen(model, privacy, { privacy = it }, ::requestPermissions, ::openHealthSettings,
                        backgroundRestricted, ::openAppSettings, ::requestRoutePermission)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshHealth()
        backgroundRestricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPrivacyIfRequested(intent)
    }

    private fun openPrivacyIfRequested(intent: Intent) {
        if (intent.action in listOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", "android.intent.action.VIEW_PERMISSION_USAGE")) {
            privacy = true
        }
    }

    private fun requestPermissions() {
        if (model.healthStatus.value != HealthConnectClient.SDK_AVAILABLE) return
        val requested = model.settings.value.enabled.map { it.permission }.toSet() - model.permitted.value
        if (requested.isNotEmpty()) permissionLauncher.launch(requested)
    }

    private fun requestRoutePermission() {
        if (model.healthStatus.value != HealthConnectClient.SDK_AVAILABLE) return
        val requested = setOf(Metric.EXERCISE.permission, ROUTE_PERMISSION) - model.permitted.value
        if (requested.isNotEmpty()) permissionLauncher.launch(requested)
    }

    private fun openHealthSettings() {
        try { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
        catch (_: ActivityNotFoundException) { model.notice.value = "手机未提供 Health Connect 设置入口" }
    }

    private fun openAppSettings() {
        startActivity(Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA).withZone(CHINA)

@Composable
private fun SyncScreen(model: MainViewModel, privacy: Boolean, showPrivacy: (Boolean) -> Unit,
                        requestPermissions: () -> Unit, openHealthSettings: () -> Unit,
                        backgroundRestricted: Boolean, openAppSettings: () -> Unit,
                        requestRoutePermission: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val connected by model.repository.connected.collectAsStateWithLifecycle()
    val permitted by model.permitted.collectAsStateWithLifecycle()
    val healthStatus by model.healthStatus.collectAsStateWithLifecycle()
    val progress by model.repository.progress.collectAsStateWithLifecycle()
    val pending by model.repository.work.collectAsStateWithLifecycle(initialValue = emptyList())
    val manualPending = pending.any { info ->
        info.state == androidx.work.WorkInfo.State.RUNNING ||
            (MANUAL_WORK in info.tags && !info.state.isFinished)
    } || progress != null
    val next = pending.filter { info -> info.state == androidx.work.WorkInfo.State.ENQUEUED }
        .minOfOrNull { it.nextScheduleTimeMillis }?.takeIf { it != Long.MAX_VALUE && it > 0 }
    val notice by model.notice.collectAsStateWithLifecycle()
    val events by model.events.collectAsStateWithLifecycle()
    val qr by model.qr.collectAsStateWithLifecycle()
    val authenticating by model.authenticating.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var clearDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 22.dp)) {
            Text("小米健康同步", Modifier.padding(top = 26.dp, bottom = 16.dp),
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (!privacy) GlassTabs(screen) { screen = it }
            val scroll = key(screen, privacy) { rememberScrollState() }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                if (privacy) {
                    GlassCard("权限与隐私") {
                        Text("应用在手机上读取你授权的小米云端运动健康数据，再写入 Android Health Connect。小米短期健康会话使用手机的安全存储保存。诊断日志默认只包含运行状态、时间和数量。")
                        Text("你可以在系统设置撤销写入权限，或在应用设置退出小米账号。已有的 Health Connect 记录可在系统设置按来源管理。")
                        TextButton(onClick = { showPrivacy(false) }) { Text("返回") }
                    }
                } else when (screen) {
                    0 -> {
                        GlassCard("小米运动健康", "☁") {
                            GlassStatus(if (connected) "中国大陆云端已连接" else "等待本人扫码登录", connected)
                            if (!connected && !authenticating) Button(onClick = model::login) { Text("获取小米登录二维码") }
                            if (authenticating) OutlinedButton(onClick = model::cancelLogin) { Text("取消登录") }
                            qr?.let { bitmap ->
                                Image(bitmap.asImageBitmap(), "小米登录二维码", Modifier.size(215.dp).background(Color.White).padding(8.dp))
                            }
                        }
                        GlassCard("Health Connect", "+") {
                            when (healthStatus) {
                                HealthConnectClient.SDK_AVAILABLE -> {
                                    val count = settings.enabled.count { it.permission in permitted }
                                    GlassStatus("已允许写入 $count / ${settings.enabled.size} 种数据", count > 0)
                                    if (count < settings.enabled.size) {
                                        Button(onClick = requestPermissions) { Text("授权写入") }
                                    }
                                }
                                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Text("请安装或更新 Health Connect 后重试")
                                else -> Text("此手机上的 Health Connect 当前不可用")
                            }
                            TextButton(onClick = openHealthSettings) { Text("打开系统设置") }
                        }
                        GlassCard("同步计划", "↻") {
                            GlassStatus(progress ?: when {
                                manualPending -> "同步等待运行"
                                settings.automatic && backgroundRestricted -> "系统限制了自动同步"
                                settings.automatic -> "自动同步已开启，每 ${settings.interval} 分钟尝试"
                                else -> "仅手动同步"
                            }, settings.automatic && !backgroundRestricted)
                            if (settings.automatic && backgroundRestricted) {
                                TextButton(onClick = openAppSettings) { Text("打开此应用的电池设置") }
                            } else next?.let { Text("下次最早尝试 ${formatMillis(it)}") }
                            events.firstNotNullOfOrNull { it.pulledAtMillis }?.let { Text("最近拉取完成 ${formatMillis(it)}") }
                            events.firstNotNullOfOrNull { it.writtenAtMillis }?.let { Text("最近写入完成 ${formatMillis(it)}") }
                            events.mapNotNull { it.latestEpochSeconds }.maxOrNull()?.let { Text("最新已处理云端记录 ${clockFormat.format(Instant.ofEpochSecond(it))}") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { model.syncNow() }, enabled = connected && !manualPending) { Text("立即同步") }
                                if (manualPending) OutlinedButton(onClick = model::cancelSync) { Text("取消同步") }
                                else OutlinedButton(onClick = { datePicker = true }, enabled = connected) { Text("选择日期") }
                            }
                        }
                        GlassCard("数据状态") {
                            val latest = events.filter { it.metric.isNotEmpty() }.distinctBy { it.metric }.associateBy { it.metric }
                            Metric.entries.forEach { metric ->
                                val item = latest[metric.name]
                                val description = when {
                                    metric !in settings.enabled -> "已关闭"
                                    healthStatus == HealthConnectClient.SDK_AVAILABLE && metric.permission !in permitted -> "未授权写入"
                                    item == null -> "等待同步"
                                    else -> item.summary()
                                }
                                Text("${metric.title}　$description", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (Metric.EXERCISE in settings.enabled) {
                                val routeStatus = if (ROUTE_PERMISSION in permitted) latest[ROUTE_EVENT]?.status ?: "等待同步"
                                    else "未授权写入"
                                Text("锻炼路线　$routeStatus", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    1 -> {
                        GlassCard("同步设置", "↻") {
                            SettingSwitch("自动同步", settings.automatic) { value -> model.updateSettings { it.copy(automatic = value) } }
                            if (settings.automatic) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(15, 30, 60).forEach { value -> FilterChip(settings.interval == value,
                                        { model.updateSettings { it.copy(interval = value) } }, { Text("$value 分钟") }) }
                                }
                                Text("如需后台同步，可在 HyperOS 应用设置中允许自启动，并检查电池限制。")
                            }
                            SettingSwitch("仅在 Wi-Fi 下同步", settings.wifiOnly) { value -> model.updateSettings { it.copy(wifiOnly = value) } }
                            Text("首次自动同步范围")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 7, 30).forEach { value -> FilterChip(settings.firstDays == value,
                                    { model.updateSettings { it.copy(firstDays = value) } }, { Text("$value 天") }) }
                            }
                            Text("增量回溯")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(24, 48, 72).forEach { value -> FilterChip(settings.overlapHours == value,
                                    { model.updateSettings { it.copy(overlapHours = value) } }, { Text("$value 小时") }) }
                            }
                        }
                        GlassCard("同步数据", "+") {
                            Metric.entries.forEach { metric ->
                                SettingSwitch(metric.title, metric in settings.enabled) { value ->
                                    model.updateSettings { it.copy(enabled = if (value) it.enabled + metric else it.enabled - metric) }
                                }
                            }
                            if (Metric.EXERCISE in settings.enabled && healthStatus == HealthConnectClient.SDK_AVAILABLE) {
                                Text(if (ROUTE_PERMISSION in permitted) "已允许写入锻炼路线" else "锻炼路线需要单独授权")
                                if (ROUTE_PERMISSION !in permitted) {
                                    TextButton(onClick = requestRoutePermission) { Text("授权写入锻炼路线") }
                                }
                            }
                        }
                        GlassCard("账号与隐私") {
                            if (connected) OutlinedButton(onClick = { model.signOut() }) { Text("退出小米账号") }
                            TextButton(onClick = { showPrivacy(true) }) { Text("查看权限与隐私") }
                            TextButton(onClick = { clearDialog = true }) { Text("清理本地日志和同步进度") }
                        }
                    }
                    else -> GlassCard("同步日志") {
                        if (events.isEmpty()) Text("还没有同步记录")
                        else {
                            TextButton(onClick = { model.syncNow() }, enabled = connected && !manualPending) { Text("重试同步") }
                            TextButton(onClick = {
                                val report = events.joinToString("\n") { event -> "${formatMillis(event.timeMillis)} ${event.metricTitle()} ${event.summary()}" }
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, report), "导出脱敏诊断"))
                            }) { Text("导出脱敏诊断") }
                            events.forEach { event ->
                                HorizontalDivider()
                                Text("${formatMillis(event.timeMillis)}　${event.metricTitle()}")
                                Text(event.summary(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (!privacy) notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (datePicker) DateRangeDialog({ datePicker = false }) { first, last ->
        model.syncNow(first.atStartOfDay(CHINA).toInstant(), last.plusDays(1).atStartOfDay(CHINA).toInstant())
        datePicker = false
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清理本地诊断") },
        text = { Text("将清除同步日志和进度。下一次同步将重新检查设置中的历史范围。") },
        confirmButton = { TextButton(onClick = { model.clearDiagnostics(); clearDialog = false }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } })
}

@Composable private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(value, onCheckedChange = onChange)
    }
}

@Composable private fun DateRangeDialog(onDismiss: () -> Unit, submit: (LocalDate, LocalDate) -> Unit) {
    val today = LocalDate.now(CHINA)
    var first by rememberSaveable { mutableStateOf(today.minusDays(6).toString()) }
    var last by rememberSaveable { mutableStateOf(today.toString()) }
    val parsed = runCatching { LocalDate.parse(first) to LocalDate.parse(last) }.getOrNull()
        ?.takeIf { (a, b) -> a <= b && b <= today && ChronoUnit.DAYS.between(a, b) < 30 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("同步指定日期") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按北京时间填写，最多 30 天。格式 YYYY-MM-DD。")
            OutlinedTextField(first, { first = it }, label = { Text("开始日期") }, singleLine = true)
            OutlinedTextField(last, { last = it }, label = { Text("结束日期") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { parsed?.let { submit(it.first, it.second) } }, enabled = parsed != null) { Text("开始同步") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun formatMillis(value: Long) = clockFormat.format(Instant.ofEpochMilli(value))
private fun SyncEvent.metricTitle() = if (metric == ROUTE_EVENT) "锻炼路线"
    else Metric.entries.firstOrNull { it.name == metric }?.title ?: "同步任务"
private fun SyncEvent.summary() = if (status == "已同步") "$status · 新增 $added · 更新 $updated · 跳过 $skipped · 无效 $rejected" else status
