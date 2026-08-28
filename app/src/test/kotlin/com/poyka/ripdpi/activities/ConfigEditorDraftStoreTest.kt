package com.poyka.ripdpi.activities

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class ConfigEditorDraftStoreTest {
    @Test
    fun `config draft recovery codec preserves credentials and immutable chain fields`() {
        val draft =
            ConfigDraft(
                commandLineArgs = "--fixture value",
                tcpChainSteps =
                    persistentListOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.Split,
                            marker = "split",
                        ),
                    ),
                udpChainSteps =
                    persistentListOf(
                        UdpChainStepModel(
                            count = 2,
                            kind = UdpChainStepKind.FakeBurst,
                        ),
                    ),
                relayChainMiddleProfileIds = persistentListOf("middle-1", "middle-2"),
                relayMasqueAuthToken = "fixture-auth-marker",
                relayMasqueClientCertificateChainPem = "certificate-pem",
                relayMasqueClientPrivateKeyPem = "fixture-key-pem",
                relayCloudflareTunnelCredentialsJson = "{\"fixture\":true}",
                relayAnyTlsPassword = "anytls-fixture",
                sourceRelayProfile = RelayProfileRecord(id = "ssh-source", kind = RelayKindSsh),
                sourceRelayCredentials =
                    RelayCredentialRecord("ssh-source", sshPrivateKey = "ssh-key-fixture"),
            )

        val restored = RipDpiJson.decodeFromString<ConfigDraft>(RipDpiJson.encodeToString(draft))

        assertEquals(draft, restored)
        assertFalse(restored.toString().contains("anytls-fixture"))
        assertFalse(restored.toString().contains("ssh-key-fixture"))
    }

    @Test
    fun `recovery session identifiers are opaque canonical uuids`() {
        val first = newConfigEditorRecoverySessionId()
        val second = newConfigEditorRecoverySessionId()

        assertTrue(isValidConfigEditorRecoverySessionId(first))
        assertTrue(isValidConfigEditorRecoverySessionId(second))
        assertFalse(first == second)
        assertFalse(isValidConfigEditorRecoverySessionId("draft contents"))
    }

    @Test
    fun `recovery record bound and ttl remain finite`() {
        assertTrue(ConfigEditorDraftMaxRecordBytes in 1..(1024 * 1024))
        assertTrue(ConfigEditorDraftTtlMillis in 1L..(7L * 24L * 60L * 60L * 1000L))
    }

    @Test
    fun `encrypted store persists no draft plaintext and restores the bound session`() =
        runTest {
            val fixture = StoreFixture()
            val recoverySessionId = newConfigEditorRecoverySessionId()
            val session = recoverableSession("fixture-sensitive-marker")

            fixture.store.persist(recoverySessionId, session)

            val storedValues =
                fixture.preferences.all.values
                    .joinToString()
            assertFalse(storedValues.contains("fixture-sensitive-marker"))
            assertEquals(
                ConfigEditorDraftRestoreResult.Restored(session.withoutRuntimeState()),
                fixture.store.restore(recoverySessionId),
            )
        }

    @Test
    fun `transient decrypt failure preserves the record for retry`() =
        runTest {
            val cipher = TestDraftCipher()
            val fixture = StoreFixture(cipher = cipher)
            val recoverySessionId = newConfigEditorRecoverySessionId()
            val session = recoverableSession("fixture-retry-marker")
            fixture.store.persist(recoverySessionId, session)
            cipher.nextDecryptFailure = IOException("fixture transient failure")

            assertTrue(fixture.store.restore(recoverySessionId) is ConfigEditorDraftRestoreResult.RestoreFailed)
            assertEquals(
                ConfigEditorDraftRestoreResult.Restored(session.withoutRuntimeState()),
                fixture.store.restore(recoverySessionId),
            )
        }

    @Test
    fun `authenticated corruption is discarded without exposing a draft`() =
        runTest {
            val cipher = TestDraftCipher()
            val fixture = StoreFixture(cipher = cipher)
            val recoverySessionId = newConfigEditorRecoverySessionId()
            fixture.preferences
                .edit()
                .putString(ConfigEditorDraftRecordKey, "invalid-envelope")
                .commit()
            cipher.nextDecryptFailure = CorruptConfigEditorDraftException()

            assertEquals(
                ConfigEditorDraftRestoreResult.MissingOrInvalid,
                fixture.store.restore(recoverySessionId),
            )
            assertFalse(fixture.preferences.contains(ConfigEditorDraftRecordKey))
        }

    @Test
    fun `expired recovery is discarded`() =
        runTest {
            var now = 10_000L
            val fixture = StoreFixture(currentTimeMillis = { now })
            val recoverySessionId = newConfigEditorRecoverySessionId()
            fixture.store.persist(recoverySessionId, recoverableSession("fixture-expired-marker"))

            now += ConfigEditorDraftTtlMillis + 1L

            assertEquals(
                ConfigEditorDraftRestoreResult.MissingOrInvalid,
                fixture.store.restore(recoverySessionId),
            )
            assertFalse(fixture.preferences.contains(ConfigEditorDraftRecordKey))
        }

    @Test
    fun `mismatched recovery id cannot read or delete the active record`() =
        runTest {
            val fixture = StoreFixture()
            val recoverySessionId = newConfigEditorRecoverySessionId()
            val session = recoverableSession("fixture-bound-marker")
            fixture.store.persist(recoverySessionId, session)

            assertEquals(
                ConfigEditorDraftRestoreResult.MissingOrInvalid,
                fixture.store.restore(newConfigEditorRecoverySessionId()),
            )
            assertEquals(
                ConfigEditorDraftRestoreResult.Restored(session.withoutRuntimeState()),
                fixture.store.restore(recoverySessionId),
            )
        }

    @Test
    fun `invalidating an old session preserves the current active record`() =
        runTest {
            val fixture = StoreFixture()
            val oldRecoverySessionId = newConfigEditorRecoverySessionId()
            val currentRecoverySessionId = newConfigEditorRecoverySessionId()
            val currentSession = recoverableSession("fixture-current-after-recreation")
            fixture.store.persist(currentRecoverySessionId, currentSession)

            fixture.store.invalidate(oldRecoverySessionId)

            assertEquals(
                ConfigEditorDraftRestoreResult.Restored(currentSession.withoutRuntimeState()),
                fixture.store.restore(currentRecoverySessionId),
            )
            assertTrue(
                fixture.preferences
                    .getString(ConfigEditorDraftInvalidatedSessionIdsKey, null)
                    .orEmpty()
                    .contains(oldRecoverySessionId),
            )
        }

    @Test
    fun `replayed invalidation keeps the durable ledger unique without evicting another tombstone`() =
        runTest {
            val fixture = StoreFixture()
            val first = newConfigEditorRecoverySessionId()
            val second = newConfigEditorRecoverySessionId()

            fixture.store.invalidate(first)
            fixture.store.invalidate(second)
            repeat(40) { fixture.store.invalidate(first) }

            val invalidated =
                fixture.preferences
                    .getString(ConfigEditorDraftInvalidatedSessionIdsKey, null)
                    .orEmpty()
                    .split(',')
                    .filter(String::isNotBlank)
            assertEquals(listOf(first, second), invalidated)
            assertEquals(invalidated.size, invalidated.toSet().size)
        }

    @Test
    fun `invalidation wins over delayed persist after many session rotations`() =
        runTest {
            val cipher = BlockingEncryptDraftCipher()
            val fixture = StoreFixture(cipher = cipher)
            val recoverySessionId = newConfigEditorRecoverySessionId()
            val persist =
                async(Dispatchers.Default) {
                    fixture.store.persist(recoverySessionId, recoverableSession("fixture-stale-marker"))
                }
            assertTrue(cipher.encryptStarted.await(5, TimeUnit.SECONDS))

            fixture.store.invalidate(recoverySessionId)
            repeat(40) { fixture.store.invalidate(newConfigEditorRecoverySessionId()) }
            val currentRecoverySessionId = newConfigEditorRecoverySessionId()
            val currentSession = recoverableSession("fixture-current-marker")
            fixture.store.persist(currentRecoverySessionId, currentSession)
            cipher.allowEncrypt.countDown()
            persist.await()

            assertEquals(
                ConfigEditorDraftRestoreResult.MissingOrInvalid,
                fixture.store.restore(recoverySessionId),
            )
            assertEquals(
                ConfigEditorDraftRestoreResult.Restored(currentSession.withoutRuntimeState()),
                fixture.store.restore(currentRecoverySessionId),
            )
        }
}

