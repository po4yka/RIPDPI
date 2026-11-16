package com.poyka.ripdpi.ui.screens.status

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Whether the main screen still owes the user its one-time explanation. Onboarding sets the flag when it
 * completes, and the dismiss action clears it. A user who completed onboarding before this flag existed never
 * has it set, so the explanation does not appear for them.
 */
internal object StatusFirstRunIntroStore {
    private const val PreferencesName = "status_first_run_intro"
    private const val PendingKey = "pending"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun isPending(context: Context): Boolean = preferences(context).getBoolean(PendingKey, false)

    fun setPending(
        context: Context,
        pending: Boolean,
    ) {
        preferences(context).edit().putBoolean(PendingKey, pending).apply()
    }
}

/** The one-time explanation of the hero signal line and the connect control. It stays until the user dismisses it. */
@Composable
internal fun StatusFirstRunIntro(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var visible by remember(context) { mutableStateOf(!inspection && StatusFirstRunIntroStore.isPending(context)) }
    if (visible) {
        StatusFirstRunIntroCard(
            onDismiss = {
                StatusFirstRunIntroStore.setPending(context, pending = false)
                visible = false
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun StatusFirstRunIntroCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard(modifier = modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.StatusFirstRunIntro)) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Column(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                Text(text = stringResource(R.string.status_intro_line), style = type.body, color = colors.foreground)
                Text(text = stringResource(R.string.status_intro_control), style = type.body, color = colors.foreground)
            }
            RipDpiButton(
                text = stringResource(R.string.action_dismiss),
                onClick = onDismiss,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.StatusFirstRunIntroDismiss),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusFirstRunIntroPreview() {
    RipDpiComponentPreview(themePreference = "light") { StatusFirstRunIntroCard(onDismiss = {}) }
}

@Preview(showBackground = true)
@Composable
private fun StatusFirstRunIntroDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") { StatusFirstRunIntroCard(onDismiss = {}) }
}
