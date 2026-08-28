package com.poyka.ripdpi.integration

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.core.RipDpiSshHostKeyNativeBindings
import com.poyka.ripdpi.core.RipDpiSshHostKeyProbe
import com.poyka.ripdpi.core.SshHostKeyProbeFailure
import com.poyka.ripdpi.core.SshHostKeyProbeRequest
import com.poyka.ripdpi.core.SshHostKeyProbeResult
import com.poyka.ripdpi.services.SshHostKeyObserver
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidTest
class SshHostKeyProbeInstrumentedTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var observer: SshHostKeyObserver

    @Before
    fun inject() = hiltRule.inject()

    @Test
    fun nativeSocketDenialPreventsConnect() {
        assertProtectionDenied { false }
    }

    @Test
    fun nativeSocketCallbackExceptionPreventsConnectAndIsCleared() {
        assertProtectionDenied { throw SecurityException("owned test denial") }
        assertProtectionDenied { false }
    }

    private fun assertProtectionDenied(protect: () -> Boolean) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { peer ->
            val calls = AtomicInteger()
            val result =
                RipDpiSshHostKeyProbe(RipDpiSshHostKeyNativeBindings()).probe(
                    SshHostKeyProbeRequest("127.0.0.1", peer.localPort),
                ) {
                    calls.incrementAndGet()
                    protect()
                }

            assertEquals(SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.ProtectionDenied), result)
            assertEquals(1, calls.get())
            peer.soTimeout = 100
            assertThrows(SocketTimeoutException::class.java) { peer.accept().close() }
        }
    }

    /** Requires an owned host-loopback upstream peer and VPN consent on the test emulator. */
    @Test
    fun protectedObservationMatchesOwnedUpstreamBeforeTunnel() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue("Owned SSH peer is required", arguments.containsKey("ripdpi.sshFixturePort"))
            val port = requireNotNull(arguments.getString("ripdpi.sshFixturePort")).toInt()
            val fingerprint = requireNotNull(arguments.getString("ripdpi.sshFixtureFingerprint"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertNull("Grant test-app VPN consent before running this case", VpnService.prepare(context))
            assertNoActiveVpn(context)

            repeat(2) {
                assertEquals(
                    SshHostKeyProbeResult.Observed(fingerprint, "ssh-ed25519"),
                    observer.observe("10.0.2.2", port),
                )
            }

            assertNoActiveVpn(context)
        }

    private fun assertNoActiveVpn(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        assertFalse(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
    }
}
