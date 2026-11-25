package com.mihealthsync.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val GlassLight = lightColorScheme(
    primary = Color(0xFF244EC5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF142C7C),
    secondaryContainer = Color(0xFFDDE7FF), onSecondaryContainer = Color(0xFF25428D),
    background = Color(0xFFEAF0FC), onBackground = Color(0xFF172548),
    surface = Color(0xFFF4F7FF), onSurface = Color(0xFF172548), onSurfaceVariant = Color(0xFF4E5D7E),
    surfaceContainerHigh = Color(0xFFF3F6FF), outline = Color(0xFF7181A7), outlineVariant = Color(0xFFC7D3EB),
)

internal val GlassDark = darkColorScheme(
    primary = Color(0xFFB4C7FF), onPrimary = Color(0xFF172F72),
    primaryContainer = Color(0xFF263A78), onPrimaryContainer = Color(0xFFDDE6FF),
    secondaryContainer = Color(0xFF2C4276), onSecondaryContainer = Color(0xFFE1EBFF),
    background = Color(0xFF0F172D), onBackground = Color(0xFFE8EEFF),
    surface = Color(0xFF1B2744), onSurface = Color(0xFFE8EEFF), onSurfaceVariant = Color(0xFFBBC9EC),
    surfaceContainerHigh = Color(0xFF253353), outline = Color(0xFF899ABD), outlineVariant = Color(0xFF47597B),
)

@Composable
internal fun GlassBackdrop(dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(
            if (dark) listOf(Color(0xFF0C142A), Color(0xFF1D2550), Color(0xFF101A36))
            else listOf(Color(0xFFE9F1FF), Color(0xFFE2E6FE), Color(0xFFEEF3FF))
        ))
        val radius = size.width * 0.94f
        fun glow(color: Color, x: Float, y: Float) {
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), Offset(x, y), radius),
                radius, Offset(x, y))
        }
        glow(if (dark) Color(0x705677E7) else Color(0xB591C8FF), size.width * .08f, size.height * .16f)
        glow(if (dark) Color(0x855E399F) else Color(0xA7B5A2FF), size.width * 1.03f, size.height * .42f)
        glow(if (dark) Color(0x503B79AA) else Color(0xA88FE5E9), size.width * .08f, size.height * .92f)
    }
}

@Composable
internal fun GlassTabs(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(CircleShape)
            .background(scheme.surface.copy(alpha = .44f))
            .border(1.dp, Color.White.copy(alpha = .38f), CircleShape)
            .selectableGroup().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("同步", "设置", "日志").forEachIndexed { index, label ->
            val active = selected == index
            val tint by animateColorAsState(if (active) scheme.surface.copy(alpha = .94f) else Color.Transparent,
                label = "分段按钮背景")
            Box(
                Modifier.weight(1f).then(if (active) Modifier.shadow(3.dp, CircleShape) else Modifier)
                    .clip(CircleShape).background(tint)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) scheme.primary else scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun GlassCard(title: String, mark: String? = null, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(27.dp)
    Column(
        Modifier.fillMaxWidth().shadow(7.dp, shape, ambientColor = Color(0x3637478D), spotColor = Color(0x4437478D))
            .clip(shape).background(Brush.verticalGradient(listOf(scheme.surface.copy(alpha = .88f),
                scheme.surface.copy(alpha = .60f))))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .68f),
                scheme.outlineVariant.copy(alpha = .34f), Color.White.copy(alpha = .35f))), shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mark != null) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                    .background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(mark, color = scheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        }
        content()
    }
}

@Composable
internal fun GlassStatus(label: String, ready: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (ready) Color(0xFF26946C) else scheme.primary, CircleShape))
        Text(label, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
