package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindGoogleAppsScript
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelaySecurityLayerTls
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.testsupport.NoOpProfileMutationCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRelayArtifactRepositoryTest {
    @Test
    fun `four imported relay kinds retain configuration and secrets after edit`() =
        runTest {
            val kinds = listOf(RelayKindVless, RelayKindTrojan, RelayKindShadowsocks, RelayKindGoogleAppsScript)
            for (kind in kinds) {
                val profiles = FailingRelayProfileStore()
                val credentials = FailingRelayCredentialRepository()
                val repository = ConfigRelayArtifactRepository(FailingSettingsRepository(), profiles, credentials)
                val profile =
                    RelayProfileRecord(
                        id = "imported-$kind",
                        kind = kind,
                        server = "before.example",
                        serverName = "tls.example",
                        securityLayer = RelaySecurityLayerTls,
                        vlessTransport = RelayVlessTransportXhttp,
                        xhttpPath = "/proxy",
                        appsScriptScriptIds = listOf("deployment-a", "deployment-b"),
                        appsScriptGoogleIp = "8.8.8.8",
                        appsScriptFrontDomain = "front.example",
                        appsScriptSniHosts = listOf(" sni.example "),
                        appsScriptDirectHosts = listOf("direct.example"),
                        appsScriptVerifySsl = false,
                        appsScriptParallelRelay = true,
                    )
                val secret =
                    RelayCredentialRecord(
                        profileId = profile.id,
                        vlessUuid = "00000000-0000-4000-8000-000000000001",
                        trojanPassword = "trojan-fixture",
                        shadowsocksMethod = "aes-128-gcm",
                        shadowsocksPassword = "ss-fixture",
                        appsScriptAuthKey = "apps-fixture",
                    )
                profiles.save(profile)
                credentials.save(secret)
                val draft = repository.hydrateProfile(profile.id)
                assertEquals(profile.id, draft.editingRelayProfileId)
                repository.persist(draft.copy(relayServerPort = "8443"))
                val saved = requireNotNull(profiles.load(profile.id))
                val savedSecret = requireNotNull(credentials.load(profile.id))
                assertEquals(8443, saved.serverPort)
                assertEquals(profile.securityLayer, saved.securityLayer)
                assertEquals(profile.vlessTransport, saved.vlessTransport)
                assertEquals(profile.appsScriptScriptIds, saved.appsScriptScriptIds)
                assertEquals(profile.appsScriptSniHosts, saved.appsScriptSniHosts)
                assertEquals(profile.appsScriptDirectHosts, saved.appsScriptDirectHosts)
                assertEquals(profile.appsScriptVerifySsl, saved.appsScriptVerifySsl)
                assertEquals(profile.appsScriptParallelRelay, saved.appsScriptParallelRelay)
                assertEquals(secret.vlessUuid, savedSecret.vlessUuid)
                assertEquals(secret.trojanPassword, savedSecret.trojanPassword)
                assertEquals(secret.shadowsocksMethod, savedSecret.shadowsocksMethod)
                assertEquals(secret.shadowsocksPassword, savedSecret.shadowsocksPassword)
                assertEquals(secret.appsScriptAuthKey, savedSecret.appsScriptAuthKey)
            }
        }

    @Test
    fun `new relay kinds reject missing required credentials`() {
        for (kind in listOf(
            RelayKindVless,
            RelayKindTrojan,
            RelayKindShadowsocks,
            RelayKindGoogleAppsScript,
            RelayKindMieru,
            RelayKindSsh,
        )) {
            val errors =
                validateConfigDraft(
                    ConfigDraft(
                        relayEnabled = true,
                        relayKind = kind,
                        relayProfileId = "new-$kind",
                        relayServer = "relay.example",
                        relayServerName = "relay.example",
                        relayVlessTransport = RelayVlessTransportXhttp,
                    ),
                )
            assertNotNull("$kind accepted empty credentials", errors[ConfigFieldRelayCredentials])
        }
    }

    @Test
    fun `Apps Script accepts native default IP and rejects disabled TLS verification`() {
        val draft =
            ConfigDraft(
                relayEnabled = true,
                relayKind = RelayKindGoogleAppsScript,
                relayProfileId = "apps-script",
                relayAppsScriptScriptIds = "deployment-a",
                relayAppsScriptAuthKey = "auth-fixture",
            )
        assertNull(validateConfigDraft(draft)[ConfigFieldRelayCredentials])
        assertEquals(
            "unsupported",
            validateConfigDraft(draft.copy(relayAppsScriptVerifySsl = false))[ConfigFieldRelayCredentials],
        )
    }

    @Test
    fun `mode edits preserve imported SSH and Mieru authentication and carrier options`() =
        runTest {
            for (kind in listOf(RelayKindSsh, RelayKindMieru)) {
                val profiles = FailingRelayProfileStore()
                val credentials = FailingRelayCredentialRepository()
                val repository = ConfigRelayArtifactRepository(FailingSettingsRepository(), profiles, credentials)
                val profile =
                    RelayProfileRecord(
                        id = "imported-$kind",
                        kind = kind,
                        server = "relay.example",
                        sshAuthType = "private_key",
                        sshHostKeyFingerprint = "SHA256:" + "A".repeat(43),
                        sshStrictHostKey = true,
                        mieruMultiplexing = "high",
                        mieruMtu = 1280,
                    )
                val secret =
                    RelayCredentialRecord(
                        profileId = profile.id,
                        sshUsername = "ssh-fixture",
                        sshPrivateKey = "key-fixture",
                        sshPrivateKeyPassphrase = "passphrase-fixture",
                        mieruUsername = "mieru-fixture",
                        mieruPassword = "password-fixture",
                    )
                profiles.save(profile)
                credentials.save(secret)
                val draft =
                    repository.hydrate(
                        ConfigDraft(relayKind = kind, relayProfileId = profile.id, relayServer = profile.server),
                    )

                repository.persist(draft.copy(relayServerPort = "8443"))

                val saved = requireNotNull(profiles.load(profile.id))
                val savedSecret = requireNotNull(credentials.load(profile.id))
                assertEquals(8443, saved.serverPort)
                assertEquals(profile.sshHostKeyFingerprint, saved.sshHostKeyFingerprint)
                assertEquals(profile.sshAuthType, saved.sshAuthType)
                assertTrue(saved.sshStrictHostKey)
                assertEquals(profile.mieruMultiplexing, saved.mieruMultiplexing)
                assertEquals(profile.mieruMtu, saved.mieruMtu)
                assertEquals(secret.sshPrivateKey, savedSecret.sshPrivateKey)
                assertEquals(secret.sshPrivateKeyPassphrase, savedSecret.sshPrivateKeyPassphrase)
                assertEquals(secret.mieruPassword, savedSecret.mieruPassword)
            }
        }

    @Test
    fun `rebinding a draft never revives hidden credentials from an earlier identity`() {
        val source = RelayProfileRecord(id = "ssh-a", kind = RelayKindSsh, sshHostKeyFingerprint = "old-key")
        val draft =
            ConfigDraft(relayProfileId = source.id, relayKind = source.kind)
                .withRelayArtifacts(source, RelayCredentialRecord(source.id, sshPrivateKey = "key-fixture"))

        val rebound =
            draft
                .applyRelayDraftEdit { copy(relayProfileId = "ssh-b") }
                .applyRelayDraftEdit { copy(relayProfileId = "ssh-a") }

        assertEquals(null, rebound.toRelayCredentialRecord(source.id).sshPrivateKey)
        assertEquals("", rebound.toRelayProfileRecord(source.id).sshHostKeyFingerprint)
        assertEquals(null, draft.toRelayCredentialRecord("another-profile").sshPrivateKey)
        assertEquals(null, draft.copy(relayKind = RelayKindMieru).toRelayCredentialRecord(source.id).sshPrivateKey)
    }

    @Test
    fun `saving a renamed draft cannot overwrite another saved profile`() =
        runTest {
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(FailingSettingsRepository(), profiles, credentials)
            profiles.save(RelayProfileRecord(id = "first", server = "first.example"))
            profiles.save(RelayProfileRecord(id = "second", server = "second.example"))
            val draft =
                repository
                    .hydrate(ConfigDraft(relayProfileId = "first"))
                    .applyRelayDraftEdit { copy(relayProfileId = "second", relayServer = "wrong.example") }

            assertTrue(runCatching { repository.persist(draft) }.isFailure)
            assertEquals("second.example", profiles.load("second")?.server)
        }

    @Test
    fun `an existing profile ID is invalid even when relay is disabled`() {
        val existing = RelayProfileRecord(id = "taken")
        val draft = ConfigDraft(relayProfileId = existing.id, relayEnabled = false)

        assertEquals("taken", validateConfigDraft(draft, relayProfiles = listOf(existing))[ConfigFieldRelayProfileId])
    }

    @Test
    fun `an existing profile ID cannot be renamed through persistence`() =
        runTest {
            val profiles = FailingRelayProfileStore()
            val repository =
                ConfigRelayArtifactRepository(
                    FailingSettingsRepository(),
                    profiles,
                    FailingRelayCredentialRepository(),
                )
            profiles.save(RelayProfileRecord(id = "original", server = "original.example"))
            val draft = repository.hydrate(ConfigDraft(relayProfileId = "original"))

            assertTrue(runCatching { repository.persist(draft.copy(relayProfileId = "renamed")) }.isFailure)
            assertEquals("original.example", profiles.load("original")?.server)
            assertEquals(null, profiles.load("renamed"))
        }

    @Test
    fun `changing a saved profile kind cannot erase imported metadata`() =
        runTest {
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(FailingSettingsRepository(), profiles, credentials)
            val profile =
                RelayProfileRecord(
                    id = "imported",
                    kind = RelayKindSsh,
                    server = "old.example",
                    operatorName = "Imported operator",
                )
            val secret = RelayCredentialRecord(profileId = profile.id, sshPrivateKey = "imported-secret")
            profiles.save(profile)
            credentials.save(secret)
            val draft =
                repository
                    .hydrate(ConfigDraft(relayProfileId = profile.id, relayKind = profile.kind))
                    .applyRelayDraftEdit { copy(relayKind = RelayKindVlessReality) }

            assertTrue(runCatching { repository.persist(draft) }.isFailure)
            assertEquals(profile, profiles.load(profile.id))
            assertEquals(secret, credentials.load(profile.id))
        }

    @Test
    fun `stale editor cannot replace changed profile or credentials`() =
        runTest {
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(FailingSettingsRepository(), profiles, credentials)
            val profile = RelayProfileRecord(id = "shared", kind = RelayKindAnyTls, server = "old.example")
            val secret = RelayCredentialRecord(profileId = "shared", anyTlsPassword = "old-secret")
            profiles.save(profile)
            credentials.save(secret)
            val draft = repository.hydrate(ConfigDraft(relayProfileId = "shared", relayKind = RelayKindAnyTls))
            profiles.save(profile.copy(server = "new.example"))
            credentials.save(secret.copy(anyTlsPassword = "new-secret"))

            assertTrue(runCatching { repository.persist(draft.copy(relayServer = "stale.example")) }.isFailure)
            assertEquals("new.example", profiles.load("shared")?.server)
            assertEquals("new-secret", credentials.load("shared")?.anyTlsPassword)
        }

    @Test
    fun `selecting a saved profile preserves its metadata and credentials`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profile =
                RelayProfileRecord(
                    id = "selected",
                    kind = RelayKindSsh,
                    server = "selected.example",
                    sshAuthType = "private_key",
                )
            val secret = RelayCredentialRecord(profileId = profile.id, sshPrivateKey = "secret-fixture")
            profiles.save(profile)
            credentials.save(secret)

            repository.selectProfile(profile.id)

            assertEquals(profile, profiles.load(profile.id))
            assertEquals(secret, credentials.load(profile.id))
            assertEquals(profile.id, settings.snapshot().relayProfileId)
            assertTrue(settings.snapshot().relayEnabled)
        }

    @Test
    fun `editing a saved profile hydrates that profile instead of the active one`() =
        runTest {
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(FailingSettingsRepository(), profiles, credentials)
            profiles.save(RelayProfileRecord(id = "inactive", kind = RelayKindAnyTls, server = "inactive.example"))
            credentials.save(RelayCredentialRecord(profileId = "inactive", anyTlsPassword = "secret-fixture"))

            val draft = repository.hydrateProfile("inactive")

            assertEquals("inactive.example", draft.relayServer)
            assertEquals("secret-fixture", draft.relayAnyTlsPassword)
            assertEquals("inactive", draft.relayProfileId)
        }

    @Test
    fun `AnyTLS mode editor refuses an empty password`() {
        val errors =
            validateConfigDraft(
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayEnabled = true,
                    relayKind = RelayKindAnyTls,
                    relayServer = "relay.example",
                ),
            )

        assertEquals("required", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `editing or clearing AnyTLS password never falls back to imported secret`() {
        val profile = RelayProfileRecord(id = "anytls-source", kind = RelayKindAnyTls)
        val draft =
            ConfigDraft(relayProfileId = profile.id, relayKind = profile.kind)
                .withRelayArtifacts(
                    profile,
                    RelayCredentialRecord(profile.id, anyTlsPassword = "imported-secret-fixture"),
                )

        assertEquals(
            "edited-secret-fixture",
            draft
                .copy(
                    relayAnyTlsPassword = "edited-secret-fixture",
                ).toRelayCredentialRecord(profile.id)
                .anyTlsPassword,
        )
        assertEquals(null, draft.copy(relayAnyTlsPassword = "").toRelayCredentialRecord(profile.id).anyTlsPassword)
    }

    @Test
    fun `editing imported AnyTLS profile preserves its credential`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "anytls-imported"
            val passwordFixture = "anytls-editor-fixture"
            profiles.save(RelayProfileRecord(id = profileId, kind = RelayKindAnyTls, server = "relay.example"))
            credentials.save(
                RelayCredentialRecord(
                    profileId = profileId,
                    anyTlsPassword = passwordFixture,
                    updatedAtEpochMillis = 1L,
                ),
            )
            val draft =
                repository.hydrate(
                    AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                        relayEnabled = true,
                        relayKind = RelayKindAnyTls,
                        relayProfileId = profileId,
                        relayServer = "relay.example",
                        relayServerName = "relay.example",
                    ),
                )

            repository.persist(draft.copy(relayServerPort = "8443"))

            assertEquals(passwordFixture, credentials.load(profileId)?.anyTlsPassword)
            assertTrue(requireNotNull(credentials.load(profileId)).updatedAtEpochMillis > 1L)
            assertEquals(8443, profiles.load(profileId)?.serverPort)
        }

    @Test
    fun `journaled relay persistence keeps the complete config settings after-image`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val profileMutations = RecordingRelayMutationCoordinator(settings, profiles, credentials)
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials, profileMutations)
            val draft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    mode = Mode.Proxy,
                    dnsIp = "9.9.9.9",
                    relayProfileId = "relay-journaled",
                    relayServer = "relay.example",
                )

            repository.persist(draft)

            assertEquals(Mode.Proxy.preferenceValue, settings.snapshot().ripdpiMode)
            assertEquals("9.9.9.9", settings.snapshot().dnsIp)
            assertEquals("relay.example", profiles.load("relay-journaled")?.server)
        }

    @Test
    fun `failed metadata write restores the previous relay snapshot`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "relay-atomic"
            val previousDraft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayProfileId = profileId,
                    relayServer = "old.example",
                    relayVlessUuid = "old-credential",
                )
            profiles.save(previousDraft.toRelayProfileRecord(profileId))
            credentials.save(previousDraft.toRelayCredentialRecord(profileId))
            val editingDraft = repository.hydrate(previousDraft)
            profiles.failNextSaveAfterWrite = true

            val error =
                runCatching {
                    repository.persist(editingDraft.copy(relayServer = "new.example"))
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals("old.example", profiles.load(profileId)?.server)
            assertEquals("old-credential", credentials.load(profileId)?.vlessUuid)
            assertEquals(AppSettingsSerializer.defaultValue, settings.snapshot())
        }

    @Test
    fun `failed DataStore write restores relay metadata credentials and settings`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "relay-atomic"
            val previousDraft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayProfileId = profileId,
                    relayServer = "old.example",
                    relayVlessUuid = "old-credential",
                )
            profiles.save(previousDraft.toRelayProfileRecord(profileId))
            credentials.save(previousDraft.toRelayCredentialRecord(profileId))
            settings.update { applyConfigDraft(previousDraft) }
            val editingDraft = repository.hydrate(previousDraft)
            settings.failNextUpdateAfterWrite = true

            val error =
                runCatching {
                    repository.persist(
                        editingDraft.copy(relayServer = "new.example", relayVlessUuid = "new-credential"),
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals("old.example", profiles.load(profileId)?.server)
            assertEquals("old-credential", credentials.load(profileId)?.vlessUuid)
            assertEquals("old.example", settings.snapshot().relayServer)
        }

    @Test
    fun `failed credential write restores relay metadata and credentials`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "relay-atomic"
            val previousDraft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayProfileId = profileId,
                    relayServer = "old.example",
                    relayVlessUuid = "old-credential",
                )
            profiles.save(previousDraft.toRelayProfileRecord(profileId))
            credentials.save(previousDraft.toRelayCredentialRecord(profileId))
            val editingDraft = repository.hydrate(previousDraft)
            credentials.failNextSaveAfterWrite = true

            val error =
                runCatching {
                    repository.persist(
                        editingDraft.copy(relayServer = "new.example", relayVlessUuid = "new-credential"),
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals("old.example", profiles.load(profileId)?.server)
            assertEquals("old-credential", credentials.load(profileId)?.vlessUuid)
            assertEquals(AppSettingsSerializer.defaultValue, settings.snapshot())
        }
}

