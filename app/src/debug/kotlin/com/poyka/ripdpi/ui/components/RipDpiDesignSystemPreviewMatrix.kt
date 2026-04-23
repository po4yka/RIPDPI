package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbar
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.LogRow
import com.poyka.ripdpi.ui.components.indicators.LogRowTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiContrastLevel
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun RipDpiDesignSystemPreviewMatrixCatalog(
    themePreference: String,
    contrastLevel: RipDpiContrastLevel = RipDpiContrastLevel.Standard,
) {
    RipDpiDesignSystemCatalog(
        themePreference = themePreference,
        contrastLevel = contrastLevel,
        includeInteractiveStates = true,
    )
}

@Composable
internal fun RipDpiDesignSystemScreenshotCatalog(
    themePreference: String,
    contrastLevel: RipDpiContrastLevel = RipDpiContrastLevel.Standard,
) {
    RipDpiDesignSystemCatalog(
        themePreference = themePreference,
        contrastLevel = contrastLevel,
        includeInteractiveStates = false,
    )
}

@Composable
private fun RipDpiDesignSystemCatalog(
    themePreference: String,
    contrastLevel: RipDpiContrastLevel,
    includeInteractiveStates: Boolean,
) {
    val dropdownOptions =
        kotlinx.collections.immutable.persistentListOf(
            RipDpiDropdownOption("auto", "Auto"),
            RipDpiDropdownOption("fake", "desync (fake)"),
            RipDpiDropdownOption("proxy", "SOCKS5 proxy"),
        )

    RipDpiComponentPreview(themePreference = themePreference, contrastLevel = contrastLevel) {
        PreviewSection(title = "Controls") {
            PreviewMatrixRow(
                title = "Buttons",
                cells =
                    buildList {
                        add("Enabled" to { RipDpiButton(text = "Connect", onClick = {}) })
                        add("Disabled" to { RipDpiButton(text = "Connect", onClick = {}, enabled = false) })
                        if (includeInteractiveStates) {
                            add(
                                "Pressed" to {
                                    RipDpiButton(
                                        text = "Connect",
                                        onClick = {},
                                        interactionSource = rememberPreviewInteractionSource(pressed = true),
                                    )
                                },
                            )
                            add(
                                "Focused" to {
                                    RipDpiButton(
                                        text = "Connect",
                                        onClick = {},
                                        interactionSource = rememberPreviewInteractionSource(focused = true),
                                    )
                                },
                            )
                        }
                        if (includeInteractiveStates) {
                            add(
                                "Loading" to {
                                    RipDpiButton(
                                        text = "Connecting",
                                        onClick = {},
                                        loading = true,
                                        density = RipDpiControlDensity.Compact,
                                    )
                                },
                            )
                        }
                    },
            )

            PreviewMatrixRow(
                title = "Destructive",
                cells =
                    buildList {
                        add(
                            "Enabled" to {
                                RipDpiButton(
                                    text = "Reset",
                                    onClick = {},
                                    variant = RipDpiButtonVariant.Destructive,
                                )
                            },
                        )
                        add(
                            "Disabled" to {
                                RipDpiButton(
                                    text = "Reset",
                                    onClick = {},
                                    variant = RipDpiButtonVariant.Destructive,
                                    enabled = false,
                                )
                            },
                        )
                        if (includeInteractiveStates) {
                            add(
                                "Pressed" to {
                                    RipDpiButton(
                                        text = "Reset",
                                        onClick = {},
                                        variant = RipDpiButtonVariant.Destructive,
                                        interactionSource = rememberPreviewInteractionSource(pressed = true),
                                    )
                                },
                            )
                            add(
                                "Focused" to {
                                    RipDpiButton(
                                        text = "Reset",
                                        onClick = {},
                                        variant = RipDpiButtonVariant.Destructive,
                                        interactionSource = rememberPreviewInteractionSource(focused = true),
                                    )
                                },
                            )
                        }
                    },
            )

            PreviewMatrixRow(
                title = "Icon Buttons",
                cells =
                    buildList {
                        add(
                            "Enabled" to {
                                RipDpiIconButton(
                                    icon = RipDpiIcons.Settings,
                                    contentDescription = "Settings",
                                    onClick = {},
                                    style = RipDpiIconButtonStyle.Outline,
                                )
                            },
                        )
                        add(
                            "Disabled" to {
                                RipDpiIconButton(
                                    icon = RipDpiIcons.Settings,
                                    contentDescription = "Settings",
                                    onClick = {},
                                    style = RipDpiIconButtonStyle.Outline,
                                    enabled = false,
                                )
                            },
                        )
                        if (includeInteractiveStates) {
                            add(
                                "Pressed" to {
                                    RipDpiIconButton(
                                        icon = RipDpiIcons.Settings,
                                        contentDescription = "Settings",
                                        onClick = {},
                                        style = RipDpiIconButtonStyle.Outline,
                                        interactionSource = rememberPreviewInteractionSource(pressed = true),
                                    )
                                },
                            )
                            add(
                                "Focused" to {
                                    RipDpiIconButton(
                                        icon = RipDpiIcons.Settings,
                                        contentDescription = "Settings",
                                        onClick = {},
                                        style = RipDpiIconButtonStyle.Outline,
                                        interactionSource = rememberPreviewInteractionSource(focused = true),
                                    )
                                },
                            )
                        }
                    },
            )

            PreviewMatrixRow(
                title = "Config Fields",
                cells =
                    buildList {
                        add(
                            "Enabled" to {
                                RipDpiConfigTextField(
                                    value = "127.0.0.1",
                                    onValueChange = {},
                                    decoration =
                                        RipDpiTextFieldDecoration(
                                            label = "Proxy IP",
                                            helperText = "Local listener address",
                                        ),
                                )
                            },
                        )
                        add(
                            "Disabled" to {
                                RipDpiConfigTextField(
                                    value = "127.0.0.1",
                                    onValueChange = {},
                                    decoration =
                                        RipDpiTextFieldDecoration(
                                            label = "Proxy IP",
                                            helperText = "Local listener address",
                                        ),
                                    behavior = RipDpiTextFieldBehavior(enabled = false),
                                )
                            },
                        )
                        if (includeInteractiveStates) {
                            add(
                                "Focused" to {
                                    RipDpiConfigTextField(
                                        value = "127.0.0.1",
                                        onValueChange = {},
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = "Proxy IP",
                                                helperText = "Local listener address",
                                            ),
                                        behavior =
                                            RipDpiTextFieldBehavior(
                                                interactionSource = rememberPreviewInteractionSource(focused = true),
                                            ),
                                    )
                                },
                            )
                        }
                        add(
                            "Error" to {
                                RipDpiConfigTextField(
                                    value = "999.0.0.1",
                                    onValueChange = {},
                                    decoration =
                                        RipDpiTextFieldDecoration(
                                            label = "Proxy IP",
                                            errorText = "Enter a valid IP address",
                                        ),
                                )
                            },
                        )
                    },
            )

            PreviewMatrixRow(
                title = "Multiline",
                cells =
                    buildList {
                        add(
                            "Enabled" to {
                                RipDpiConfigTextField(
                                    value = "--dpi-desync=fake --dpi-desync-ttl=5",
                                    onValueChange = {},
                                    decoration = RipDpiTextFieldDecoration(label = "Command line"),
                                    multiline = true,
                                )
                            },
                        )
                        add(
                            "Disabled" to {
                                RipDpiConfigTextField(
                                    value = "--dpi-desync=fake --dpi-desync-ttl=5",
                                    onValueChange = {},
                                    decoration = RipDpiTextFieldDecoration(label = "Command line"),
                                    multiline = true,
                                    behavior = RipDpiTextFieldBehavior(enabled = false),
                                )
                            },
                        )
                        if (includeInteractiveStates) {
                            add(
                                "Focused" to {
                                    RipDpiConfigTextField(
                                        value = "--dpi-desync=fake --dpi-desync-ttl=5",
                                        onValueChange = {},
                                        decoration = RipDpiTextFieldDecoration(label = "Command line"),
                                        multiline = true,
                                        behavior =
                                            RipDpiTextFieldBehavior(
                                                interactionSource = rememberPreviewInteractionSource(focused = true),
                                            ),
                                    )
                                },
                            )
                        }
                        add(
                            "Error" to {
                                RipDpiConfigTextField(
                                    value = "--broken",
                                    onValueChange = {},
                                    decoration =
                                        RipDpiTextFieldDecoration(
                                            label = "Command line",
                                            errorText = "Option is not supported in this mode",
                                        ),
                                    multiline = true,
                                )
                            },
                        )
                    },
            )

            PreviewMatrixRow(
                title = "Selection",
                cells =
                    listOf(
                        "Switch Off" to {
                            RipDpiSwitch(checked = false, onCheckedChange = {})
                        },
                        "Switch On" to {
                            RipDpiSwitch(checked = true, onCheckedChange = {})
                        },
                        "Switch Disabled" to {
                            RipDpiSwitch(checked = true, onCheckedChange = {}, enabled = false)
                        },
                        "Dropdown" to {
                            RipDpiDropdown(
                                options = dropdownOptions,
                                selectedValue = "auto",
                                onValueSelected = {},
                                label = "Mode",
                                helperText = "Traffic handling strategy",
                            )
                        },
                    ),
            )

            PreviewMatrixRow(
                title = "Long Text",
                cells =
                    listOf(
                        "Button" to {
                            RipDpiButton(
                                text = "Start local optimization service with diagnostic telemetry",
                                onClick = {},
                            )
                        },
                        "Dropdown" to {
                            RipDpiDropdown(
                                options = dropdownOptions,
                                selectedValue = null,
                                onValueSelected = {},
                                label = "Traffic handling profile",
                                placeholder = "Choose a connection strategy",
                                errorText = "Choose a mode before saving this profile.",
                            )
                        },
                        "Switch" to {
                            RipDpiSwitch(
                                checked = true,
                                onCheckedChange = {},
                                label = "Use system DNS as a safety fallback",
                                helperText =
                                    "Prevents blank connectivity if the tunnel resolver is unavailable.",
                            )
                        },
                        "Text Field" to {
                            RipDpiConfigTextField(
                                value = "",
                                onValueChange = {},
                                decoration =
                                    RipDpiTextFieldDecoration(
                                        label = "Custom startup command",
                                        helperText =
                                            "Long commands wrap, but supporting text should remain readable " +
                                                "at larger scales.",
                                    ),
                                multiline = true,
                                behavior = RipDpiTextFieldBehavior(density = RipDpiControlDensity.Compact),
                            )
                        },
                    ),
            )
        }

        PreviewSection(title = "Semantic Tones") {
            PreviewMatrixRow(
                title = "Surface Roles",
                framed = false,
                cells =
                    listOf(
                        "Card" to {
                            RipDpiCard {
                                Text(
                                    text = "Outlined card",
                                    style = RipDpiThemeTokens.type.bodyEmphasis,
                                    color = RipDpiThemeTokens.colors.foreground,
                                )
                                Text(
                                    text = "Default grouped panel surface.",
                                    style = RipDpiThemeTokens.type.secondaryBody,
                                    color = RipDpiThemeTokens.colors.mutedForeground,
                                )
                            }
                        },
                        "Tonal" to {
                            RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                                Text(
                                    text = "Tonal card",
                                    style = RipDpiThemeTokens.type.bodyEmphasis,
                                    color = RipDpiThemeTokens.colors.foreground,
                                )
                                Text(
                                    text = "Muted container for dense inline groups.",
                                    style = RipDpiThemeTokens.type.secondaryBody,
                                    color = RipDpiThemeTokens.colors.mutedForeground,
                                )
                            }
                        },
                        "Elevated" to {
                            RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                                Text(
                                    text = "Elevated card",
                                    style = RipDpiThemeTokens.type.bodyEmphasis,
                                    color = RipDpiThemeTokens.colors.foreground,
                                )
                                Text(
                                    text = "Raised panel for higher-priority content.",
                                    style = RipDpiThemeTokens.type.secondaryBody,
                                    color = RipDpiThemeTokens.colors.mutedForeground,
                                )
                            }
                        },
                        "Status" to {
                            RipDpiCard(variant = RipDpiCardVariant.Status) {
                                Text(
                                    text = "Status card",
                                    style = RipDpiThemeTokens.type.bodyEmphasis,
                                    color = RipDpiThemeTokens.colors.foreground,
                                )
                                Text(
                                    text = "Neutral emphasis without warning color.",
                                    style = RipDpiThemeTokens.type.secondaryBody,
                                    color = RipDpiThemeTokens.colors.mutedForeground,
                                )
                            }
                        },
                    ),
            )

            PreviewMatrixRow(
                title = "Selectable Cards",
                framed = false,
                cells =
                    listOf(
                        "Selected" to {
                            PresetCard(
                                title = "Automatic",
                                description = "Detect and apply the strongest optimization strategy.",
                                badgeText = "Active",
                                selected = true,
                                onClick = {},
                            )
                        },
                        "Default" to {
                            PresetCard(
                                title = "desync (fake)",
                                description = "Send fake packets before the real request leaves the device.",
                                onClick = {},
                            )
                        },
                        "Disabled" to {
                            PresetCard(
                                title = "SOCKS5 proxy",
                                description = "Tunnel traffic through the local proxy listener.",
                                enabled = false,
                                onClick = {},
                            )
                        },
                    ),
            )

            PreviewMatrixRow(
                title = "Settings Rows",
                framed = false,
                cells =
                    listOf(
                        "Default" to {
                            RipDpiCard(
                                paddingValues =
                                    androidx.compose.foundation.layout
                                        .PaddingValues(0.dp),
                            ) {
                                SettingsRow(
                                    title = "DNS provider",
                                    subtitle = "Cloudflare DoH",
                                    value = "Edit",
                                    showChevron = true,
                                    onClick = {},
                                    variant = SettingsRowVariant.Default,
                                )
                            }
                        },
                        "Tonal" to {
                            SettingsRow(
                                title = "Background diagnostics",
                                subtitle = "Runs after reconnect",
                                checked = true,
                                onCheckedChange = {},
                                variant = SettingsRowVariant.Tonal,
                            )
                        },
                        "Selected" to {
                            SettingsRow(
                                title = "Recommended path",
                                subtitle = "Best result from the latest audit",
                                value = "Apply",
                                showChevron = true,
                                onClick = {},
                                variant = SettingsRowVariant.Selected,
                            )
                        },
                    ),
            )

            PreviewMatrixRow(
                title = "Banners",
                framed = false,
                cells =
                    listOf(
                        "Warning" to {
                            WarningBanner(
                                title = "Validation warning",
                                message = "Double-check the current values before saving the preset.",
                                tone = WarningBannerTone.Warning,
                            )
                        },
                        "Error" to {
                            WarningBanner(
                                title = "Connection failed",
                                message = "The service stopped before the tunnel could be established.",
                                tone = WarningBannerTone.Error,
                            )
                        },
                        "Info" to {
                            WarningBanner(
                                title = "Manual step required",
                                message = "VPN permission must be granted before the service can start.",
                                tone = WarningBannerTone.Info,
                            )
                        },
                        "Restricted" to {
                            WarningBanner(
                                title = "Feature unavailable",
                                message = "This control only applies when command-line mode is enabled.",
                                tone = WarningBannerTone.Restricted,
                            )
                        },
                    ),
            )

            PreviewMatrixRow(
                title = "Snackbars",
                framed = false,
                cells =
                    listOf(
                        "Warning" to {
                            RipDpiSnackbar(
                                message = "Double-check the current values before saving.",
                                actionLabel = "Open",
                                onAction = {},
                                tone = RipDpiSnackbarTone.Warning,
                            )
                        },
                        "Error" to {
                            RipDpiSnackbar(
                                message = "The service stopped before the tunnel could be established.",
                                tone = RipDpiSnackbarTone.Error,
                            )
                        },
                        "Info" to {
                            RipDpiSnackbar(
                                message = "VPN permission still needs to be granted.",
                                tone = RipDpiSnackbarTone.Info,
                            )
                        },
                        "Restricted" to {
                            RipDpiSnackbar(
                                message = "This option only applies when command-line mode is enabled.",
                                tone = RipDpiSnackbarTone.Restricted,
                            )
                        },
                    ),
            )

            PreviewMatrixRow(
                title = "Log Rows",
                cells =
                    listOf(
                        "DNS" to {
                            LogRow(
                                timestamp = "02:14:38",
                                type = "dns",
                                message = "Resolver switched to 1.1.1.1 after the tunnel became active.",
                                tone = LogRowTone.Dns,
                            )
                        },
                        "Connection" to {
                            LogRow(
                                timestamp = "02:14:42",
                                type = "conn",
                                message = "VPN service started on 127.0.0.1:1080.",
                                tone = LogRowTone.Connection,
                            )
                        },
                        "Warning" to {
                            LogRow(
                                timestamp = "02:14:47",
                                type = "warn",
                                message = "Fallback DNS is active for the current network.",
                                tone = LogRowTone.Warning,
                            )
                        },
                        "Error" to {
                            LogRow(
                                timestamp = "02:14:51",
                                type = "error",
                                message = "VPN permission was denied by the system.",
                                tone = LogRowTone.Error,
                            )
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PreviewSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        content()
    }
}

@Composable
private fun PreviewMatrixRow(
    title: String,
    cells: List<Pair<String, @Composable () -> Unit>>,
    framed: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            cells.forEach { (label, content) ->
                PreviewMatrixCell(label = label, framed = framed, content = content)
            }
        }
    }
}

