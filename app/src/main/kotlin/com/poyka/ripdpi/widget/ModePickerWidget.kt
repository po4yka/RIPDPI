package com.poyka.ripdpi.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
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
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.WidgetSnapshot
import com.poyka.ripdpi.widget.actions.ModeKey
import com.poyka.ripdpi.widget.actions.PickModeAction
import com.poyka.ripdpi.widget.state.loadSnapshot
import com.poyka.ripdpi.widget.theme.RipDpiGlanceTheme

class ModePickerWidget : GlanceAppWidget() {
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
                ModePickerContent(context, snap)
            }
        }
    }
}

@androidx.compose.runtime.Composable
@Suppress("ContentEmitterReturningValues") // Glance composable, not standard Compose
private fun ModePickerContent(
    context: Context,
    snap: WidgetSnapshot,
) {
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
            Text(
                text = context.getString(R.string.widget_mode_picker_title),
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeButton(
                    label = context.getString(R.string.widget_mode_vpn),
                    mode = Mode.VPN,
                    isActive = snap.mode == Mode.VPN,
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                ModeButton(
                    label = context.getString(R.string.widget_mode_proxy),
                    mode = Mode.Proxy,
                    isActive = snap.mode == Mode.Proxy,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ModeButton(
    label: String,
    mode: Mode,
    isActive: Boolean,
) {
    val bgColor = if (isActive) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer
    val fgColor = if (isActive) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer
    Box(
        modifier =
            GlanceModifier
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(
                    actionRunCallback<PickModeAction>(
                        actionParametersOf(ModeKey to mode.preferenceValue),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style =
                TextStyle(
                    color = fgColor,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                ),
        )
    }
}

class ModePickerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ModePickerWidget()
}
