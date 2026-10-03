package com.poyka.ripdpi.integration

import com.poyka.ripdpi.core.DefaultRipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayBindings
import com.poyka.ripdpi.core.RipDpiRelayBindingsModule
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayNativeBindings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/** Uses the production resolver/factory/probe and observes handles delegated to actual JNI. */
@HiltAndroidTest
@UninstallModules(RipDpiRelayBindingsModule::class)
class SelectorRuntimeInstrumentedTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var candidateProbe: CandidateRelayPayloadProbe

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var relayFactory: RipDpiRelayFactory

    @BindValue
    @JvmField
    val relayBindings: RipDpiRelayBindings = ObservedNativeRelayBindings()

    @Before
    fun injectProductionGraph() {
        hiltRule.inject()
        assertTrue(relayFactory is DefaultRipDpiRelayFactory)
        assertTrue((relayBindings as ObservedNativeRelayBindings).native is RipDpiRelayNativeBindings)
    }

    @Test
    fun nativeCandidateRelaysConfiguredHttpPayloadWithoutActivatingSettings() =
        runBlocking {
            verifyCandidate(truncated = false)
        }

    @Test
    fun nativeCandidateRejectsTruncatedHttpPayloadWithoutActivatingSettings() =
        runBlocking {
            verifyCandidate(truncated = true)
        }

    @Test
    fun nativeCandidateCleansFailedRuntimeBeforeNextSuccessfulMeasurement() =
        runBlocking {
            verifyCandidate(truncated = true)
            verifyCandidate(truncated = false)
        }

    private suspend fun verifyCandidate(truncated: Boolean) =
        withTimeout(30_000L) {
            val before = settings.snapshot()
            val observedBindings = relayBindings as ObservedNativeRelayBindings
            val previousHandles = observedBindings.handles.toSet()
            NativeCandidateFixture(truncated).use { fixture ->
                val profile =
                    RelayProfileRecord(
                        id = "instrumented-ephemeral-candidate",
                        kind = RelayKindShadowsocks,
                        server = "127.0.0.1",
                        serverPort = fixture.relayPort,
                        udpEnabled = false,
                    )
                val credentials =
                    RelayCredentialRecord(
                        profileId = profile.id,
                        shadowsocksMethod = "aes-256-gcm",
                        shadowsocksPassword = FixturePassword,
                    )
                val latency = candidateProbe.measure(profile, credentials, fixture.probeUrl)
                observedBindings.assertRetiredSince(previousHandles)
                // Both the decrypted relay target and HTTP request must be observed. Direct HTTP
                // or a listener-readiness-only measurement cannot satisfy these assertions.
                fixture.assertRelayedRequest()
                if (truncated) {
                    assertNull("Partial HTTP bodies must not count as candidate health", latency)
                } else {
                    assertNotNull("A complete payload through the native candidate must succeed", latency)
                    assertTrue(requireNotNull(latency) >= 0L)
                }
                assertEquals(
                    "Candidate probing must not activate or edit persisted settings",
                    before,
                    settings.snapshot(),
                )
            }
        }
}

/** Records creation only; readiness, start, stop, telemetry and retirement all call real JNI. */
private class ObservedNativeRelayBindings(
    val native: RipDpiRelayBindings = RipDpiRelayNativeBindings(),
) : RipDpiRelayBindings by native {
    val handles = CopyOnWriteArrayList<Long>()

    override fun create(configJson: String): Long =
        native.create(configJson).also { handle ->
            handles.add(handle)
        }

    fun assertRetiredSince(previousHandles: Set<Long>) {
        val created = handles.filterNot(previousHandles::contains)
        assertTrue("A real native session must have been created", created.isNotEmpty())
        assertTrue("Native JNI must accept the candidate configuration", created.all { it != 0L })
        val unknownHandleTelemetry = native.pollTelemetry(0L)
        assertNotNull(unknownHandleTelemetry)
        created.forEach { handle ->
            assertEquals(
                "Candidate cleanup must retire its native registry handle before returning",
                unknownHandleTelemetry,
                native.pollTelemetry(handle),
            )
        }
    }
}

