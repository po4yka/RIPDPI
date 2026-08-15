package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.diagnostics.HandshakeTimelineScreen
import com.poyka.ripdpi.ui.screens.diagnostics.sampleHandshakeTimelineState
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Smoke test for the RTL Roborazzi capture infrastructure (`captureThreeVariants`
 * in `RoborazziCaptureHelpers.kt`).
 *
 * Exercises the helper on a small set of representative components to prove the
 * pipeline (light / dark / RTL light) is wired up end-to-end. The `_rtl` PNGs
 * are NOT yet blessed — actual RTL goldens land via an explicit user-invoked
 * bless step governed by `.claude/rules/golden-bless-discipline.md`.
 *
 * Currently `@Ignore`d so `verifyRoborazziDebug` stays green against the existing
 * blessed light/dark goldens. To bless the RTL surface:
 *   1. Remove `@Ignore` below.
 *   2. Run `RIPDPI_BLESS_GOLDENS=1 ./gradlew :app:recordRoborazziDebug --tests '*RtlVariantGalleryTest*'`.
 *   3. Commit the resulting `_light` / `_dark` / `_rtl` PNGs with an
 *      "Approved-bless" rationale per the golden bless discipline rule.
 */
@Ignore("RTL infrastructure smoke test — goldens not yet blessed (see KDoc).")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RtlVariantGalleryTest {
    private val testClassFqn = "com.poyka.ripdpi.ui.screenshot.RtlVariantGalleryTest"

    @Test
    fun rtlHandshakeTimeline() {
        captureThreeVariants(
            name = "rtlHandshakeTimeline",
            widthDp = 720,
            heightDp = 520,
            testClassFqn = testClassFqn,
        ) {
            HandshakeTimelineScreen(
                state =
                    sampleHandshakeTimelineState(
                        title = "Handshake timeline",
                        subtitle = "cloudflare-dns.com · UDP/443 · attempt 1 of 3",
                        totalLabel = "First packet ready",
                        footerSlowest = "Slowest stage MTU probe · > budget by 100 ms",
                        footerBudget = "Next attempt budget 2.0 s",
                    ),
            )
        }
    }
}
