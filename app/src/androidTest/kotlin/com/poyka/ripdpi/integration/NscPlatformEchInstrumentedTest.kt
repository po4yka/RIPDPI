package com.poyka.ripdpi.integration

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.EchReadinessProbe
import com.poyka.ripdpi.diagnostics.dpi.NativeEchTlsHandshake
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Android 17 (API 37) platform-ECH-from-Network-Security-Config check.
 *
 * The open acceptance criterion for the documented `domainEncryption mode="enabled"`
 * Network Security Config is: "the platform stack attempts ECH from NSC when
 * DNS supplies an ECH config." Android exposes **no API** that reports whether
 * the platform actually attempted ECH on a given connection, so this test
 * cannot assert the platform behaviour *directly*. It instead gathers the
 * strongest evidence available on a real API-37 device:
 *
 *  1. Our native rustls bridge negotiates ECH for an ECH-enabled host (proves
 *     the HTTPS RR carries a usable ECH config and ECH is negotiable from this
 *     network) — the same signal the existing [EchReadinessProbeInstrumentedTest]
 *     uses, here gated to Android 17+.
 *  2. The **platform** TLS stack (`HttpsURLConnection`, which honours the
 *     `@xml-v37/network_security_config` enabled `domainEncryption` on API 37)
 *     completes a request to the same ECH host. A successful connection proves
 *     only that the configured platform path preserves connectivity; it does
 *     not prove that this connection used ECH.
 *
 * This is honest about its limits: step 2 is necessary-but-not-sufficient proof
 * of an ECH *attempt*. Direct verification would require platform telemetry
 * Android does not currently surface, or a packet capture asserting an
 * encrypted ClientHello inner SNI. Skipped cleanly below API 37.
 */
class NscPlatformEchInstrumentedTest {
    @Test
    fun platformReachesEchHostWithEnabledDomainEncryptionOnApi37() =
        runBlocking {
            assumeTrue(
                "Platform ECH-from-NSC is an Android 17 (API 37+) behaviour",
                Build.VERSION.SDK_INT >= API_ANDROID_17,
            )
            assumeTrue(
                "Set RIPDPI_RUN_NETWORK_TESTS=1 or -Pandroid.testInstrumentationRunnerArguments.ripdpi.runNetworkTests=1",
                networkTestsEnabled(),
            )

            // (1) Native bridge: confirm an ECH config is published and negotiable.
            val nativeResult =
                EchReadinessProbe(tlsHandshake = NativeEchTlsHandshake())
                    .check(ECH_TEST_HOST, vanillaTlsOk = true)
            assertEquals(
                "Expected $ECH_TEST_HOST to publish an HTTPS RR with an ECH config: $nativeResult",
                true,
                nativeResult.httpsRrFetched,
            )
            assertEquals(
                "Expected the native rustls bridge to negotiate ECH on Android 17: $nativeResult",
                EchProbeVerdict.ECH_OK,
                nativeResult.verdict,
            )

            // (2) Platform stack: a request to the same ECH host completes. On
            // API 37 this connection is governed by the enabled domainEncryption
            // base-config in @xml-v37/network_security_config.
            val status = platformHttpsStatus("https://$ECH_TEST_HOST/cdn-cgi/trace")
            assertTrue(
                "Expected the platform TLS stack to reach $ECH_TEST_HOST with enabled domainEncryption; got HTTP $status",
                status in 200..399,
            )
        }

    private fun platformHttpsStatus(url: String): Int {
        val connection = URL(url).openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = PLATFORM_TIMEOUT_MS
            connection.readTimeout = PLATFORM_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun networkTestsEnabled(): Boolean =
        System.getenv("RIPDPI_RUN_NETWORK_TESTS") == "1" ||
            InstrumentationRegistry
                .getArguments()
                .getString("ripdpi.runNetworkTests") == "1"

    private companion object {
        const val API_ANDROID_17 = 37
        const val ECH_TEST_HOST = "cloudflare-ech.com"
        const val PLATFORM_TIMEOUT_MS = 10_000
    }
}
