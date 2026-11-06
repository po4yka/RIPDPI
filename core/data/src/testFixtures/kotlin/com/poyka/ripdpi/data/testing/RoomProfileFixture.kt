package com.poyka.ripdpi.data.testing

import android.content.Context
import androidx.room.Room
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultProxyGroupRepository
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileMutationGenerationPublisher
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileMutationStores
import com.poyka.ripdpi.data.ProfileSelectionStore
import com.poyka.ripdpi.data.ProxyGroupCatalog
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.persistence.LegacyProfileImporter
import com.poyka.ripdpi.data.persistence.ProfileDatabase
import com.poyka.ripdpi.data.persistence.RoomAppSettingsRepository
import com.poyka.ripdpi.data.persistence.RoomAwgCredentialStore
import com.poyka.ripdpi.data.persistence.RoomAwgProfileReadStore
import com.poyka.ripdpi.data.persistence.RoomDurableXrayProfileStore
import com.poyka.ripdpi.data.persistence.RoomProfileSelectionStore
import com.poyka.ripdpi.data.persistence.RoomProxyGroupBlobStore
import com.poyka.ripdpi.data.persistence.RoomRelayCredentialStore
import com.poyka.ripdpi.data.persistence.RoomRelayProfileStore
import com.poyka.ripdpi.data.persistence.RoomSelectorPersistence
import com.poyka.ripdpi.data.persistence.RoomWarpCredentialStore
import com.poyka.ripdpi.data.persistence.RoomWarpEndpointStore
import com.poyka.ripdpi.data.persistence.RoomWarpProfileStore
import com.poyka.ripdpi.data.persistence.RoomXrayProfileMetadataStore
import com.poyka.ripdpi.data.persistence.RoomXrayProfileSecretStore
import com.poyka.ripdpi.data.persistence.RoomXraySelectionStore
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.rules.RipDpiDatabaseMigrations
import com.poyka.ripdpi.data.selector.DefaultSelectorSelectionStore
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import java.io.Closeable
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Uses production stores and transactions with a test key because Robolectric has no Android Keystore. */
class RoomProfileFixture private constructor(
    private val context: Context,
    val authority: PauseIntentAuthority,
    val databaseName: String,
    private val key: SecretKey,
) : Closeable {
    constructor(
        context: Context,
        authority: PauseIntentAuthority,
        databaseName: String = "profile-fixture-${UUID.randomUUID()}.db",
    ) : this(context, authority, databaseName, KeyGenerator.getInstance("AES").apply { init(KeyBits) }.generateKey())

    private val cipher = FixtureProfileCipher(key)

    @Suppress("SpreadOperator")
    val database: RipDpiDatabase =
        Room
            .databaseBuilder(context, RipDpiDatabase::class.java, databaseName)
            .addMigrations(*RipDpiDatabaseMigrations.ALL)
            .addCallback(RipDpiDatabase.SeedCallback)
            .build()

    internal val profileDatabase = ProfileDatabase(database, cipher, LegacyProfileImporter(context, authority))
    private val selectorPersistence = RoomSelectorPersistence(profileDatabase, authority)

    val settings: AppSettingsRepository = RoomAppSettingsRepository(profileDatabase)
    val relayProfiles: RelayProfileStore = RoomRelayProfileStore(profileDatabase)
    val relayCredentials: RelayCredentialStore = RoomRelayCredentialStore(profileDatabase)
    val warpProfiles: WarpProfileStore = RoomWarpProfileStore(profileDatabase)
    val warpCredentials: WarpCredentialStore = RoomWarpCredentialStore(profileDatabase)
    val warpEndpoints: WarpEndpointStore = RoomWarpEndpointStore(profileDatabase)
    val xrayMetadata: XrayProfileMetadataStore = RoomXrayProfileMetadataStore(profileDatabase)
    val xraySecrets: XrayProfileSecretStore = RoomXrayProfileSecretStore(profileDatabase)
    val xraySelection: XrayProviderSelectionStore = RoomXraySelectionStore(profileDatabase)
    val profileSelection: ProfileSelectionStore = RoomProfileSelectionStore(profileDatabase)
    val awgProfiles: AwgProfileDao = database.awgProfileDao()
    val awgCredentials: AwgCredentialStore = RoomAwgCredentialStore(profileDatabase)
    val groupCatalog = ProxyGroupCatalog(RoomProxyGroupBlobStore(profileDatabase))
    val selectorActiveGroup = SelectorActiveGroupStore(selectorPersistence, authority)
    val mutationGeneration = ProfileMutationGenerationPublisher()
    val stores =
        ProfileMutationStores(
            settings,
            relayProfiles,
            relayCredentials,
            warpProfiles,
            warpCredentials,
            warpEndpoints,
            xrayMetadata,
            xraySecrets,
            xraySelection,
            profileSelection,
            groupCatalog,
            selectorActiveGroup,
        )
    val coordinator =
        ProfileMutationRecoveryCoordinator(
            stores,
            profileDatabase,
            mutationGeneration,
            authority,
            selectorActiveGroup,
        )
    val awgRepository = AwgProfileRepository(RoomAwgProfileReadStore(profileDatabase, awgProfiles), coordinator)
    val durableXrayProfiles: DurableXrayProfileStore = RoomDurableXrayProfileStore(profileDatabase)
    val selectorSelections =
        DefaultSelectorSelectionStore(selectorPersistence, coordinator, authority, selectorActiveGroup)
    val groups: ProxyGroupRepository = DefaultProxyGroupRepository(groupCatalog, coordinator)

    /** Runs on the database IO thread before encryption; throwing aborts the real transaction. */
    var beforeSeal: ((namespace: String, id: String) -> Unit)?
        get() = cipher.beforeSeal
        set(value) {
            cipher.beforeSeal = value
        }

    /** Runs on the database IO thread before decryption. */
    var beforeOpen: ((namespace: String, id: String) -> Unit)?
        get() = cipher.beforeOpen
        set(value) {
            cipher.beforeOpen = value
        }

    suspend fun initialize() = profileDatabase.initialize()

    /** Call after collectors finish. The new fixture keeps committed rows and discards transient state and hooks. */
    fun reopen(authority: PauseIntentAuthority = this.authority): RoomProfileFixture {
        close()
        return RoomProfileFixture(context, authority, databaseName, key)
    }

    override fun close() = database.close()

    private companion object {
        const val KeyBits = 256
    }
}
