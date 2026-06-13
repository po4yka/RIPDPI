package com.poyka.ripdpi.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
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
import com.poyka.ripdpi.data.WidgetSnapshot
import com.poyka.ripdpi.widget.actions.ConnectAction
import com.poyka.ripdpi.widget.actions.DisconnectAction
import com.poyka.ripdpi.widget.state.loadSnapshot
import com.poyka.ripdpi.widget.theme.RipDpiGlanceTheme

class ConnectToggleWidget : GlanceAppWidget() {
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
                ConnectToggleContent(context, snap)
            }
        }
    }
}

@androidx.compose.runtime.Composable
@Suppress("ContentEmitterReturningValues") // Glance composable, not standard Compose
private fun ConnectToggleContent(
    context: Context,
    snap: WidgetSnapshot,
) {
    val size = LocalSize.current
    // Reconnecting is an active (resuming) session: offer Disconnect, like Running.
    val isActive = snap.status != AppStatus.Halted
    val action =
        if (isActive) {
            actionRunCallback<DisconnectAction>()
        } else {
            actionRunCallback<ConnectAction>()
        }
    val label =
        if (isActive) {
            context.getString(R.string.widget_action_disconnect)
        } else {
            context.getString(R.string.widget_action_connect)
        }
    val statusText =
        when (snap.status) {
            AppStatus.Running -> context.getString(R.string.widget_status_running)
            AppStatus.Reconnecting -> context.getString(R.string.widget_status_reconnecting)
            AppStatus.Halted -> context.getString(R.string.widget_status_halted)
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
            if (size.height >= 180.dp) {
                Text(
                    text = "RIPDPI",
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = statusText,
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
            } else {
                Text(
                    text = statusText,
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
            }
            Box(
                modifier =
                    GlanceModifier
                        .background(GlanceTheme.colors.primary)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable(action),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onPrimary,
                            fontWeight = FontWeight.Medium,
                        ),
                )
            }
        }
    }
}

class ConnectToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConnectToggleWidget()
}
