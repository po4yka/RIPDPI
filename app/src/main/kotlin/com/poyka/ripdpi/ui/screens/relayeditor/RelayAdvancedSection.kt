package com.poyka.ripdpi.ui.screens.relayeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * The one collapsed "Advanced" group of an editor. It stays open while [hasError] is `true`, so an invalid field
 * inside it is never hidden.
 */
@Composable
internal fun RelayAdvancedSection(
    hasError: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Latched, so fixing the field does not fold the group away under the user's cursor.
    LaunchedEffect(hasError) { if (hasError) expanded = true }
    val shown = expanded || hasError
    val state = stringResource(if (shown) R.string.semantic_state_expanded else R.string.semantic_state_collapsed)
    RipDpiButton(
        text = stringResource(R.string.relay_editor_advanced),
        onClick = { expanded = !shown },
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.RelayEditorAdvanced)
                .semantics { stateDescription = state },
        variant = RipDpiButtonVariant.Ghost,
        enabled = !hasError,
    )
    if (shown) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md), content = content)
    }
}
