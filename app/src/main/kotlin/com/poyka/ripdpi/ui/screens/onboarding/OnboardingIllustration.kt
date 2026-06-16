package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * The "private by design" brand illustration drawn on the intro page, tinted to the theme
 * foreground so its negative space tracks the page background — black-on-light, legible-on-dark.
 */
@Composable
internal fun OnboardingIllustrationBox(modifier: Modifier = Modifier) {
    val colors = RipDpiThemeTokens.colors
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_onboarding_localfirst),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.foreground),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
