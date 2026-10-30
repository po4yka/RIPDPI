package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindNaiveProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaiveUpstreamOutputParserTest {
    @Test
    fun `an upstream line shaped like the helper protocol does not flip health`() {
        val spec =
            SubprocessSocksRelayLaunchSpec(
                binaryName = NaiveUpstreamBinaryName,
                commandArguments = emptyList(),
                runtimeKind = RelayKindNaiveProxy,
                redactedValues = listOf("secret"),
            )
        val parser = SubprocessRelayOutputParser()

        assertNull(parser.parse("RIPDPI-UPSTREAM-OK|naiveproxy|", spec))
        assertNull(parser.parse("RIPDPI-READY|naiveproxy|1", spec))
        assertNull(parser.parse("RIPDPI-ERROR|naiveproxy|auth|bad", spec))
        assertNull(parser.parse("[ERROR] leaked secret", spec))
        assertTrue(
            parser.parse("RIPDPI-UPSTREAM-OK|naiveproxy|", spec.copy(binaryName = "ripdpi-naiveproxy")) is
                SubprocessRelayOutputEvent.UpstreamOk,
        )
    }

    private val upstreamSpec =
        SubprocessSocksRelayLaunchSpec(
            binaryName = NaiveUpstreamBinaryName,
            commandArguments = emptyList(),
            runtimeKind = RelayKindNaiveProxy,
            redactedValues = listOf("secret"),
        )

    private val recordedUpstreamLines =
        listOf(
            "[1030/121157.991006:INFO:net/tools/naive/naive_proxy_bin.cc:549] Listening on http://0.0.0.0:1080",
            "[1030/121157.991124:INFO:net/tools/naive/naive_proxy_bin.cc:236] Proxying via HTTPS naive.test:8443",
            "[1030/121159.512501:INFO:net/tools/naive/preamble_getter.cc:236] Preamble https://naive.test:8443/",
            "[1030/121159.519902:INFO:net/tools/naive/naive_connection.cc:286] " +
                "Connection 0 to 127.0.0.1:9100 via HTTPS naive.test:8443",
        )

    private val paddingLine =
        "[1030/121159.520448:INFO:net/tools/naive/naive_proxy_delegate.cc:171] " +
            "https://naive.test:8443 negotiated padding type: Variant1"

    @Test
    fun `upstream parser drops INFO lines and maps evidence lines to UpstreamOk`() {
        val parser = SubprocessRelayOutputParser()

        recordedUpstreamLines.forEach { assertNull(it, parser.parse(it, upstreamSpec)) }
        val ok = SubprocessRelayOutputEvent.UpstreamOk(RelayKindNaiveProxy)
        assertEquals(ok, parser.parse(paddingLine, upstreamSpec))
        assertEquals(
            ok,
            parser.parse(
                "[1030/121224.848214:INFO:net/tools/naive/naive_proxy.cc:288] Connection 0 closed: OK",
                upstreamSpec,
            ),
        )
    }

    @Test
    fun `upstream parser reduces errors to fixed messages without targets`() {
        val parser = SubprocessRelayOutputParser()
        val prefix = "[1030/121224.848214:INFO:net/tools/naive/naive_proxy.cc:288] "

        assertEquals(
            SubprocessRelayOutputEvent.PlainError("upstream connection closed: ERR_HTTP2_PROTOCOL_ERROR"),
            parser.parse(prefix + "Connection 3 closed: ERR_HTTP2_PROTOCOL_ERROR", upstreamSpec),
        )
        assertEquals(
            SubprocessRelayOutputEvent.PlainError("upstream connection closed: net::ERR_PROXY_CONNECTION_FAILED"),
            parser.parse(prefix + "Connection 3 closed: net::ERR_PROXY_CONNECTION_FAILED", upstreamSpec),
        )
        assertEquals(
            SubprocessRelayOutputEvent.PlainError("upstream connection closed with an error"),
            parser.parse(prefix + "Connection 3 closed: to 127.0.0.1:9100 https://naive.test", upstreamSpec),
        )
        val errors =
            listOf(
                "[1030/121224.848214:ERROR:net/tools/naive/naive_connection.cc:99] " +
                    "failed to 127.0.0.1:9100 via https://naive.test:8443 secret",
                "[1030/121224.848214:FATAL:net/tools/naive/naive_proxy_bin.cc:12] bad naive.test",
            )
        val messages = errors.map { (parser.parse(it, upstreamSpec) as SubprocessRelayOutputEvent.PlainError).message }
        assertEquals(
            listOf("upstream client error (naive_connection.cc)", "upstream client error (naive_proxy_bin.cc)"),
            messages,
        )
        assertNull(
            parser.parse("[1030/121224.848214:WARNING:net/tools/naive/naive_connection.cc:9] 127.0.0.1", upstreamSpec),
        )
        val all = messages + "upstream connection closed: ERR_HTTP2_PROTOCOL_ERROR"
        all.forEach { message ->
            listOf("127.0.0.1", "9100", "naive.test", "https://", "secret").forEach { forbidden ->
                assertFalse("$message contains $forbidden", message.contains(forbidden))
            }
        }
    }

    private val closePrefix = "[1030/121224.848214:INFO:net/tools/naive/naive_proxy.cc:288] "
    private val ok = SubprocessRelayOutputEvent.UpstreamOk(RelayKindNaiveProxy)

    @Test
    fun `padding type None is not health evidence`() {
        val parser = SubprocessRelayOutputParser()

        assertNull(parser.parse(paddingLine.replace("Variant1", "None"), upstreamSpec))
        assertEquals(ok, parser.parse(paddingLine, upstreamSpec))
    }

    @Test
    fun `a close OK needs an earlier announced connection and is consumed once`() {
        val parser = SubprocessRelayOutputParser()

        // The local readiness probe connection is never announced with "Connection n to".
        assertNull(parser.parse(closePrefix + "Connection 7 closed: OK", upstreamSpec))
        assertNull(parser.parse(recordedUpstreamLines.last(), upstreamSpec))
        assertEquals(ok, parser.parse(closePrefix + "Connection 0 closed: OK", upstreamSpec))
        assertNull(parser.parse(closePrefix + "Connection 0 closed: OK", upstreamSpec))
    }

    @Test
    fun `a proxy credential rejection is an auth failure`() {
        val parser = SubprocessRelayOutputParser()
        // Recorded from the pinned upstream client with a wrong password against Caddy forwardproxy.
        val rejected = closePrefix + "Connection 0 closed: ERR_PROXY_AUTH_UNSUPPORTED"

        for (code in listOf("ERR_PROXY_AUTH_UNSUPPORTED", "net::ERR_PROXY_AUTH_REQUESTED")) {
            parser.parse(recordedUpstreamLines.last(), upstreamSpec)
            assertEquals(
                SubprocessRelayOutputEvent.Error(
                    upstreamSpec.runtimeKind,
                    "auth",
                    "proxy rejected the credentials: $code",
                ),
                parser.parse(rejected.replace("ERR_PROXY_AUTH_UNSUPPORTED", code), upstreamSpec),
            )
        }
    }

    @Test
    fun `proxy rejection closes produce no health evidence and reset clears announcements`() {
        val parser = SubprocessRelayOutputParser()
        parser.parse(recordedUpstreamLines.last(), upstreamSpec)

        assertEquals(
            SubprocessRelayOutputEvent.PlainError("upstream connection closed: ERR_PROXY_CONNECTION_FAILED"),
            parser.parse(closePrefix + "Connection 0 closed: ERR_PROXY_CONNECTION_FAILED", upstreamSpec),
        )
        parser.parse(recordedUpstreamLines.last().replace("Connection 0", "Connection 2"), upstreamSpec)
        assertEquals(
            SubprocessRelayOutputEvent.PlainError("upstream connection closed: ERR_TUNNEL_CONNECTION_FAILED"),
            parser.parse(closePrefix + "Connection 2 closed: ERR_TUNNEL_CONNECTION_FAILED", upstreamSpec),
        )
        parser.parse(recordedUpstreamLines.last(), upstreamSpec)
        parser.reset()
        assertNull(parser.parse(closePrefix + "Connection 0 closed: OK", upstreamSpec))
    }

    @Test
    fun `destination side close codes produce no event`() {
        val parser = SubprocessRelayOutputParser()

        listOf("ERR_CONNECTION_REFUSED", "net::ERR_NAME_NOT_RESOLVED", "ERR_CONNECTION_CLOSED").forEach { code ->
            assertNull(code, parser.parse(closePrefix + "Connection 3 closed: $code", upstreamSpec))
        }
    }

    @Test
    fun `unrecognized output format is reported once without raw text`() {
        val parser = SubprocessRelayOutputParser()

        val events = (1..30).mapNotNull { parser.parse("unexpected format secret-$it", upstreamSpec) }

        assertEquals(
            listOf<SubprocessRelayOutputEvent>(
                SubprocessRelayOutputEvent.PlainError("upstream client output format not recognized"),
            ),
            events,
        )
        val recognized = SubprocessRelayOutputParser()
        recognized.parse(recordedUpstreamLines.first(), upstreamSpec)
        assertEquals(0, (1..30).mapNotNull { recognized.parse("noise $it", upstreamSpec) }.size)
    }

    @Test
    fun `padding evidence makes health healthy and its absence does not`() {
        val parser = SubprocessRelayOutputParser()

        fun health(lines: List<String>): String {
            val verified =
                lines.any { parser.parse(it, upstreamSpec) is SubprocessRelayOutputEvent.UpstreamOk }
            return SubprocessRelayTelemetryProjector()
                .project(
                    SubprocessRelayTelemetryInputs(
                        config = sampleResolvedRelayConfig(kind = RelayKindNaiveProxy),
                        launchSpec = upstreamSpec,
                        running = true,
                        runtimeVersion = null,
                        lastError = null,
                        lastFailureClass = null,
                        runtimeStateOverride = null,
                        upstreamVerified = verified,
                    ),
                ).health
        }

        assertEquals("unknown", health(recordedUpstreamLines))
        assertEquals("healthy", health(recordedUpstreamLines + paddingLine))
    }
}
