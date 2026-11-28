package com.mihealthsync.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class MetricStatusGroup(
    val title: String,
    val rows: List<MetricStatusRow>,
    val initiallyExpanded: Boolean = true,
)

internal data class MetricStatusRow(
    val key: String,
    val label: String,
    val status: String,
    val tone: MetricStatusTone = MetricStatusTone.NEUTRAL,
    val details: String? = null,
)

internal enum class MetricStatusTone { NEUTRAL, SUCCESS, ATTENTION }

@Composable
internal fun MetricStatusPanel(groups: List<MetricStatusGroup>) {
    var openRow by rememberSaveable { mutableStateOf<String?>(null) }
    GlassCard("数据状态") {
        groups.forEach { group ->
            key(group.title) {
                MetricStatusSection(group, openRow) { row ->
                    openRow = if (openRow == row.key) null else row.key
                }
            }
        }
    }
}

@Composable
private fun MetricStatusSection(
    group: MetricStatusGroup,
    openRow: String?,
    onSelectRow: (MetricStatusRow) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(group.initiallyExpanded) }
    val attentionCount = group.rows.count { it.tone == MetricStatusTone.ATTENTION }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha = .48f))) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(colors.surfaceContainerHigh.copy(alpha = .72f))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起分组" else "展开分组") {
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.title, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.onSurface,
            )
            if (!expanded && attentionCount > 0) {
                Text(
                    "$attentionCount 项需处理", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = colors.error, textAlign = TextAlign.End,
                )
            }
            StatusChevron(expanded)
        }
        AnimatedVisibility(expanded) {
            Column {
                group.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = colors.outlineVariant.copy(alpha = .50f),
                    )
                    MetricStatusLine(row, openRow == row.key) { onSelectRow(row) }
                }
            }
        }
    }
}

@Composable
private fun MetricStatusLine(row: MetricStatusRow, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val details = row.details?.takeIf { it.isNotBlank() && it != row.status }
    val statusColor = when (row.tone) {
        MetricStatusTone.NEUTRAL -> colors.onSurfaceVariant
        MetricStatusTone.SUCCESS -> colors.primary
        MetricStatusTone.ATTENTION -> colors.error
    }
    val interaction = if (details == null) Modifier else Modifier
        .clickable(role = Role.Button, onClickLabel = if (expanded) "收起详情" else "展开详情", onClick = onClick)
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(interaction)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.label, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
            )
            Text(
                row.status, Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                color = statusColor, textAlign = TextAlign.End,
            )
            if (details != null) StatusChevron(expanded) else Spacer(Modifier.size(14.dp))
        }
        if (details != null) {
            AnimatedVisibility(expanded) {
                Text(
                    details,
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.primary.copy(alpha = .07f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusChevron(expanded: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(14.dp)) {
        val edge = size.height * if (expanded) .64f else .36f
        val midpoint = size.height * if (expanded) .36f else .64f
        val middle = Offset(size.width * .5f, midpoint)
        val stroke = 1.5.dp.toPx()
        drawLine(color, Offset(size.width * .24f, edge), middle, stroke, StrokeCap.Round)
        drawLine(color, middle, Offset(size.width * .76f, edge), stroke, StrokeCap.Round)
    }
}
