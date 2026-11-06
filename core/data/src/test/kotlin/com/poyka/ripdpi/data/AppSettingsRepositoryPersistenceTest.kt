package com.poyka.ripdpi.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class AppSettingsRepositoryPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var storage: RoomProfileFixture? = null

    @Test
    fun `updated settings survive store shutdown and reopen`() =
        runTest {
            val expected =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRipdpiMode(Mode.Proxy.preferenceValue)
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("9.9.9.9")
                    .setWebrtcProtectionEnabled(true)
                    .setHapticFeedbackDisabled(true)
                    .setDiagnosticsHistoryRetentionDays(7)
                    .build()
            withRepository { repository ->
                assertEquals(AppSettingsSerializer.defaultValue, repository.snapshot())
                repository.update {
                    setRipdpiMode(expected.ripdpiMode)
                    setDnsMode(expected.dnsMode)
                    setDnsIp(expected.dnsIp)
                    setWebrtcProtectionEnabled(expected.webrtcProtectionEnabled)
                    setHapticFeedbackDisabled(expected.hapticFeedbackDisabled)
                    setDiagnosticsHistoryRetentionDays(expected.diagnosticsHistoryRetentionDays)
                }
            }

            withRepository { assertEquals(expected, it.snapshot()) }
        }

    @Test
    fun `reset restores system haptic feedback after store reopen`() =
        runTest {
            withRepository { repository ->
                repository.update { setHapticFeedbackDisabled(true) }
                assertTrue(repository.snapshot().hapticFeedbackDisabled)
                repository.replace(AppSettingsSerializer.defaultValue)
            }

            withRepository { assertFalse(it.snapshot().hapticFeedbackDisabled) }
        }

    @Test
    fun `replace removes old fields instead of merging them`() =
        runTest {
            val replacement = AppSettings.newBuilder().setRipdpiMode(Mode.VPN.preferenceValue).build()
            withRepository {
                it.update {
                    setDnsIp("9.9.9.9")
                    setWebrtcProtectionEnabled(true)
                }
                it.replace(replacement)
            }

            withRepository { assertEquals(replacement, it.snapshot()) }
        }

    @Test
    fun `concurrent disjoint updates both survive reopen`() =
        runTest {
            withRepository { repository ->
                coroutineScope {
                    listOf(
                        async { repository.update { setDnsIp("9.9.9.9") } },
                        async { repository.update { setWebrtcProtectionEnabled(true) } },
                    ).awaitAll()
                }
            }

            withRepository {
                val settings = it.snapshot()
                assertEquals("9.9.9.9", settings.dnsIp)
                assertTrue(settings.webrtcProtectionEnabled)
            }
        }

    @Test
    fun `failed update leaves committed settings intact`() =
        runTest {
            var committed = AppSettings.getDefaultInstance()
            withRepository { repository ->
                repository.update { setDnsIp("9.9.9.9") }
                committed = repository.snapshot()
                val failure =
                    runCatching {
                        repository.update {
                            setDnsIp("1.1.1.1")
                            error("Rejected update")
                        }
                    }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals(committed, repository.snapshot())
            }

            withRepository { assertEquals(committed, it.snapshot()) }
        }

    @Test
    fun `corrupt legacy settings block import and remain recoverable`() =
        runTest {
            val source = resolveAppSettingsStoreFile(context)
            val directory = checkNotNull(source.parentFile)
            check(directory.mkdirs() || directory.isDirectory)
            val corrupt = byteArrayOf(0x0f)
            source.writeBytes(corrupt)
            try {
                val failure = runCatching { withRepository { it.snapshot() } }.exceptionOrNull()
                assertTrue(failure is CorruptionException)
                assertArrayEquals(corrupt, source.readBytes())
                val restored =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setDnsIp("9.9.9.9")
                        .build()
                source.writeBytes(restored.toByteArray())
                withRepository { assertEquals(restored, it.snapshot()) }
                withRepository { assertEquals(restored, it.snapshot()) }
            } finally {
                source.delete()
            }
        }

    private suspend fun withRepository(block: suspend (AppSettingsRepository) -> Unit) {
        val fixture = storage?.reopen() ?: RoomProfileFixture(context, testPauseAuthority())
        storage = fixture
        try {
            fixture.initialize()
            block(fixture.settings)
        } finally {
            fixture.close()
        }
    }
}
