package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.ui.screens.home.HomePauseControls
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomePauseScreenshotTest {
    @Test fun pausedLight() = capture("paused_light", paused())

    @Test fun pausedDark() = capture("paused_dark", paused(), dark = true)

    @Test fun releasingLight() = capture("releasing_light", paused().copy(phase = PausePhase.Releasing))

    @Test fun resumingDark() = capture("resuming_dark", paused().copy(phase = PausePhase.Resuming), dark = true)

    @Test fun cleanupPendingDark() =
        capture("cleanup_pending_dark", paused().copy(phase = PausePhase.CleanupPending), dark = true)

    @Test fun deferredConsentLight() =
        capture(
            "deferred_consent_light",
            paused().copy(phase = PausePhase.Deferred, failure = PauseFailure.ConsentRequired),
        )

    @Test fun initialPersistenceFailureLight() =
        capture("persistence_failure_light", HomePauseUiState(available = true, requestFailed = true))

    @Test
    @Config(qualifiers = "ar-rEG")
    fun pausedRtlFont2() = capture("paused_rtl_font2", paused(), rtl = true, font = 2f)

    private fun capture(
        name: String,
        state: HomePauseUiState,
        dark: Boolean = false,
        rtl: Boolean = false,
        font: Float = 1f,
    ) = withUtcScreenshotTime {
        captureSingle(
            name = name,
            widthDp = 411,
            heightDp = 900,
            darkMode = dark,
            fontScale = font,
            layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            testClassFqn = javaClass.name,
        ) {
            HomePauseControls(state, {}, {}, {})
        }
    }

    private fun paused() =
        HomePauseUiState(
            phase = PausePhase.Paused,
            mode = Mode.VPN,
            deadlineWallMillis = 1_791_224_100_000L,
            remainingMillis = 300_000L,
        )
}
