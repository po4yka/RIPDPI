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
