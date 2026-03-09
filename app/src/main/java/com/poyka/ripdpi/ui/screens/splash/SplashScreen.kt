package com.poyka.ripdpi.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.delay

private const val SplashDelayMillis = 1_500L

@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    LaunchedEffect(Unit) {
        delay(SplashDelayMillis)
        onFinished()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .background(colors.foreground, RipDpiThemeTokens.shapes.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "R",
                    style = type.brandGlyph,
                    color = colors.background,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = type.brandMark,
                color = colors.foreground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.splash_subtitle),
                style = type.monoConfig.copy(letterSpacing = 0.325.sp),
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SplashScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        SplashScreen(onFinished = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SplashScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        SplashScreen(onFinished = {})
    }
}
