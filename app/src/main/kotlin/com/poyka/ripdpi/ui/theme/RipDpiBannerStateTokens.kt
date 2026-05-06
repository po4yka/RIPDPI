package com.poyka.ripdpi.ui.theme

import androidx.compose.runtime.Immutable

@Immutable
data class RipDpiBannerStateTokens(
    private val colors: RipDpiExtendedColors,
) {
    fun resolve(role: RipDpiBannerStateRole): RipDpiBannerStateStyle =
        when (role) {
            RipDpiBannerStateRole.Warning -> {
                RipDpiBannerStateStyle(
                    container = colors.warningContainer,
                    border = colors.warning.copy(alpha = 0.52f),
                    iconContainer = colors.warning.copy(alpha = 0.12f),
                    icon = colors.warning,
                    title = colors.warningContainerForeground,
                    message = colors.warningContainerForeground,
                )
            }

            RipDpiBannerStateRole.Error -> {
                RipDpiBannerStateStyle(
                    container = colors.destructiveContainer,
                    border = colors.destructive.copy(alpha = 0.52f),
                    iconContainer = colors.destructive.copy(alpha = 0.12f),
                    icon = colors.destructive,
                    title = colors.destructiveContainerForeground,
                    message = colors.destructiveContainerForeground,
                )
            }

            RipDpiBannerStateRole.Info -> {
                RipDpiBannerStateStyle(
                    container = colors.infoContainer,
                    border = colors.info.copy(alpha = 0.48f),
                    iconContainer = colors.info.copy(alpha = 0.12f),
                    icon = colors.info,
                    title = colors.infoContainerForeground,
                    message = colors.infoContainerForeground,
                )
            }

            RipDpiBannerStateRole.Restricted -> {
                RipDpiBannerStateStyle(
                    container = colors.restrictedContainer,
                    border = colors.restricted.copy(alpha = 0.52f),
                    iconContainer = colors.restricted.copy(alpha = 0.12f),
                    icon = colors.restricted,
                    title = colors.restrictedContainerForeground,
                    message = colors.restrictedContainerForeground,
                )
            }
        }
}
