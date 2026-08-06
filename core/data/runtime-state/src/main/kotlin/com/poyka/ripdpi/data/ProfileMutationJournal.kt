package com.poyka.ripdpi.data

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.nio.BufferUnderflowException
import java.security.GeneralSecurityException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class ProfileMutationFamily {
    Awg,
    Relay,
    Warp,
    Backup,
}

/** Encrypted write-ahead record for a cross-store profile mutation. */
@Serializable
data class PendingProfileMutation(
    val schemaVersion: Int = CurrentProfileMutationSchemaVersion,
    val mutationId: String = UUID.randomUUID().toString(),
    val family: ProfileMutationFamily,
    val payload: String,
)

interface ProfileMutationJournal {
    suspend fun prepare(mutation: PendingProfileMutation)

    suspend fun pending(): PendingProfileMutation?

    suspend fun replace(
        expectedMutationId: String,
        mutation: PendingProfileMutation,
    )

    suspend fun complete(mutationId: String)

    /** Removes an unreadable marker after recovery has observed the same corruption twice. */
    suspend fun discardCorruptPending()

    suspend fun clearForReset()
}

class ProfileMutationJournalCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** AndroidKeyStore-backed journal whose marker changes use checked synchronous commits on IO. */
@Singleton
class EncryptedProfileMutationJournal
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ProfileMutationJournal {
        private val mutex = Mutex()
        private val json = com.poyka.ripdpi.serialization.RipDpiContractJson
        private val blobStore =
            KeystoreEncryptedPreferences(
                preferences = context.getSharedPreferences(JournalPreferencesName, Context.MODE_PRIVATE),
                keyAlias = JournalKeyAlias,
            )

        override suspend fun prepare(mutation: PendingProfileMutation) =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    check(blobStore.getStringStrict(PendingEntryKey) == null) { "Profile mutation recovery is pending" }
                    blobStore.putString(
                        PendingEntryKey,
                        json.encodeToString(PendingProfileMutation.serializer(), mutation),
                    )
                }
            }

        override suspend fun pending(): PendingProfileMutation? =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        blobStore.getStringStrict(PendingEntryKey)?.let {
                            json.decodeFromString(PendingProfileMutation.serializer(), it)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: SerializationException) {
                        throw unreadableMarker(error)
                    } catch (error: GeneralSecurityException) {
                        throw unreadableMarker(error)
                    } catch (error: BufferUnderflowException) {
                        throw unreadableMarker(error)
                    } catch (error: NegativeArraySizeException) {
                        throw unreadableMarker(error)
                    } catch (error: IllegalArgumentException) {
                        throw unreadableMarker(error)
                    }
                }
            }

        override suspend fun replace(
            expectedMutationId: String,
            mutation: PendingProfileMutation,
        ) = mutex.withLock {
            withContext(Dispatchers.IO) {
                val pending =
                    blobStore.getStringStrict(PendingEntryKey)?.let {
                        json.decodeFromString(PendingProfileMutation.serializer(), it)
                    }
                check(pending?.mutationId == expectedMutationId) { "Profile mutation journal id changed" }
                blobStore.putString(
                    PendingEntryKey,
                    json.encodeToString(PendingProfileMutation.serializer(), mutation),
                )
            }
        }

        override suspend fun complete(mutationId: String) =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val pending =
                        blobStore.getStringStrict(PendingEntryKey)?.let {
                            json.decodeFromString(PendingProfileMutation.serializer(), it)
                        } ?: return@withContext
                    check(pending.mutationId == mutationId) { "Profile mutation journal id changed" }
                    blobStore.remove(PendingEntryKey)
                }
            }

        override suspend fun discardCorruptPending() =
            mutex.withLock {
                withContext(Dispatchers.IO) { blobStore.remove(PendingEntryKey) }
            }

        override suspend fun clearForReset() =
            mutex.withLock {
                withContext(Dispatchers.IO) { blobStore.clear() }
            }

        private companion object {
            const val JournalPreferencesName = "profile_mutation_journal"
            const val JournalKeyAlias = "ripdpi_profile_mutation_journal"
            const val PendingEntryKey = "pending"
        }
    }

private fun unreadableMarker(cause: Exception) =
    ProfileMutationJournalCorruptionException(
        message = "Profile mutation journal marker is unreadable",
        cause = cause,
    )

private const val CurrentProfileMutationSchemaVersion = 1

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileMutationJournalModule {
    @Binds
    @Singleton
    abstract fun bindProfileMutationJournal(journal: EncryptedProfileMutationJournal): ProfileMutationJournal
}
