package com.mihealthsync.app

internal fun metricStatusGroups(
    enabled: Set<Metric>, permitted: Set<String>, checkPermissions: Boolean, events: List<SyncEvent>,
): List<MetricStatusGroup> {
    val latest = events.distinctBy { it.metric }.associateBy { it.metric }

    fun metricRow(metric: Metric): MetricStatusRow {
        val label = if (metric == Metric.EXERCISE) "锻炼记录" else metric.title
        return when {
            metric !in enabled -> MetricStatusRow(metric.name, label, "已关闭")
            checkPermissions && metric.permission !in permitted ->
                MetricStatusRow(metric.name, label, "未授权", MetricStatusTone.ATTENTION)
            else -> statusFromEvent(metric.name, label, latest[metric.name])
        }
    }

    val workoutRows = listOf(Metric.EXERCISE, Metric.DISTANCE, Metric.ELEVATION, Metric.SPEED, Metric.TOTAL_CALORIES)
        .map(::metricRow)
    val routeRow = when {
        Metric.EXERCISE !in enabled -> null
        ROUTE_PERMISSION !in permitted ->
            MetricStatusRow(ROUTE_EVENT, "锻炼路线", "未授权", MetricStatusTone.ATTENTION)
        else -> statusFromEvent(ROUTE_EVENT, "锻炼路线", latest[ROUTE_EVENT])
    }

    return listOf(
        MetricStatusGroup("健康", listOf(Metric.HEART, Metric.RESTING, Metric.OXYGEN, Metric.SLEEP, Metric.WEIGHT).map(::metricRow)),
        MetricStatusGroup("日常活动", listOf(Metric.STEPS, Metric.ACTIVE_CALORIES).map(::metricRow)),
        MetricStatusGroup("锻炼", workoutRows + listOfNotNull(routeRow), initiallyExpanded = false),
    )
}

private fun statusFromEvent(key: String, label: String, event: SyncEvent?): MetricStatusRow {
    if (event == null) return MetricStatusRow(key, label, "等待同步")

    val status = event.status
    return when {
        status == "已同步" -> MetricStatusRow(
            key, label, status, MetricStatusTone.SUCCESS,
            "新增 ${event.added} · 更新 ${event.updated}\n跳过 ${event.skipped} · 无效 ${event.rejected}",
        )
        status == "云端暂无可写数据" && event.rejected > 0 -> MetricStatusRow(
            key, label, "暂无有效数据", MetricStatusTone.ATTENTION, "无效 ${event.rejected}",
        )
        status == "云端暂无可写数据" -> MetricStatusRow(key, label, "暂无数据")
        status == "暂无可写路线" -> MetricStatusRow(key, label, "暂无路线")
        key == ROUTE_EVENT && status.startsWith("已同步或核对 ") ->
            MetricStatusRow(key, label, "已核对", MetricStatusTone.SUCCESS, status)
        status == "未授权写入" || status == "写入权限已撤销" ->
            MetricStatusRow(key, label, "未授权", MetricStatusTone.ATTENTION, status)
        status == "分页不完整，下次重试" ->
            MetricStatusRow(key, label, "等待重试", MetricStatusTone.ATTENTION, status)
        status == "网络或 Health Connect 暂不可用" ->
            MetricStatusRow(key, label, "暂不可用", MetricStatusTone.ATTENTION, status)
        else -> MetricStatusRow(key, label, "同步失败", MetricStatusTone.ATTENTION, status)
    }
}