@Composable
private fun PreviewMatrixCell(
    label: String,
    framed: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    if (framed) {
        Surface(
            modifier = Modifier.width(248.dp),
            shape = MaterialTheme.shapes.large,
            color = colors.card,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(RipDpiStroke.Thin, colors.cardBorder),
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = label,
                    style = RipDpiThemeTokens.type.smallLabel,
                    color = colors.mutedForeground,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(min = 0.dp)
                            .heightIn(min = 56.dp),
                ) {
                    content()
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.width(248.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = label,
                style = RipDpiThemeTokens.type.smallLabel,
                color = colors.mutedForeground,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(min = 0.dp)
                        .heightIn(min = 56.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun rememberPreviewInteractionSource(
    pressed: Boolean = false,
    focused: Boolean = false,
): MutableInteractionSource {
    val interactionSource = remember(pressed, focused) { MutableInteractionSource() }

    LaunchedEffect(interactionSource, pressed, focused) {
        if (focused) {
            interactionSource.emit(FocusInteraction.Focus())
        }
        if (pressed) {
            interactionSource.emit(PressInteraction.Press(Offset.Zero))
        }
    }

    return interactionSource
}

@Preview(name = "Catalog Light Compact", showBackground = true, widthDp = 390, heightDp = 2200)
@Composable
private fun RipDpiDesignSystemPreviewMatrixLightPreview() {
    RipDpiDesignSystemPreviewMatrixCatalog(themePreference = "light")
}

@Preview(name = "Catalog Light Medium", showBackground = true, widthDp = 720, heightDp = 2200)
@Composable
private fun RipDpiDesignSystemPreviewMatrixMediumPreview() {
    RipDpiDesignSystemPreviewMatrixCatalog(themePreference = "light")
}

@Preview(name = "Catalog Light Expanded", showBackground = true, widthDp = 1040, heightDp = 2200)
@Composable
private fun RipDpiDesignSystemPreviewMatrixExpandedPreview() {
    RipDpiDesignSystemPreviewMatrixCatalog(themePreference = "light")
}

@Preview(name = "Catalog Large Font", showBackground = true, widthDp = 720, heightDp = 2200, fontScale = 1.3f)
@Composable
private fun RipDpiDesignSystemPreviewMatrixLargeFontPreview() {
    RipDpiDesignSystemPreviewMatrixCatalog(themePreference = "light")
}

@Preview(name = "Catalog Dark", showBackground = true, widthDp = 720, heightDp = 2200)
@Composable
private fun RipDpiDesignSystemPreviewMatrixDarkPreview() {
    RipDpiDesignSystemPreviewMatrixCatalog(themePreference = "dark")
}
