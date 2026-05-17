package com.poyka.ripdpi.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.WidgetSnapshot
import com.poyka.ripdpi.widget.state.loadSnapshot
import com.poyka.ripdpi.widget.theme.GlanceError
import com.poyka.ripdpi.widget.theme.GlanceOnPrimary
import com.poyka.ripdpi.widget.theme.GlanceSuccess
import com.poyka.ripdpi.widget.theme.RipDpiGlanceTheme

class StatusDisplayWidget : GlanceAppWidget() {
    override val sizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(110.dp, 110.dp),
                DpSize(180.dp, 110.dp),
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
                StatusDisplayContent(context, snap)
            }
        }
    }
}

@androidx.compose.runtime.Composable
@Suppress("ContentEmitterReturningValues") // Glance composable, not standard Compose
private fun StatusDisplayContent(
    context: Context,
    snap: WidgetSnapshot,
) {
    val size = LocalSize.current
    val statusText =
        when (snap.status) {
            AppStatus.Running -> context.getString(R.string.widget_status_running)
            AppStatus.Halted -> context.getString(R.string.widget_status_halted)
        }
    val badgeColor =
        if (snap.status == AppStatus.Running) GlanceSuccess else GlanceError
    val modeText =
        when (snap.mode) {
            Mode.VPN -> context.getString(R.string.widget_mode_vpn)
            Mode.Proxy -> context.getString(R.string.widget_mode_proxy)
            null -> context.getString(R.string.widget_status_unknown)
        }

    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    GlanceModifier
                        .background(badgeColor)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusText,
                    style =
                        TextStyle(
                            color = GlanceOnPrimary,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            }
            if (size.height >= 180.dp || size.width >= 180.dp) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    text = modeText,
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                )
            }
        }
    }
}

class StatusDisplayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusDisplayWidget()
}
