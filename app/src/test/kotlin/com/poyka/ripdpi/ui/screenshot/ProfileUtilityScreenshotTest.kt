package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.screens.profiles.ProfileCatalogState
import com.poyka.ripdpi.ui.screens.profiles.ProfileMeasurementUiState
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityActions
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityFailure
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityItem
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityScreen
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityTestTags
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileUtilityScreenshotTest {
    @Test fun nativeMeasuredLight() = capture("native_measured_light", state())

    @Test fun nativeMeasuredDark() = capture("native_measured_dark", state(), dark = true)

    @Test fun xrayCheckingLight() =
        capture(
            "xray_checking_light",
            state(
                item = item(ProfileUtilityReference.Xray("xray-one"), measurement = ProfileMeasurementUiState.Checking),
            ),
        )

    @Test fun selectorMeasuredDark() =
        capture(
            "selector_measured_dark",
            state(item = item(ProfileUtilityReference.SelectorMember("travel", "member-one"))),
            dark = true,
        )

    @Test fun failedLight() =
        capture(
            "failed_light",
            state(item = item(measurement = ProfileMeasurementUiState.Failed(ProfileUtilityFailure.HttpFailed))),
        )

    @Test fun cleanupPendingDark() =
        capture(
            "cleanup_pending_dark",
            state().copy(cleanupPending = true, failure = ProfileUtilityFailure.CleanupPending),
            dark = true,
        )

    @Test fun loadingLight() =
        capture(
            "loading_light",
            state().copy(profiles = persistentListOf(), catalogState = ProfileCatalogState.Loading, canMeasure = false),
        )

    @Test fun emptyDark() = capture("empty_dark", state().copy(profiles = persistentListOf()), dark = true)

    @Test fun catalogFailureLight() =
        capture(
            "catalog_failure_light",
            state().copy(
                profiles = persistentListOf(),
                catalogState = ProfileCatalogState.Failed,
                failure = ProfileUtilityFailure.Persistence,
            ),
        )

    @Test fun catalogFailureDark() =
        capture(
            "catalog_failure_dark",
            state().copy(
                profiles = persistentListOf(),
                catalogState = ProfileCatalogState.Failed,
                failure = ProfileUtilityFailure.Persistence,
            ),
            dark = true,
        )

    @Test fun nativeMeasuredFont2() = capture("native_measured_font2", state(), font = 2f)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun nativeMeasuredRtlFont2() = capture("native_measured_rtl_font2", state(), rtl = true, font = 2f)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun cleanupPendingRtlFont2() =
        capture(
            "cleanup_pending_rtl_font2",
            state().copy(cleanupPending = true, failure = ProfileUtilityFailure.CleanupPending),
            rtl = true,
            font = 2f,
        )

    private fun capture(
        name: String,
        state: ProfileUtilityUiState,
        dark: Boolean = false,
        rtl: Boolean = false,
        font: Float = 1f,
    ) {
        val previousTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            captureSingle(
                name = name,
                widthDp = 411,
                heightDp = if (font > 1f) 1600 else 1000,
                darkMode = dark,
                fontScale = font,
                layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                testClassFqn = javaClass.name,
            ) {
                ProfileUtilityScreen(
                    state = state,
                    onBack = {},
                    actions = ProfileUtilityActions({ _, _, _ -> }, {}, {}, {}, {}, {}, {}),
                )
            }
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    private fun state(item: ProfileUtilityItem = item()) =
        ProfileUtilityUiState(
            catalogGeneration = 7,
            profiles = persistentListOf(item),
            probeUrl = "https://check.example/health",
            catalogState = ProfileCatalogState.Ready,
            canMeasure = true,
            cleanupPending = false,
            failure = null,
        )

    private fun item(
        reference: ProfileUtilityReference = ProfileUtilityReference.NativeRelay("native-one"),
        measurement: ProfileMeasurementUiState = ProfileMeasurementUiState.Measured(128, ObservationTime),
    ) = ProfileUtilityItem(
        reference = reference,
        label = "Personal connection",
        groupLabel = if (reference is ProfileUtilityReference.SelectorMember) "Travel profiles" else null,
        kind = if (reference is ProfileUtilityReference.Xray) "VLESS" else "Shadowsocks",
        favorite = true,
        applied = true,
        lastUsedAtMillis = ObservationTime,
        recentSequence = 3,
        measurement = measurement,
    )

    private companion object {
        const val ObservationTime = 1_791_228_000_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-mdpi")
class ProfileUtilityLargeFontControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `large font LTR keeps both full action labels usable on a phone`() = assertActions(LayoutDirection.Ltr)

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h900dp-mdpi")
    fun `large font Arabic RTL keeps both full action labels usable on a phone`() = assertActions(LayoutDirection.Rtl)

    private fun assertFastestAction() {
        val fastest = composeRule.onNodeWithTag(ProfileUtilityTestTags.Fastest).performScrollTo().assertIsDisplayed()
        val fastestText =
            composeRule.onNodeWithText(
                RuntimeEnvironment.getApplication().getString(R.string.profile_utility_check_fastest),
                useUnmergedTree = true,
            )
        val fastestLayouts = mutableListOf<TextLayoutResult>()
        fastestText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(fastestLayouts) }
        assertTrue(fastestLayouts.isNotEmpty())
        fastestLayouts.forEach { assertFalse(it.hasVisualOverflow) }
        assertTrue(fastest.fetchSemanticsNode().boundsInRoot.height >= 48f)
        fastest.performClick()
    }

    private fun assertActions(direction: LayoutDirection) {
        val reference = ProfileUtilityReference.NativeRelay("large-font")
        val checks = mutableListOf<ProfileUtilityReference>()
        val selections = mutableListOf<ProfileUtilityReference>()
        var fastestChecks = 0
        val state =
            ProfileUtilityUiState(
                7,
                persistentListOf(
                    ProfileUtilityItem(
                        reference,
                        "Personal connection",
                        null,
                        "Shadowsocks",
                        true,
                        true,
                        null,
                        null,
                        ProfileMeasurementUiState.NotChecked,
                    ),
                ),
                "https://check.example/health",
                ProfileCatalogState.Ready,
                true,
                false,
                null,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides direction) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(411.dp, 900.dp)) {
                        ProfileUtilityScreen(
                            state,
                            {},
                            ProfileUtilityActions(
                                { _, _, _ -> },
                                checks::add,
                                selections::add,
                                {},
                                {},
                                {},
                                { fastestChecks++ },
                            ),
                        )
                    }
                }
            }
        }
        assertFastestAction()
        assertEquals(1, fastestChecks)
        for ((action, label) in listOf(
            "check" to R.string.profile_utility_check,
            "check_select" to R.string.profile_utility_check_select,
        )) {
            val button =
                composeRule
                    .onNodeWithTag(ProfileUtilityTestTags.action(reference, action))
                    .performScrollTo()
                    .assertIsDisplayed()
            val buttonBounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(buttonBounds.height >= 48f)
            val text =
                composeRule.onNodeWithText(
                    RuntimeEnvironment.getApplication().getString(label),
                    useUnmergedTree = true,
                )
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertFalse(it.hasVisualOverflow) }
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            assertTrue(textBounds.left >= buttonBounds.left && textBounds.right <= buttonBounds.right)
            assertTrue(textBounds.top >= buttonBounds.top && textBounds.bottom <= buttonBounds.bottom)
            button.performClick()
        }
        assertEquals(listOf(reference), checks)
        assertEquals(listOf(reference), selections)
    }
}
