package com.poyka.ripdpi.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.nio.BufferUnderflowException
import java.security.GeneralSecurityException
import java.util.UUID

@Serializable
enum class ProfileMutationFamily {
    Awg,
    Relay,
    Warp,
    Backup,
    Xray,
    Subscription,
}

/** Legacy record consumed once by the Room migration. */
@Serializable
data class PendingProfileMutation(
    val schemaVersion: Int = CurrentProfileMutationSchemaVersion,
    val mutationId: String = UUID.randomUUID().toString(),
    val family: ProfileMutationFamily,
    val payload: String,
    val origin: ProfileMutationOrigin?,
    val expectedPauseAuthority: PauseAuthorityRef?,
)

fun interface ProfileMutationJournal {
    suspend fun pending(): PendingProfileMutation?
}

class ProfileMutationJournalCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Read-only access to the encrypted journal from the previous storage format. */
class LegacyProfileMutationJournal(
    context: Context,
) : ProfileMutationJournal {
    private val blobStore =
        KeystoreEncryptedPreferences(
            preferences = context.getSharedPreferences(JournalPreferencesName, Context.MODE_PRIVATE),
            keyAlias = JournalKeyAlias,
            durableReads = true,
        )

    override suspend fun pending(): PendingProfileMutation? =
        access {
            try {
                blobStore.getStringStrict(PendingEntryKey)?.let {
                    decodePendingProfileMutation(it)
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

    private suspend fun <T> access(block: () -> T): T =
        withContext(Dispatchers.IO) { blobStore.withDurableState(block) }

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

private const val CurrentProfileMutationSchemaVersion = 2

/** Reads both journal formats that can exist before the Room migration. */
private fun decodePendingProfileMutation(encoded: String): PendingProfileMutation {
    val json = com.poyka.ripdpi.serialization.RipDpiContractJson
    val element = json.parseToJsonElement(encoded)
    val version =
        (element as kotlinx.serialization.json.JsonObject)["schemaVersion"]?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        } ?: throw kotlinx.serialization.SerializationException("Profile mutation schema is missing")
    if (version != 1) return json.decodeFromJsonElement(PendingProfileMutation.serializer(), element)
    val legacy = json.decodeFromJsonElement(LegacyPendingProfileMutation.serializer(), element)
    return PendingProfileMutation(1, legacy.mutationId, legacy.family, legacy.payload, null, null)
}

@Serializable
private data class LegacyPendingProfileMutation(
    val schemaVersion: Int = 1,
    val mutationId: String,
    val family: ProfileMutationFamily,
    val payload: String,
)
