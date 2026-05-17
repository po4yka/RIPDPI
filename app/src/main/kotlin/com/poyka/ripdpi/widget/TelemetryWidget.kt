package com.poyka.ripdpi.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.WidgetSnapshot
import com.poyka.ripdpi.widget.state.loadSnapshot
import com.poyka.ripdpi.widget.theme.GlanceMutedForeground
import com.poyka.ripdpi.widget.theme.RipDpiGlanceTheme
import java.util.concurrent.TimeUnit

class TelemetryWidget : GlanceAppWidget() {
    override val sizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(180.dp, 110.dp),
                DpSize(250.dp, 110.dp),
                DpSize(250.dp, 180.dp),
            ),
        )

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val snap = loadSnapshot(context)
        provideContent {
            RipDpiGlanceTheme {
                TelemetryContent(context, snap)
            }
        }
    }
}

@androidx.compose.runtime.Composable
@Suppress("ContentEmitterReturningValues") // Glance composable, not standard Compose
private fun TelemetryContent(
    context: Context,
    snap: WidgetSnapshot,
) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(12.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = context.getString(R.string.widget_description_telemetry),
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            TelemetryRow(
                label = context.getString(R.string.widget_telemetry_uptime),
                value = formatUptime(snap.uptimeMs),
            )
            TelemetryRow(
                label = context.getString(R.string.widget_telemetry_bytes_up),
                value = formatBytes(snap.bytesUp),
            )
            TelemetryRow(
                label = context.getString(R.string.widget_telemetry_bytes_down),
                value = formatBytes(snap.bytesDown),
            )
            TelemetryRow(
                label = context.getString(R.string.widget_telemetry_restarts),
                value = snap.restartCount.toString(),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TelemetryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = GlanceMutedForeground),
            modifier = GlanceModifier.width(80.dp),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = value,
            style = TextStyle(color = GlanceTheme.colors.onSurface),
        )
    }
}

private fun formatUptime(ms: Long): String {
    if (ms <= 0L) return "--"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}

class TelemetryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TelemetryWidget()
}
