package com.poyka.ripdpi.data

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsRepositoryPersistenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val settingsFile: File
        get() = File(temporaryFolder.root, AppSettingsStoreFileName)

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
                    .setDiagnosticsHistoryRetentionDays(7)
                    .build()
            withRepository { repository ->
                assertEquals(AppSettingsSerializer.defaultValue, repository.snapshot())
                repository.update {
                    setRipdpiMode(expected.ripdpiMode)
                    setDnsMode(expected.dnsMode)
                    setDnsIp(expected.dnsIp)
                    setWebrtcProtectionEnabled(expected.webrtcProtectionEnabled)
                    setDiagnosticsHistoryRetentionDays(expected.diagnosticsHistoryRetentionDays)
                }
            }

            assertEquals(expected, AppSettings.parseFrom(settingsFile.readBytes()))
            withRepository { assertEquals(expected, it.snapshot()) }
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
            assertEquals(replacement, AppSettings.parseFrom(settingsFile.readBytes()))
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

            assertEquals(committed, AppSettings.parseFrom(settingsFile.readBytes()))
            withRepository { assertEquals(committed, it.snapshot()) }
        }

    @Test
    fun `corrupt file is replaced with defaults and accepts durable updates`() =
        runTest {
            settingsFile.writeBytes(byteArrayOf(0x0f))
            withRepository {
                assertEquals(AppSettingsSerializer.defaultValue, it.snapshot())
                assertEquals(AppSettingsSerializer.defaultValue, AppSettings.parseFrom(settingsFile.readBytes()))
                it.update { setDnsIp("9.9.9.9") }
            }

            withRepository { assertEquals("9.9.9.9", it.snapshot().dnsIp) }
        }

    private suspend fun withRepository(block: suspend (AppSettingsRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store =
            DataStoreFactory.create(
                serializer = AppSettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { AppSettingsSerializer.defaultValue },
                scope = scope,
                produceFile = { settingsFile },
            )
        try {
            block(DefaultAppSettingsRepository(store))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
