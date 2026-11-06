package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.testing.LegacyProfileKeyStore
import com.poyka.ripdpi.serialization.RipDpiContractJson
import java.io.Closeable

/** Real encrypted legacy preferences and a real new Room database share one test lifetime. */
internal class LegacyProfileImportFixture(
    initialAuthority: PauseAuthorityState? = null,
) : Closeable {
    private val keyStore = LegacyProfileKeyStore()
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fixture = ProfileMutationTestFixture(initialAuthority)
    val journal = LegacyProfileMutationJournal(context)

    suspend fun seed(
        intent: ProfileMutationIntent,
        schemaVersion: Int = 2,
        origin: ProfileMutationOrigin? = ProfileMutationOrigin.SubscriptionRefresh,
        expected: PauseAuthorityRef? = PauseAuthorityRef(0),
    ): PendingProfileMutation =
        seedPayload(
            intent.family,
            RipDpiContractJson.encodeToString(ProfileMutationIntent.serializer(), intent),
            schemaVersion,
            origin,
            expected,
        )

    suspend fun seedPayload(
        family: ProfileMutationFamily,
        payload: String,
        schemaVersion: Int = 2,
        origin: ProfileMutationOrigin? = ProfileMutationOrigin.SubscriptionRefresh,
        expected: PauseAuthorityRef? = PauseAuthorityRef(0),
    ): PendingProfileMutation {
        val pending = PendingProfileMutation(schemaVersion, "legacy-import", family, payload, origin, expected)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeLegacyJournal(pending) }
        return pending
    }

    /** Creates the previous release's byte framing with the test key provider. */
    private fun writeLegacyJournal(pending: PendingProfileMutation) {
        val keyStore =
            java.security.KeyStore
                .getInstance("AndroidKeyStore")
                .apply { load(null) }
        val key = keyStore.getKey("ripdpi_profile_mutation_journal", null) as javax.crypto.SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val plaintext = RipDpiContractJson.encodeToString(PendingProfileMutation.serializer(), pending)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val framed =
            java.nio.ByteBuffer
                .allocate(Int.SIZE_BYTES + cipher.iv.size + encrypted.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(encrypted)
                .array()
        check(
            context
                .getSharedPreferences("profile_mutation_journal", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putString("pending", android.util.Base64.encodeToString(framed, android.util.Base64.NO_WRAP))
                .commit(),
        )
    }

    fun seedGroups(
        json: String,
        version: Int = 1,
    ) {
        check(
            context
                .getSharedPreferences("proxy_group_cache", Context.MODE_PRIVATE)
                .edit()
                .putString("proxy-groups", json)
                .putInt("proxy-groups-schema-version", version)
                .commit(),
        )
    }

    fun legacyBytes(): Map<String, Map<String, *>> =
        listOf("profile_mutation_journal", "proxy_group_cache")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }

    override fun close() {
        fixture.close()
        keyStore.close()
    }
}
