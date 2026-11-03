package com.poyka.ripdpi.ui.screens.connection

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.poyka.ripdpi.activities.isValid
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

internal const val ConnectionEditorRecoverySessionIdSavedStateKey = "config_editor_recovery_session_id"
internal const val ConnectionEditorInvalidatedRecoverySessionIdsSavedStateKey =
    "config_editor_invalidated_recovery_session_ids"
internal const val ConnectionEditorMaxInvalidatedSessionIds = 32
internal const val ConnectionEditorDraftMaxRecordBytes = 1024 * 1024
internal const val ConnectionEditorDraftTtlMillis = 7L * 24L * 60L * 60L * 1000L
internal const val ConnectionEditorDraftPreferencesName = "config_editor_recovery_secure"
internal const val ConnectionEditorDraftRecordKey = "active-record"
internal const val ConnectionEditorDraftInvalidatedSessionIdsKey = "invalidated-session-ids"

internal fun newConfigEditorRecoverySessionId(): String = UUID.randomUUID().toString()

internal fun isValidConfigEditorRecoverySessionId(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal interface ConnectionEditorDraftStore {
    suspend fun restore(recoverySessionId: String): ConnectionEditorDraftRestoreResult

    suspend fun persist(
        recoverySessionId: String,
        session: ConnectionEditorSession,
    )

    suspend fun delete(recoverySessionId: String)

    suspend fun invalidate(recoverySessionId: String)
}

internal sealed interface ConnectionEditorDraftRestoreResult {
    data class Restored(
        val session: ConnectionEditorSession,
    ) : ConnectionEditorDraftRestoreResult

    data object MissingOrInvalid : ConnectionEditorDraftRestoreResult

    data class RestoreFailed(
        val cause: Throwable,
    ) : ConnectionEditorDraftRestoreResult
}

internal fun interface ConnectionEditorDraftCipher {
    fun encrypt(value: String): String

    fun decrypt(value: String): String = error("Decrypt is not implemented")
}

internal class CorruptConfigEditorDraftException(
    cause: Throwable? = null,
) : Exception(cause)

@Singleton
internal class EncryptedConfigEditorDraftStore
    internal constructor(
        private val preferences: SharedPreferences,
        private val cipher: ConnectionEditorDraftCipher,
        private val currentTimeMillis: () -> Long,
    ) : ConnectionEditorDraftStore {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(
            preferences = context.getSharedPreferences(ConnectionEditorDraftPreferencesName, Context.MODE_PRIVATE),
            cipher = AndroidKeystoreConfigEditorDraftCipher(),
            currentTimeMillis = System::currentTimeMillis,
        )

        private val recordLock = Any()
        private val invalidatedInProcess = mutableSetOf<String>()

        override suspend fun restore(recoverySessionId: String): ConnectionEditorDraftRestoreResult =
            withContext(Dispatchers.IO) {
                if (!isValidConfigEditorRecoverySessionId(recoverySessionId)) {
                    return@withContext ConnectionEditorDraftRestoreResult.MissingOrInvalid
                }
                if (isInvalidated(recoverySessionId)) {
                    return@withContext ConnectionEditorDraftRestoreResult.MissingOrInvalid
                }
                val encoded =
                    runCatching { readRecord() }
                        .getOrElse { failure ->
                            if (failure.isCorruptEncryptedRecord()) {
                                deleteRecordUnconditionally()
                                return@withContext ConnectionEditorDraftRestoreResult.MissingOrInvalid
                            }
                            return@withContext ConnectionEditorDraftRestoreResult.RestoreFailed(failure)
                        }
                        ?: return@withContext ConnectionEditorDraftRestoreResult.MissingOrInvalid
                if (encoded.toByteArray(Charsets.UTF_8).size > ConnectionEditorDraftMaxRecordBytes) {
                    deleteRecordUnconditionally()
                    return@withContext ConnectionEditorDraftRestoreResult.MissingOrInvalid
                }
                val record =
                    runCatching { RipDpiJson.decodeFromString<ConnectionEditorDraftRecord>(encoded) }
                        .getOrNull()
                if (record == null) {
                    deleteRecordUnconditionally()
                    ConnectionEditorDraftRestoreResult.MissingOrInvalid
                } else if (!record.isValid(recoverySessionId, currentTimeMillis())) {
                    deleteRecord(recoverySessionId)
                    ConnectionEditorDraftRestoreResult.MissingOrInvalid
                } else {
                    ConnectionEditorDraftRestoreResult.Restored(record.toSession())
                }
            }

        override suspend fun persist(
            recoverySessionId: String,
            session: ConnectionEditorSession,
        ) = withContext(Dispatchers.IO) {
            if (!isValidConfigEditorRecoverySessionId(recoverySessionId) || !session.isRecoverable()) {
                deleteRecord(recoverySessionId)
                return@withContext
            }
            val encoded =
                RipDpiJson.encodeToString(
                    ConnectionEditorDraftRecord.from(
                        recoverySessionId = recoverySessionId,
                        session = session,
                        savedAtEpochMillis = currentTimeMillis(),
                    ),
                )
            check(encoded.toByteArray(Charsets.UTF_8).size <= ConnectionEditorDraftMaxRecordBytes) {
                "Mode editor recovery record exceeds the size limit"
            }
            val encrypted = cipher.encrypt(encoded)
            synchronized(recordLock) {
                if (
                    recoverySessionId in invalidatedInProcess ||
                    invalidatedSessionIds(preferences).contains(recoverySessionId)
                ) {
                    return@withContext
                }
                check(preferences.edit().putString(ConnectionEditorDraftRecordKey, encrypted).commit()) {
                    "Could not durably persist mode editor recovery"
                }
            }
        }

        override suspend fun delete(recoverySessionId: String) =
            withContext(Dispatchers.IO) {
                deleteRecord(recoverySessionId)
            }

        override suspend fun invalidate(recoverySessionId: String) =
            withContext(Dispatchers.IO) {
                if (!isValidConfigEditorRecoverySessionId(recoverySessionId)) return@withContext
                synchronized(recordLock) {
                    val previousInvalidated =
                        preferences.getString(ConnectionEditorDraftInvalidatedSessionIdsKey, null)
                    val previousRecord = preferences.getString(ConnectionEditorDraftRecordKey, null)
                    val invalidated =
                        (invalidatedSessionIds(preferences) + recoverySessionId)
                            .distinct()
                            .takeLast(ConnectionEditorMaxInvalidatedSessionIds)
                            .joinToString(",")
                    val editor =
                        preferences
                            .edit()
                            .putString(ConnectionEditorDraftInvalidatedSessionIdsKey, invalidated)
                    if (storedRecoverySessionId() == recoverySessionId) {
                        editor.remove(ConnectionEditorDraftRecordKey)
                    }
                    if (!editor.commit()) {
                        val rollback = preferences.edit()
                        if (previousInvalidated == null) {
                            rollback.remove(ConnectionEditorDraftInvalidatedSessionIdsKey)
                        } else {
                            rollback.putString(ConnectionEditorDraftInvalidatedSessionIdsKey, previousInvalidated)
                        }
                        if (previousRecord == null) {
                            rollback.remove(ConnectionEditorDraftRecordKey)
                        } else {
                            rollback.putString(ConnectionEditorDraftRecordKey, previousRecord)
                        }
                        rollback.commit()
                        error("Could not durably invalidate mode editor recovery")
                    }
                    invalidatedInProcess += recoverySessionId
                }
            }

        private fun isInvalidated(recoverySessionId: String): Boolean =
            synchronized(recordLock) {
                recoverySessionId in invalidatedInProcess ||
                    invalidatedSessionIds(preferences).contains(recoverySessionId)
            }

        private fun invalidatedSessionIds(preferences: SharedPreferences): List<String> =
            preferences
                .getString(ConnectionEditorDraftInvalidatedSessionIdsKey, null)
                .orEmpty()
                .split(',')
                .filter(::isValidConfigEditorRecoverySessionId)

        private fun deleteRecord(expectedRecoverySessionId: String) {
            synchronized(recordLock) {
                if (!preferences.contains(ConnectionEditorDraftRecordKey)) return
                val storedSessionId = storedRecoverySessionId()
                if (storedSessionId == null || storedSessionId != expectedRecoverySessionId) return
                check(preferences.edit().remove(ConnectionEditorDraftRecordKey).commit()) {
                    "Could not delete mode editor recovery"
                }
            }
        }

        private fun readRecord(): String? =
            preferences.getString(ConnectionEditorDraftRecordKey, null)?.let(cipher::decrypt)

        private fun storedRecoverySessionId(): String? =
            preferences
                .getString(ConnectionEditorDraftRecordKey, null)
                ?.let { encrypted -> runCatching { cipher.decrypt(encrypted) }.getOrNull() }
                ?.takeIf { encoded ->
                    encoded.toByteArray(Charsets.UTF_8).size <= ConnectionEditorDraftMaxRecordBytes
                }?.let { encoded ->
                    runCatching { RipDpiJson.decodeFromString<ConnectionEditorDraftRecord>(encoded) }
                        .getOrNull()
                        ?.recoverySessionId
                }

        private fun deleteRecordUnconditionally() {
            synchronized(recordLock) {
                check(preferences.edit().remove(ConnectionEditorDraftRecordKey).commit()) {
                    "Could not delete invalid mode editor recovery"
                }
            }
        }
    }

private fun Throwable.isCorruptEncryptedRecord(): Boolean = this is CorruptConfigEditorDraftException

internal class AndroidKeystoreConfigEditorDraftCipher : ConnectionEditorDraftCipher {
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(AesTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload =
            ByteBuffer
                .allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(value: String): String {
        val payload =
            try {
                Base64.decode(value, Base64.NO_WRAP)
            } catch (failure: IllegalArgumentException) {
                throw CorruptConfigEditorDraftException(failure)
            }
        val (iv, ciphertext) = decodePayload(payload)
        val cipher = Cipher.getInstance(AesTransformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GcmTagLengthBits, iv))
        return try {
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (failure: AEADBadTagException) {
            throw CorruptConfigEditorDraftException(failure)
        }
    }

    private fun decodePayload(payload: ByteArray): Pair<ByteArray, ByteArray> =
        try {
            require(payload.size in MinimumEncryptedRecordBytes..MaximumEncryptedRecordBytes)
            val buffer = ByteBuffer.wrap(payload)
            val ivLength = buffer.int
            require(ivLength in MinimumIvBytes..MaximumIvBytes)
            require(buffer.remaining() >= ivLength + GcmTagLengthBytes)
            val iv = ByteArray(ivLength).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            iv to ciphertext
        } catch (failure: IllegalArgumentException) {
            throw CorruptConfigEditorDraftException(failure)
        } catch (failure: BufferUnderflowException) {
            throw CorruptConfigEditorDraftException(failure)
        }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeystore).apply { load(null) }
        (keyStore.getKey(MasterKeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeystore)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    MasterKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(AesKeySizeBits)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val MasterKeyAlias = "ripdpi_config_editor_recovery"
        const val AndroidKeystore = "AndroidKeyStore"
        const val AesTransformation = "AES/GCM/NoPadding"
        const val AesKeySizeBits = 256
        const val GcmTagLengthBits = 128
        const val GcmTagLengthBytes = GcmTagLengthBits / Byte.SIZE_BITS
        const val MinimumIvBytes = 12
        const val MaximumIvBytes = 32
        const val MinimumEncryptedRecordBytes = Int.SIZE_BYTES + MinimumIvBytes + GcmTagLengthBytes
        const val MaximumEncryptedRecordBytes = ConnectionEditorDraftMaxRecordBytes + 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ConnectionEditorDraftStoreModule {
    @Binds
    @Singleton
    abstract fun bindConfigEditorDraftStore(store: EncryptedConfigEditorDraftStore): ConnectionEditorDraftStore
}

@Serializable
private data class ConnectionEditorDraftRecord(
    val schemaVersion: Int,
    val recoverySessionId: String,
    val savedAtEpochMillis: Long,
    val presetId: String,
    val baselineDraft: ConnectionDraft,
    val draft: ConnectionDraft,
    val draftRevision: Long,
) {
    fun isValid(
        expectedRecoverySessionId: String,
        now: Long,
    ): Boolean =
        schemaVersion == SchemaVersion &&
            recoverySessionId == expectedRecoverySessionId &&
            savedAtEpochMillis > 0L &&
            savedAtEpochMillis <= now + ClockSkewToleranceMillis &&
            now - savedAtEpochMillis <= ConnectionEditorDraftTtlMillis &&
            presetId.isNotBlank() &&
            presetId.length <= MaxPresetIdLength &&
            draftRevision > 0L &&
            draft != baselineDraft

    fun toSession(): ConnectionEditorSession =
        ConnectionEditorSession(
            presetId = presetId,
            baselineDraft = baselineDraft,
            draft = draft,
            draftRevision = draftRevision,
        )

    companion object {
        fun from(
            recoverySessionId: String,
            session: ConnectionEditorSession,
            savedAtEpochMillis: Long,
        ): ConnectionEditorDraftRecord =
            ConnectionEditorDraftRecord(
                schemaVersion = SchemaVersion,
                recoverySessionId = recoverySessionId,
                savedAtEpochMillis = savedAtEpochMillis,
                presetId = requireNotNull(session.presetId),
                baselineDraft = requireNotNull(session.baselineDraft),
                draft = requireNotNull(session.draft),
                draftRevision = session.draftRevision,
            )
    }
}

private fun ConnectionEditorSession.isRecoverable(): Boolean =
    !hydrationPending && presetId != null && baselineDraft != null && draft != null && isDirty

private const val SchemaVersion = 1
private const val MaxPresetIdLength = 128
private const val ClockSkewToleranceMillis = 5L * 60L * 1000L
