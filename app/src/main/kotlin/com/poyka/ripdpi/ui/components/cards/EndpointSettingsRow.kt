package com.poyka.ripdpi.ui.components.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextDirection
import com.poyka.ripdpi.ui.theme.RipDpiIcons

private val LocalEndpointValueDirection = staticCompositionLocalOf<TextDirection?> { null }

/** Apply endpoint direction to the value while keeping Arabic captions and row layout unchanged. */
@Composable
internal fun EndpointSettingsRow(
    title: String,
    subtitle: String,
    value: String,
    monospaceValue: Boolean = false,
    showChevron: Boolean = false,
    testTag: String? = null,
) {
    CompositionLocalProvider(LocalEndpointValueDirection provides TextDirection.ContentOrLtr) {
        SettingsRow(
            title = title,
            subtitle = subtitle,
            value = value,
            leadingIcon = RipDpiIcons.Public,
            monospaceValue = monospaceValue,
            showChevron = showChevron,
            showDivider = true,
            testTag = testTag,
        )
    }
}

@Composable
internal fun endpointValueTextDirection(): TextDirection? = LocalEndpointValueDirection.current
