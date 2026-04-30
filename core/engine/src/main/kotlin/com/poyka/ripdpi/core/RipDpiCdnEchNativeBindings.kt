package com.poyka.ripdpi.core

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// JNI bindings for the process-wide CdnEchUpdater.
//
// Three entry points:
//   - refresh(): pull a fresh ECH config from the DoH primary or the
//     bundled fallback. Returns the native JSON status.
//   - snapshot(): read the in-memory cache for persistence. Returns
//     null when the cache is cold; otherwise the bytes + wall-clock
//     fetch timestamp paired with them.
//   - seed(...): hydrate the cache from a previously-persisted
//     snapshot. Used at app startup so the TTL window survives
//     process restarts.
object RipDpiCdnEchNativeBindings {
    init {
        RipDpiNativeLoader.ensureLoaded()
    }

    fun refresh(): String = jniRefreshCdnEch() ?: "{\"ok\":false,\"error\":\"native_returned_null\"}"

    data class Snapshot(
        val configBytes: ByteArray,
        val fetchedAtUnixMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return fetchedAtUnixMs == other.fetchedAtUnixMs && configBytes.contentEquals(other.configBytes)
        }

        override fun hashCode(): Int = 31 * configBytes.contentHashCode() + fetchedAtUnixMs.hashCode()
    }

    // Returns null when the cache is cold; otherwise a Snapshot.
    fun snapshot(): Snapshot? {
        val payload = jniSnapshotCdnEch()?.toCdnEchSnapshotPayloadOrNull()
        val bytes = payload?.configBase64?.decodeBase64OrNull()
        return if (payload?.hasPersistedSnapshot == true && bytes != null) {
            Snapshot(configBytes = bytes, fetchedAtUnixMs = payload.fetchedAtUnixMs)
        } else {
            null
        }
    }

    private fun String.toCdnEchSnapshotPayloadOrNull(): CdnEchSnapshotPayload? =
        try {
            nativeBridgeJson.decodeFromString(CdnEchSnapshotPayload.serializer(), this)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun String.decodeBase64OrNull(): ByteArray? =
        try {
            Base64.decode(this, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun seed(snapshot: Snapshot): String {
        val configBase64 = Base64.encodeToString(snapshot.configBytes, Base64.NO_WRAP)
        return jniSeedCdnEch(configBase64, snapshot.fetchedAtUnixMs)
            ?: "{\"ok\":false,\"error\":\"native_returned_null\"}"
    }

    @JvmStatic
    private external fun jniRefreshCdnEch(): String?

    @JvmStatic
    private external fun jniSnapshotCdnEch(): String?

    @JvmStatic
    private external fun jniSeedCdnEch(
        configBase64: String,
        fetchedAtUnixMs: Long,
    ): String?
}

@Serializable
private data class CdnEchSnapshotPayload(
    val ok: Boolean = false,
    val empty: Boolean = false,
    @SerialName("fetchedAtUnixMs")
    val fetchedAtUnixMs: Long = 0L,
    @SerialName("configBase64")
    val configBase64: String? = null,
) {
    val hasPersistedSnapshot: Boolean
        get() = ok && !empty && fetchedAtUnixMs > 0L && !configBase64.isNullOrBlank()
}

private val nativeBridgeJson = Json { ignoreUnknownKeys = true }
