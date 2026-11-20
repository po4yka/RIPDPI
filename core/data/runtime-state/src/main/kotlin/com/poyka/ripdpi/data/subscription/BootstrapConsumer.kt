package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.validateNativeRelayProfileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** HTTP 410 Gone — the deployer's bootstrap service answers this for a spent token. */
private const val HttpGone = 410

/**
 * Outcome of a bootstrap one-time subscription consume attempt.
 *
 * A bootstrap URL (`/bootstrap/<sha256(token)>`) is single-use: the server
 * deletes the on-disk hash file after the first successful GET. The client must
 * therefore consume it exactly once and never re-fetch.
 */
sealed interface BootstrapConsumeResult {
    /**
     * The token was consumed: a 200 response was parsed into [profiles] and the
     * subscription should be persisted with `consumedAt = [consumedAtMillis]`.
     */
    data class Consumed(
        val profiles: List<ProxyProfile>,
        val consumedAtMillis: Long,
        val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
        val amneziaWgProfiles: List<AmneziaWgSubscriptionProfile> = emptyList(),
        val subscriptionMirrors: SubscriptionMirrorSet = SubscriptionMirrorSet(),
        val cloudflareMemberIds: Set<String> = emptySet(),
        val isSelector: Boolean = false,
        val failover: SelectorFailover? = null,
        val migration: MigrationMetadata? = null,
        val payloadSha256: String = "",
        val initialMemberId: String? = null,
        val memberHealth: Map<String, EndpointHealth> = emptyMap(),
        val fieldReport: RipdpiFieldReport? = null,
    ) : BootstrapConsumeResult

    /**
     * Terminal "already consumed" state. Either the server answered HTTP 410
     * Gone (the token was already spent or expired), or this process already
     * consumed the token earlier. Not a network error; never retried. The row
     * is created/kept with `consumedAt = [consumedAtMillis]` and zero profiles.
     */
    data class AlreadyConsumed(
        val consumedAtMillis: Long,
    ) : BootstrapConsumeResult

    /** A transient transport failure (timeout, DNS, non-410 5xx). Safe to retry later. */
    data class NetworkError(
        val reason: String,
    ) : BootstrapConsumeResult

    /** The 200 payload could not be parsed into any profile. */
    data class ParseError(
        val reason: String,
        val consumedAtMillis: Long? = null,
        /** The entry the native backend refused, when that is why the payload was rejected. */
        val node: SingBoxSkippedNode? = null,
    ) : BootstrapConsumeResult
}

/**
 * Consumes bootstrap one-time subscription tokens exactly once.
 *
 * Concurrency contract: a per-token [Mutex] (keyed on [bootstrapTokenHash])
 * serializes consumption so a UI double-tap — or 30 concurrent callers — fires
 * exactly one HTTP request. The winner's result is cached under the same key,
 * so every losing caller (and every later manual-refresh / worker run)
 * short-circuits with the cached [BootstrapConsumeResult] and never touches the
 * network again.
 *
 * The raw token never appears in logs: only its sha256 hash is used as the
 * cache and mutex key.
 *
 * @param clockMillis injectable clock for deterministic `consumedAt` stamping.
 */
