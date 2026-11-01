package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass

enum class RipDpiActionLayout {
    Adaptive,
    Inline,
    Stacked,
}

@Composable
internal fun RipDpiActionLayout.resolvedActionLayout(): RipDpiActionLayout =
    when (this) {
        RipDpiActionLayout.Adaptive -> {
            when (RipDpiThemeTokens.layout.widthClass) {
                RipDpiWidthClass.Compact -> {
                    RipDpiActionLayout.Stacked
                }

                RipDpiWidthClass.Medium,
                RipDpiWidthClass.Expanded,
                RipDpiWidthClass.Large,
                RipDpiWidthClass.ExtraLarge,
                -> {
                    RipDpiActionLayout.Inline
                }
            }
        }

        RipDpiActionLayout.Inline,
        RipDpiActionLayout.Stacked,
        -> {
            this
        }
    }

/**
 * Dialogs follow Material 3: an end-aligned row on every width. A dialog falls back to stacked only when its labels
 * do not fit, which [RipDpiDialogCard] decides by measuring them.
 */
internal fun RipDpiActionLayout.resolvedDialogActionLayout(): RipDpiActionLayout =
    if (this == RipDpiActionLayout.Adaptive) RipDpiActionLayout.Inline else this
