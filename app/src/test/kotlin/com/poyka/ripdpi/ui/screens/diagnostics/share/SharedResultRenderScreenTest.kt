package com.poyka.ripdpi.ui.screens.diagnostics.share

import android.content.Intent
import android.net.Uri
import com.poyka.ripdpi.diagnostics.dpich.AliveState
import com.poyka.ripdpi.diagnostics.dpich.DiagnosticShareLinkMaxFragmentChars
import com.poyka.ripdpi.diagnostics.dpich.DpiState
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkItem
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedResultRenderScreenTest {
    @Test
    fun `deep link intent extracts fragment correctly`() {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://po4yka.github.io/RIPDPI/share?v=1#abc_Def-123"),
            )

        assertEquals("abc_Def-123", DiagnosticShareLinkDeepLink.fragmentFrom(intent))
    }

    @Test
    fun `deep link rejects oversized fragment`() {
        val fragment = "A".repeat(DiagnosticShareLinkMaxFragmentChars + 1)
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://po4yka.github.io/RIPDPI/share?v=1#$fragment"),
            )

        assertNull(DiagnosticShareLinkDeepLink.fragmentFrom(intent))
    }

    @Test
    fun `deep link rejects percent encoded fragment`() {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://po4yka.github.io/RIPDPI/share?v=1#abc%2Fdef"),
            )

        assertNull(DiagnosticShareLinkDeepLink.fragmentFrom(intent))
    }

    @Test
    fun `deep link rejects unsupported share schema version`() {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://po4yka.github.io/RIPDPI/share?v=2#abc_Def-123"),
            )

        assertNull(DiagnosticShareLinkDeepLink.fragmentFrom(intent))
    }

    @Test
    fun `render shows asn zero as unknown origin`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x21,
                timestampMinutes = 0,
                asn = 0,
                items = listOf(ShareLinkItem(AliveState.UNKNOWN, DpiState.POSSIBLE)),
            )

        assertEquals("Unknown origin", SharedResultRenderFormatter.originLabel(payload))
    }
}
