package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.ui.components.feedback.UnsavedChangesDialog

@Composable
internal fun StrategyConfigExitDialog(
    visible: Boolean,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (visible) {
        UnsavedChangesDialog(
            onKeepEditing = onKeepEditing,
            onDiscard = onDiscard,
        )
    }
}
