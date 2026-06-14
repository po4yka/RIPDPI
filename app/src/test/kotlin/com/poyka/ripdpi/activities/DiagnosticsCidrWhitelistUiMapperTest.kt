package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetectionResult
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistProbeTrace
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistUrlGroup
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistVerdict
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsCidrWhitelistUiMapperTest {
    private val stringResolver = ResourceStringResolver()

    @Test
    fun `cidr whitelist result renders verdict metrics and traces`() {
        val model =
            CidrWhitelistDetectionResult(
                verdict = CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED,
                traces =
                    listOf(
                        trace("https://vk.example/", CidrWhitelistUrlGroup.WHITELISTED, ok = true),
                        trace("https://github.example/", CidrWhitelistUrlGroup.REGULAR, ok = false),
                    ),
            ).toUiModel(stringResolver)

        assertEquals(DiagnosticsCidrWhitelistState.Complete, model.state)
        assertTrue(model.summary.contains("CIDR whitelist pattern"))
        assertEquals("1/1", model.metrics.first { metric -> metric.label == "whitelisted" }.value)
        assertEquals("0/1", model.metrics.first { metric -> metric.label == "regular" }.value)
        assertEquals("whitelisted", model.rows.first().group)
        assertEquals("blocked", model.rows.last().error)
    }

    private fun trace(
        url: String,
        group: CidrWhitelistUrlGroup,
        ok: Boolean,
    ): CidrWhitelistProbeTrace =
        CidrWhitelistProbeTrace(
            url = url,
            group = group,
            ok = ok,
            statusCode = if (ok) 204 else null,
            latencyMs = 7,
            error = if (ok) null else "blocked",
        )

    private class ResourceStringResolver : StringResolver {
        private val application: Application = RuntimeEnvironment.getApplication()

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = application.getString(resId, *formatArgs)
    }
}