private class RecordingRelayMutationCoordinator(
    private val settings: AppSettingsRepository,
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialRepository,
) : ProfileMutationCoordinator by NoOpProfileMutationCoordinator {
    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
        expectedState: ExpectedRelayProfileState?,
    ) {
        if (expectedState != null) {
            require(
                profiles.load(profile.id) == expectedState.profile &&
                    this.credentials.load(profile.id) == expectedState.credentials,
            )
        }
        profiles.save(profile)
        this.credentials.save(credentials)
        check(modeAfterImage == null)
        check(xraySelectionAfterImage == null)
        settings.replace(requireNotNull(settingsAfterImage))
    }
}

private class FailingSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)
    var failNextUpdateAfterWrite = false

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
        if (failNextUpdateAfterWrite) {
            failNextUpdateAfterWrite = false
            error("settings update failed after write")
        }
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class FailingRelayProfileStore : RelayProfileStore {
    private val records = linkedMapOf<String, RelayProfileRecord>()
    var failNextSaveAfterWrite = false

    override suspend fun load(profileId: String): RelayProfileRecord? = records[profileId]

    override suspend fun list(): List<RelayProfileRecord> = records.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        records[profile.id] = profile
        if (failNextSaveAfterWrite) {
            failNextSaveAfterWrite = false
            error("profile save failed after write")
        }
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

private class FailingRelayCredentialRepository : RelayCredentialRepository {
    private val records = linkedMapOf<String, RelayCredentialRecord>()
    var failNextSaveAfterWrite = false

    override suspend fun load(profileId: String): RelayCredentialRecord? = records[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        records[credentials.profileId] = credentials
        if (failNextSaveAfterWrite) {
            failNextSaveAfterWrite = false
            error("credential save failed after write")
        }
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}
