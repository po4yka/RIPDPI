package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrWhitelistDetectorTest {
    @Test
    fun regularUrlSucceedsReturnsOk() =
        runTest {
            val detector =
                detector(
                    whitelisted = listOf("https://vk.example/"),
                    regular = listOf("https://github.example/"),
                    probe = FakeUrlProbe(successUrls = setOf("https://github.example/")),
                )

            val result = detector.detect(timeoutMs = 1_000)

            assertEquals(CidrWhitelistVerdict.OK, result.verdict)
            assertEquals(1, result.traces.count { it.group == CidrWhitelistUrlGroup.REGULAR && it.ok })
        }

    @Test
    fun onlyWhitelistedSucceedsReturnsWhitelistDetected() =
        runTest {
            val detector =
                detector(
                    whitelisted = listOf("https://vk.example/"),
                    regular = listOf("https://github.example/"),
                    probe = FakeUrlProbe(successUrls = setOf("https://vk.example/")),
                )

            val result = detector.detect(timeoutMs = 1_000)

            assertEquals(CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED, result.verdict)
        }

    @Test
    fun neitherGroupSucceedsReturnsNoInternet() =
        runTest {
            val detector =
                detector(
                    whitelisted = listOf("https://vk.example/"),
                    regular = listOf("https://github.example/"),
                    probe = FakeUrlProbe(successUrls = emptySet()),
                )

            val result = detector.detect(timeoutMs = 1_000)

            assertEquals(CidrWhitelistVerdict.NO_INTERNET, result.verdict)
        }

    @Test
    fun firstRegularSuccessCancelsRemainingProbes() =
        runTest {
            val slowUrl = "https://slow.example/"
            val detector =
                detector(
                    whitelisted = listOf(slowUrl),
                    regular = listOf("https://fast.example/", slowUrl),
                    probe =
                        FakeUrlProbe(
                            successUrls = setOf("https://fast.example/"),
                            delayedUrls = setOf(slowUrl),
                        ),
                )

            val result = detector.detect(timeoutMs = 10_000)

            assertEquals(CidrWhitelistVerdict.OK, result.verdict)
            assertTrue(result.traces.none { trace -> trace.url == slowUrl && trace.ok })
        }

    @Test
    fun tracesIncludeUrlGroupStatusLatencyAndErrors() =
        runTest {
            val detector =
                detector(
                    whitelisted = listOf("https://vk.example/"),
                    regular = listOf("https://github.example/"),
                    probe = FakeUrlProbe(successUrls = setOf("https://vk.example/")),
                )

            val result = detector.detect(timeoutMs = 1_000)

            assertEquals(
                setOf(CidrWhitelistUrlGroup.WHITELISTED, CidrWhitelistUrlGroup.REGULAR),
                result.traces.map { trace -> trace.group }.toSet(),
            )
            assertTrue(result.traces.all { trace -> trace.latencyMs >= 0 })
            assertEquals(
                "blocked",
                result.traces.single { trace -> trace.group == CidrWhitelistUrlGroup.REGULAR }.error,
            )
        }

    private fun detector(
        whitelisted: List<String>,
        regular: List<String>,
        probe: CidrWhitelistUrlProbe,
    ): CidrWhitelistDetector =
        CidrWhitelistDetector(
            whitelistedUrls = whitelisted,
            regularUrls = regular,
            urlProbe = probe,
        )

    private class FakeUrlProbe(
        private val successUrls: Set<String>,
        private val delayedUrls: Set<String> = emptySet(),
    ) : CidrWhitelistUrlProbe {
        override suspend fun probe(
            url: String,
            group: CidrWhitelistUrlGroup,
            timeoutMs: Long,
        ): CidrWhitelistProbeTrace {
            if (url in delayedUrls) {
                try {
                    delay(timeoutMs)
                } catch (error: CancellationException) {
                    throw error
                }
            }
            val ok = url in successUrls
            return CidrWhitelistProbeTrace(
                url = url,
                group = group,
                ok = ok,
                statusCode = if (ok) 204 else null,
                latencyMs = if (url in delayedUrls) timeoutMs else 1,
                error = if (ok) null else "blocked",
            )
        }
    }
}
