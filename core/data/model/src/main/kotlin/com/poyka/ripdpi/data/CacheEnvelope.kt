package com.poyka.ripdpi.data

enum class CacheSource(
    val wireValue: String,
) {
    Bundled("bundled"),
    Fetched("fetched"),
}

/**
 * Metadata envelope wrapping a cached snapshot.
 *
 * @param schemaVersion monotonically-increasing format version; callers reject reads where
 *   this value is higher than the version they understand.
 * @param storedAt epoch-milliseconds at which the snapshot was persisted.
 * @param source whether the payload originated from a bundled asset or a remote fetch.
 * @param payload the wrapped snapshot value.
 */
data class CacheEnvelope<T>(
    val schemaVersion: Int,
    val storedAt: Long,
    val source: CacheSource,
    val payload: T,
)

/**
 * Typed result of loading a cached value.
 *
 * Callers that intentionally tolerate fallback must opt in by calling [withFallback] or
 * [withFallbackOrNull]; they can no longer silently mask corruption via getOrNull / getOrDefault.
 */
sealed class CacheResult<out T> {
    data class Success<T>(
        val value: T,
        val envelope: CacheEnvelope<T>,
    ) : CacheResult<T>()

    data class Degraded(
        val reason: CacheDegradation,
    ) : CacheResult<Nothing>()
}

/**
 * Returns the cached value on [CacheResult.Success] or [default] on [CacheResult.Degraded].
 * Callers must explicitly opt in rather than accidentally masking corruption.
 */
fun <T> CacheResult<T>.withFallback(default: T): T =
    when (this) {
        is CacheResult.Success -> value
        is CacheResult.Degraded -> default
    }

/**
 * Returns the cached value on [CacheResult.Success] or null on [CacheResult.Degraded].
 */
fun <T> CacheResult<T>.withFallbackOrNull(): T? =
    when (this) {
        is CacheResult.Success -> value
        is CacheResult.Degraded -> null
    }
