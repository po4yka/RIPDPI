@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.StyleStateKey

/** Component state that has no predefined Foundation style key. */
object RipDpiStyleStateKeys {
    val Expanded = StyleStateKey(false)
    val Active = StyleStateKey(false)
}
