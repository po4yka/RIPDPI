package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.diagnostics.dpich.AliveState
import com.poyka.ripdpi.diagnostics.dpich.DpiState
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkDecodeError
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkItem
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkPayload
import com.poyka.ripdpi.ui.screens.diagnostics.share.SharedResultRenderScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SharedResultRenderScreenshotTest {
    @Test
    fun populatedSharedResult() {
        capture(
            "populatedSharedResult",
            Result.success(
                ShareLinkPayload(
                    commitHash = 0xab,
                    // Packed share-link timestamp is 24-bit (max 16_777_215 minutes).
                    timestampMinutes = 16_000_000,
                    asn = 13_335,
                    items =
                        listOf(
                            ShareLinkItem(alive = AliveState.YES, dpi = DpiState.NOT_DETECTED),
                            ShareLinkItem(alive = AliveState.NO, dpi = DpiState.DETECTED),
                            ShareLinkItem(alive = AliveState.YES, dpi = DpiState.PROBABLY),
                            ShareLinkItem(alive = AliveState.UNKNOWN, dpi = DpiState.POSSIBLE),
                            ShareLinkItem(alive = AliveState.YES, dpi = DpiState.UNLIKELY),
                        ),
                ),
            ),
        )
    }

    @Test
    fun emptySharedResult() {
        capture(
            "emptySharedResult",
            Result.success(
                ShareLinkPayload(
                    commitHash = 0x00,
                    timestampMinutes = 0,
                    asn = 0,
                    items = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun invalidSharedResult() {
        capture(
            "invalidSharedResult",
            Result.failure(ShareLinkDecodeError("Malformed share-link fragment")),
        )
    }

    private fun capture(
        name: String,
        decoded: Result<ShareLinkPayload>,
    ) {
        captureScreenBothThemes(
            name = name,
            widthDp = 420,
            heightDp = 900,
            testClassFqn = FQN,
        ) {
            SharedResultRenderScreen(
                decoded = decoded,
                onBack = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.SharedResultRenderScreenshotTest"
    }
}
