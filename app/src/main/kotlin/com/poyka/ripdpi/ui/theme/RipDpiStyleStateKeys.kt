@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.StyleStateKey

/** Component state that has no predefined Foundation style key. */
object RipDpiStyleStateKeys {
    val Loading = StyleStateKey(false)
    val HasError = StyleStateKey(false)
    val IsEmpty = StyleStateKey(false)
    val Compact = StyleStateKey(false)
    val Expanded = StyleStateKey(false)
    val ReadOnly = StyleStateKey(false)
    val Active = StyleStateKey(false)
}
