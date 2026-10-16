package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The Simple flavor owns the safe area around its backup settings overlay. */
@Composable
internal fun SimpleBackupOverlay(
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(insets)) {
        content()
    }
}
