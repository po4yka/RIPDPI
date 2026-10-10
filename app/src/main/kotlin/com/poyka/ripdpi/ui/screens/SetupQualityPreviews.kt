package com.poyka.ripdpi.ui.screens

import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.RememberedNetworksUiState
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsEditorField
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileEditorState
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileScreen
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileUiState
import com.poyka.ripdpi.ui.screens.routes.RuleEditorFailure
import com.poyka.ripdpi.ui.screens.routes.RuleEditorPersistenceActions
import com.poyka.ripdpi.ui.screens.routes.RuleEditorScreen
import com.poyka.ripdpi.ui.screens.routes.RuleEditorUiState
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworksScreen
import com.poyka.ripdpi.ui.theme.RipDpiTheme

@Preview(name = "Light compact", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Light large RTL", widthDp = 320, heightDp = 640, fontScale = 2f, locale = "ar", showBackground = true)
@Preview(
    name = "Dark large RTL",
    widthDp = 320,
    heightDp = 640,
    fontScale = 2f,
    locale = "ar",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun setupQualityRuleLoadRecoveryPreview() =
    SetupQualityTheme {
        RuleEditorPreview(RuleEditorUiState(failure = RuleEditorFailure.Load))
    }

@Preview(name = "Light compact", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Light large RTL", widthDp = 320, heightDp = 640, fontScale = 2f, locale = "ar", showBackground = true)
@Preview(
    name = "Dark large RTL",
    widthDp = 320,
    heightDp = 640,
    fontScale = 2f,
    locale = "ar",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun setupQualityRuleSaveRecoveryPreview() =
    SetupQualityTheme {
        RuleEditorPreview(RuleEditorUiState(loaded = true, domains = "example.com", failure = RuleEditorFailure.Save))
    }

@Preview(name = "Light compact", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Light large RTL", widthDp = 320, heightDp = 640, fontScale = 2f, locale = "ar", showBackground = true)
@Preview(
    name = "Dark large RTL",
    widthDp = 320,
    heightDp = 640,
    fontScale = 2f,
    locale = "ar",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun setupQualityRememberedLoadingPreview() =
    SetupQualityTheme {
        RememberedNetworksScreen(RememberedNetworksUiState(), {}, {}, {})
    }

@Preview(name = "Light compact", widthDp = 320, heightDp = 640, showBackground = true)
@Preview(name = "Light large RTL", widthDp = 320, heightDp = 640, fontScale = 2f, locale = "ar", showBackground = true)
@Preview(
    name = "Dark large RTL",
    widthDp = 320,
    heightDp = 640,
    fontScale = 2f,
    locale = "ar",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun setupQualityMaskedCredentialPreview() =
    SetupQualityTheme {
        AnyTlsProfileScreen(
            AnyTlsProfileUiState(
                AnyTlsProfileEditorState.initial().updateField(AnyTlsEditorField.PASSWORD, "preview-value"),
            ),
            {},
            { _, _ -> },
            {},
        )
    }

@Composable
private fun RuleEditorPreview(state: RuleEditorUiState) {
    RuleEditorScreen(
        state,
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        RuleEditorPersistenceActions(save = {}),
    )
}

@Composable
private fun SetupQualityTheme(content: @Composable () -> Unit) {
    val direction =
        if (LocalConfiguration.current.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        RipDpiTheme { content() }
    }
}