/** SIP004 AES-256-GCM peer forwarding to an independently bound loopback HTTP server. */
private class NativeCandidateFixture(
    truncated: Boolean,
) : Closeable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val relay = ServerSocket(0, 1, loopback)
    private val http = ServerSocket(0, 1, loopback)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newFixedThreadPool(2)
    val relayPort: Int = relay.localPort
    val probeUrl = "http://127.0.0.1:${http.localPort}$FixturePath"
    private val httpRequest =
        executor.submit<String> {
            accept(http).use { socket ->
                val request = readHttpHeaders(DataInputStream(socket.getInputStream()))
                val body = "candidate-payload"
                val length = body.length + if (truncated) 7 else 0
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n$body".toByteArray(),
                )
                socket.getOutputStream().flush()
                request
            }
        }
    private val relayTarget =
        executor.submit<Int> {
            accept(relay).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val decoder = FixtureAead(input.readExact(32))
                val first = decoder.readFrame(input)
                val addressLength =
                    when (first[0].toInt()) {
                        1 -> 7
                        3 -> 4 + (first[1].toInt() and 0xff)
                        else -> error("Unexpected target address family")
                    }
                val host =
                    if (first[0].toInt() == 1) {
                        InetAddress.getByAddress(first.copyOfRange(1, 5)).hostAddress
                    } else {
                        first.copyOfRange(2, addressLength - 2).toString(Charsets.UTF_8)
                    }
                assertEquals("127.0.0.1", host)
                val targetPort =
                    ((first[addressLength - 2].toInt() and 0xff) shl 8) or
                        (first[addressLength - 1].toInt() and 0xff)
                assertEquals(http.localPort, targetPort)
                val request = ByteArrayOutputStream()
                request.write(first, addressLength, first.size - addressLength)
                while (!request.toString("UTF-8").endsWith("\r\n\r\n")) {
                    check(request.size() < 16_384) { "HTTP headers exceeded fixture bound" }
                    request.write(decoder.readFrame(input))
                }
                Socket(loopback, targetPort).also(sockets::add).use { target ->
                    target.soTimeout = FixtureTimeoutMillis
                    target.getOutputStream().write(request.toByteArray())
                    target.getOutputStream().flush()
                    val response = target.getInputStream().readBytes()
                    val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val encoder = FixtureAead(salt)
                    socket.getOutputStream().write(salt + encoder.writeFrame(response))
                    socket.getOutputStream().flush()
                }
                targetPort
            }
        }

    fun assertRelayedRequest() {
        assertEquals(http.localPort, relayTarget.get(2, TimeUnit.SECONDS))
        assertEquals("GET $FixturePath HTTP/1.1", httpRequest.get(2, TimeUnit.SECONDS).lineSequence().first())
    }

    private fun accept(server: ServerSocket): Socket =
        server.accept().also {
            it.soTimeout = FixtureTimeoutMillis
            sockets.add(it)
        }

    override fun close() {
        relay.close()
        http.close()
        sockets.forEach { it.close() }
        executor.shutdownNow()
        check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "Fixture threads did not terminate" }
    }
}

/** Mirrors native/rust/crates/ripdpi-shadowsocks/src/{cipher,tcp}.rs SIP004 framing. */
private class FixtureAead(
    salt: ByteArray,
) {
    private val key: ByteArray
    private var counter = 0L

    init {
        val password = FixturePassword.toByteArray()
        val md5 = MessageDigest.getInstance("MD5")
        val first = md5.digest(password)
        val master = first + md5.digest(first + password)
        val extract = Mac.getInstance("HmacSHA1")
        extract.init(SecretKeySpec(salt, "HmacSHA1"))
        val prk = extract.doFinal(master)
        val expand = Mac.getInstance("HmacSHA1")
        expand.init(SecretKeySpec(prk, "HmacSHA1"))
        val info = "ss-subkey".toByteArray()
        val block = expand.doFinal(info + byteArrayOf(1))
        key = (block + expand.doFinal(block + info + byteArrayOf(2))).copyOf(32)
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val lengthBytes = transform(Cipher.DECRYPT_MODE, input.readExact(18))
        val length = ((lengthBytes[0].toInt() and 0xff) shl 8) or (lengthBytes[1].toInt() and 0xff)
        check(length <= 0x3fff) { "Invalid SIP004 frame length" }
        return transform(Cipher.DECRYPT_MODE, input.readExact(length + 16))
    }

    fun writeFrame(payload: ByteArray): ByteArray {
        check(payload.size <= 0x3fff)
        val length = byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte())
        return transform(Cipher.ENCRYPT_MODE, length) + transform(Cipher.ENCRYPT_MODE, payload)
    }

    private fun transform(
        mode: Int,
        bytes: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(12)
        val observed = counter++
        repeat(8) { index -> nonce[index] = (observed ushr (8 * index)).toByte() }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(bytes)
        }
    }
}

private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

private fun readHttpHeaders(input: DataInputStream): String {
    val bytes = ByteArrayOutputStream()
    while (!bytes.toString("UTF-8").endsWith("\r\n\r\n")) {
        check(bytes.size() < 16_384) { "HTTP headers exceeded fixture bound" }
        bytes.write(input.readUnsignedByte())
    }
    return bytes.toString("UTF-8")
}

private const val FixturePassword = "loopback-instrumented-only"
private const val FixturePath = "/selector/custom-health?contract=payload"
private const val FixtureTimeoutMillis = 10_000
