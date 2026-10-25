package com.poyka.ripdpi.widget

import android.content.Context
import android.text.format.Formatter
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

private const val WidgetCompactWidthDp = 180
private const val WidgetMediumWidthDp = 250
private const val WidgetCompactHeightDp = 110
private const val WidgetExpandedHeightDp = 180
private const val TelemetryLabelWidthDp = 80
private const val SecondsPerMinute = 60L

class TelemetryWidget : GlanceAppWidget() {
    override val sizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(WidgetCompactWidthDp.dp, WidgetCompactHeightDp.dp),
                DpSize(WidgetMediumWidthDp.dp, WidgetCompactHeightDp.dp),
                DpSize(WidgetMediumWidthDp.dp, WidgetExpandedHeightDp.dp),
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
                value = Formatter.formatShortFileSize(context, snap.bytesUp.coerceAtLeast(0L)),
            )
            TelemetryRow(
                label = context.getString(R.string.widget_telemetry_bytes_down),
                value = Formatter.formatShortFileSize(context, snap.bytesDown.coerceAtLeast(0L)),
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
            modifier = GlanceModifier.width(TelemetryLabelWidthDp.dp),
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
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % SecondsPerMinute
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % SecondsPerMinute
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

class TelemetryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TelemetryWidget()
}
