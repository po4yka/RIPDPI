package com.poyka.ripdpi.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.delay

private const val SplashDelayMillis = 1_500L

@Composable
fun SplashPlaceholderScreen(
    onboardingComplete: Boolean,
    onFinished: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val appName = stringResource(R.string.app_name)

    LaunchedEffect(onboardingComplete) {
        delay(SplashDelayMillis)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(RipDpiThemeTokens.layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            Text(
                text = appName,
                style = type.screenTitle,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.placeholder_message),
                style = type.body,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun OnboardingPlaceholderScreen(
    onComplete: () -> Unit,
) {
    PlaceholderCenterScreen(
        title = stringResource(R.string.title_onboarding),
        actionText = stringResource(R.string.complete_onboarding),
        onAction = onComplete,
    )
}

@Composable
fun HomePlaceholderScreen() {
    val appName = stringResource(R.string.app_name)
    TopLevelPlaceholderScreen(
        title = appName,
        brandGlyph = appName.take(1),
    )
}

@Composable
fun ConfigPlaceholderScreen(
    onOpenModeEditor: () -> Unit,
    onOpenDnsSettings: () -> Unit,
) {
    TopLevelPlaceholderScreen(
        title = stringResource(R.string.config),
        primaryActionText = stringResource(R.string.title_mode_editor),
        onPrimaryAction = onOpenModeEditor,
        secondaryActionText = stringResource(R.string.title_dns_settings),
        onSecondaryAction = onOpenDnsSettings,
    )
}

@Composable
fun SettingsPlaceholderScreen(
    onOpenCustomization: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    TopLevelPlaceholderScreen(
        title = stringResource(R.string.settings),
        primaryActionText = stringResource(R.string.title_app_customization),
        onPrimaryAction = onOpenCustomization,
        secondaryActionText = stringResource(R.string.about_category),
        onSecondaryAction = onOpenAbout,
    )
}

@Composable
fun LogsPlaceholderScreen(
    onSaveLogs: () -> Unit,
) {
    TopLevelPlaceholderScreen(
        title = stringResource(R.string.logs),
        primaryActionText = stringResource(R.string.save_logs),
        onPrimaryAction = onSaveLogs,
    )
}

@Composable
fun NestedPlaceholderScreen(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RipDpiTopAppBar(
            title = stringResource(titleRes),
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = onBack,
        )
        PlaceholderBody(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layout.horizontalPadding),
        )
    }
}

@Composable
private fun TopLevelPlaceholderScreen(
    title: String,
    brandGlyph: String? = null,
    primaryActionText: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RipDpiTopAppBar(
            title = title,
            brandGlyph = brandGlyph,
        )
        PlaceholderBody(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layout.horizontalPadding),
            primaryActionText = primaryActionText,
            onPrimaryAction = onPrimaryAction,
            secondaryActionText = secondaryActionText,
            onSecondaryAction = onSecondaryAction,
        )
    }
}

@Composable
private fun PlaceholderCenterScreen(
    title: String,
    actionText: String,
    onAction: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = title,
                style = type.screenTitle,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.placeholder_message),
                style = type.body,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
            RipDpiButton(
                text = actionText,
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun PlaceholderBody(
    modifier: Modifier = Modifier,
    primaryActionText: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.placeholder_message),
                style = type.body,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
            if (primaryActionText != null && onPrimaryAction != null) {
                RipDpiButton(
                    text = primaryActionText,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (secondaryActionText != null && onSecondaryAction != null) {
                RipDpiButton(
                    text = secondaryActionText,
                    onClick = onSecondaryAction,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RipDpiButtonVariant.Secondary,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TopLevelPlaceholderPreview() {
    RipDpiTheme(themePreference = "light") {
        ConfigPlaceholderScreen(
            onOpenModeEditor = {},
            onOpenDnsSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NestedPlaceholderDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        NestedPlaceholderScreen(
            titleRes = R.string.title_dns_settings,
            onBack = {},
        )
    }
}
