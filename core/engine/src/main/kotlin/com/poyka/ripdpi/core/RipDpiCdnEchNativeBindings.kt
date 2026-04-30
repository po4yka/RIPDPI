package com.poyka.ripdpi.core

import android.util.Base64

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

    // Returns null when the cache is cold; otherwise a Snapshot. JSON
    // parsing is intentionally hand-rolled so this module has no
    // serialization-library dep -- Retrofit/Moshi already covers what
    // the wider codebase needs but the parse here is one-shot and
    // tightly bounded.
    fun snapshot(): Snapshot? {
        val raw = jniSnapshotCdnEch() ?: return null
        if (!raw.contains("\"ok\":true")) return null
        if (raw.contains("\"empty\":true")) return null
        val fetchedAt =
            FETCHED_AT_PATTERN
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return null
        val configBase64 = CONFIG_BASE64_PATTERN.find(raw)?.groupValues?.getOrNull(1) ?: return null
        val bytes = runCatching { Base64.decode(configBase64, Base64.NO_WRAP) }.getOrNull() ?: return null
        return Snapshot(configBytes = bytes, fetchedAtUnixMs = fetchedAt)
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

    private val FETCHED_AT_PATTERN = "\"fetchedAtUnixMs\"\\s*:\\s*(\\d+)".toRegex()
    private val CONFIG_BASE64_PATTERN = "\"configBase64\"\\s*:\\s*\"([^\"]+)\"".toRegex()
}