private class StoreFixture(
    cipher: ConfigEditorDraftCipher = TestDraftCipher(),
    currentTimeMillis: () -> Long = { 10_000L },
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val preferences: SharedPreferences =
        context.getSharedPreferences("config-editor-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val store = EncryptedConfigEditorDraftStore(preferences, cipher, currentTimeMillis)
}

private open class TestDraftCipher : ConfigEditorDraftCipher {
    var nextDecryptFailure: Throwable? = null

    override fun encrypt(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    override fun decrypt(value: String): String {
        nextDecryptFailure?.let { failure ->
            nextDecryptFailure = null
            throw failure
        }
        return Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
    }
}

private class BlockingEncryptDraftCipher : TestDraftCipher() {
    val encryptStarted = CountDownLatch(1)
    val allowEncrypt = CountDownLatch(1)
    private val shouldBlock = AtomicBoolean(true)

    override fun encrypt(value: String): String {
        if (shouldBlock.compareAndSet(true, false)) {
            encryptStarted.countDown()
            check(allowEncrypt.await(5, TimeUnit.SECONDS))
        }
        return super.encrypt(value)
    }
}

private fun recoverableSession(marker: String): ConfigEditorSession {
    val baseline = ConfigDraft()
    return ConfigEditorSession(
        sessionId = 42L,
        presetId = "custom",
        baselineDraft = baseline,
        draft = baseline.copy(relayMasqueAuthToken = marker),
        draftRevision = 1L,
        savePending = true,
        suppressSaveSuccess = true,
    )
}

private fun ConfigEditorSession.withoutRuntimeState(): ConfigEditorSession =
    copy(
        sessionId = 0L,
        savePending = false,
        suppressSaveSuccess = false,
    )