class BootstrapConsumer(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * In-flight consumption per token. The winning caller fires the single HTTP
     * request and completes the deferred. Callers for the same target group
     * share the parsed result; another group gets a spent-token result instead
     * of foreign-owned profiles.
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<BootstrapConsumeResult>>()

    /**
     * Settled-token cache. Once a token reaches a terminal state, any *later*
     * `consume` call (a manual refresh tap, an auto-update worker run) sees this
     * entry and never replays the delivery. A rejected delivered payload keeps
     * its parse error; a successfully imported token becomes AlreadyConsumed.
     */
    private val settled = ConcurrentHashMap<String, BootstrapConsumeResult>()
    private val deliveryHttpClient =
        httpClient
            .newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(
                (
                    httpClient.callTimeoutMillis.takeIf { it > 0 }?.coerceAtMost(BootstrapCallTimeoutMillis)
                        ?: BootstrapCallTimeoutMillis
                ).toLong(),
                TimeUnit.MILLISECONDS,
            ).build()

    /**
     * Consumes the bootstrap subscription at [url], stamping produced profiles
     * with [groupId].
     *
     * Idempotent per token: once a token has reached a terminal state, every
     * later call returns the cached terminal state with no network call.
     * Callers that race the *same* in-flight consumption all
     * observe the winner's live result only for the same target group; another
     * target group sees AlreadyConsumed instead of foreign-owned members. Never throws.
     */
    @Suppress("ReturnCount")
    suspend fun consume(
        url: String,
        groupId: String,
    ): BootstrapConsumeResult {
        val key = bootstrapTokenHash(url)
        // Fast path: a prior terminal result already settled this token.
        settled[key]?.let { return it }

        val mutex = locks.getOrPut(key) { Mutex() }
        // Claim phase under the lock: decide winner vs. joiner, then release so
        // the single HTTP request does not serialize joiners behind it.
        val claim =
            mutex.withLock {
                settled[key]?.let { return it }
                val existing = inFlight[key]
                if (existing != null) {
                    Claim.Joiner(existing)
                } else {
                    val winnerDeferred = CompletableDeferred<BootstrapConsumeResult>()
                    inFlight[key] = winnerDeferred
                    Claim.Winner(winnerDeferred)
                }
            }

        return when (claim) {
            is Claim.Joiner -> {
                val result = claim.deferred.await()
                if (result is BootstrapConsumeResult.Consumed &&
                    (
                        result.profiles.any { it.groupId != groupId } ||
                            result.amneziaWgProfiles.any { it.groupId != groupId }
                    )
                ) {
                    BootstrapConsumeResult.AlreadyConsumed(result.consumedAtMillis)
                } else {
                    result
                }
            }

            is Claim.Winner -> {
                // The server spends the token as soon as the blocking request completes, so a cancelled
                // caller must still record the outcome and release joiners instead of dropping it.
                withContext(NonCancellable) {
                    try {
                        val result = fetchOnce(url, groupId)
                        mutex.withLock {
                            result.terminalOutcome()?.let { settled[key] = it }
                            inFlight.remove(key)
                        }
                        claim.deferred.complete(result)
                        result
                    } finally {
                        if (!claim.deferred.isCompleted) {
                            mutex.withLock { inFlight.remove(key) }
                            claim.deferred.complete(BootstrapConsumeResult.NetworkError("bootstrap fetch failed"))
                        }
                    }
                }
            }
        }
    }

    private sealed interface Claim {
        /** This caller owns the consumption and must fire the single HTTP request. */
        data class Winner(
            val deferred: CompletableDeferred<BootstrapConsumeResult>,
        ) : Claim

        /** Another caller is already consuming; await its live result. */
        data class Joiner(
            val deferred: CompletableDeferred<BootstrapConsumeResult>,
        ) : Claim
    }

    private fun BootstrapConsumeResult.terminalOutcome(): BootstrapConsumeResult? =
        when (this) {
            is BootstrapConsumeResult.Consumed -> BootstrapConsumeResult.AlreadyConsumed(consumedAtMillis)
            is BootstrapConsumeResult.AlreadyConsumed -> this
            is BootstrapConsumeResult.ParseError -> takeIf { consumedAtMillis != null }
            is BootstrapConsumeResult.NetworkError -> null
        }

    private suspend fun fetchOnce(
        url: String,
        groupId: String,
    ): BootstrapConsumeResult =
        withContext(Dispatchers.IO) {
            val request =
                try {
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .build()
                } catch (_: IllegalArgumentException) {
                    return@withContext BootstrapConsumeResult.ParseError("invalid bootstrap URL")
                }
            var delivered = false
            try {
                deliveryHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == HttpGone -> {
                            BootstrapConsumeResult.AlreadyConsumed(clockMillis())
                        }

                        !response.isSuccessful -> {
                            BootstrapConsumeResult.NetworkError("HTTP ${response.code}")
                        }

                        else -> {
                            delivered = true
                            val body =
                                try {
                                    response.body.readBoundedSubscriptionPayload()
                                } catch (_: SubscriptionPayloadTooLargeException) {
                                    return@use rejectedDelivery("bootstrap payload exceeds limit")
                                } catch (_: IllegalArgumentException) {
                                    return@use rejectedDelivery("invalid bootstrap payload encoding")
                                }
                            when (val result = parseBody(body, groupId)) {
                                is BootstrapConsumeResult.ParseError -> result.copy(consumedAtMillis = clockMillis())
                                else -> result
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                if (delivered) {
                    rejectedDelivery("incomplete bootstrap payload")
                } else {
                    BootstrapConsumeResult.NetworkError("bootstrap request failed")
                }
            }
        }

    @Suppress("ReturnCount")
    private fun parseBody(
        payload: SubscriptionPayload,
        groupId: String,
    ): BootstrapConsumeResult {
        val body = payload.text
        if (body.isBlank()) {
            return BootstrapConsumeResult.ParseError("empty bootstrap payload")
        }
        // Try sing-box JSON first, then fall back to the base64 / plain URI list
        // parser — the same precedence the long-lived subscription path uses.
        val singBox = SingBoxSubscriptionParser.parse(body, groupId)
        val singBoxProfileCount =
            if (singBox is SingBoxParseResult.Success) {
                singBox.profiles.size + singBox.amneziaWgProfiles.size
            } else {
                0
            }
        if (singBox is SingBoxParseResult.Success &&
            singBoxProfileCount in 1..MaxSubscriptionProfiles &&
            singBox.profiles.fitsSubscriptionLimits()
        ) {
            // A one-time payload is installed whole or not at all; the refused entry is named for the user.
            (singBox.skipped + splitNativeProfiles(singBox.profiles).second)
                .firstOrNull()
                ?.let { return BootstrapConsumeResult.ParseError("unsupported bootstrap node", node = it) }
            if (singBox.migration != null) {
                try {
                    payload.requireMigrationUtf8()
                } catch (_: IllegalArgumentException) {
                    return BootstrapConsumeResult.ParseError("invalid migration encoding")
                }
            }
            val selector = SelectorUrltestGroupImport.import(body, groupId)
            if (selector !is SelectorUrltestImportResult.Success) {
                return BootstrapConsumeResult.ParseError("invalid bootstrap selector")
            }
            val members = selector.group?.members ?: selector.profiles
            val migrationTags = members.map { it.displayName } + singBox.amneziaWgProfiles.map { it.displayName }
            if (singBox.migration?.targetTags?.any { it !in migrationTags } == true) {
                return BootstrapConsumeResult.ParseError("migration target is not a selected bootstrap member")
            }
            return BootstrapConsumeResult.Consumed(
                profiles = members,
                consumedAtMillis = clockMillis(),
                packageRoutingRules = singBox.packageRoutingRules,
                amneziaWgProfiles = singBox.amneziaWgProfiles,
                subscriptionMirrors = singBox.subscriptionMirrors ?: SubscriptionMirrorSet(),
                migration = singBox.migration,
                payloadSha256 = payload.sha256,
                cloudflareMemberIds = selector.group?.cloudflareMemberIds ?: selector.cloudflareMemberIds.orEmpty(),
                isSelector = selector.group != null,
                initialMemberId = selector.group?.initialMemberId,
                memberHealth = selector.memberHealth,
                fieldReport = selector.fieldReport,
                failover = selector.failoverPolicy.toSelectorFailover(),
            )
        }
        if (singBox is SingBoxParseResult.Error && singBox.recognizedBundle) {
            return BootstrapConsumeResult.ParseError("invalid bootstrap JSON metadata")
        }
        return parseLinkList(body, groupId)
    }

    private fun parseLinkList(
        body: String,
        groupId: String,
    ): BootstrapConsumeResult {
        val base64 = Base64SubscriptionParser.parse(body, groupId)
        val hasCompleteProfiles = base64.warnings.isEmpty() && base64.profiles.isNotEmpty()
        if (hasCompleteProfiles &&
            base64.profiles.fitsSubscriptionLimits() && base64.profiles.hasOnlyNativeProfiles()
        ) {
            return BootstrapConsumeResult.Consumed(base64.profiles, clockMillis())
        }
        return BootstrapConsumeResult.ParseError("no profiles in bootstrap payload")
    }

    private fun List<ProxyProfile>.hasOnlyNativeProfiles(): Boolean =
        all { it !is ProxyProfile.RawConfig && validateNativeRelayProfileResult(it).getOrDefault(false) }

    private fun rejectedDelivery(reason: String) = BootstrapConsumeResult.ParseError(reason, clockMillis())

    private companion object {
        const val BootstrapCallTimeoutMillis = 15_000
    }
}

/**
 * Heuristic: a deployer bootstrap URL has a `/bootstrap/` path segment, vs. a
 * long-lived subscription's `/sub/`. The user can override this on the add
 * screen with an explicit toggle.
 */
fun isBootstrapUrl(url: String): Boolean = bootstrapPathOf(url).startsWith("/bootstrap/")

/**
 * Stable consume-once key for a bootstrap [url]: the hex sha256 of
 * `host || path || query` (the query carries the token in some deployer
 * shapes). The raw token is never returned — the digest is the only thing
 * persisted, so it is safe to log and to use as a map key.
 */
fun bootstrapTokenHash(url: String): String {
    val host = hostOf(url)
    val path = bootstrapPathOf(url)
    val query = url.substringAfter('?', "").substringBefore('#')
    val material = host + path + query
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun hostOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    val afterScheme = if (schemeEnd >= 0) url.substring(schemeEnd + "://".length) else url
    return afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
}

private fun bootstrapPathOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    val afterAuthority =
        if (schemeEnd >= 0) {
            val rest = url.substring(schemeEnd + "://".length)
            val slash = rest.indexOf('/')
            if (slash >= 0) rest.substring(slash) else ""
        } else {
            url
        }
    return afterAuthority.substringBefore('?').substringBefore('#')
}
