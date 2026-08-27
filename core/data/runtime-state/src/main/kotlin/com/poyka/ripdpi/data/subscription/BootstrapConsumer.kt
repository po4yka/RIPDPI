package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
     * request and completes the deferred; every caller that joins the same
     * window awaits it and observes the winner's actual result (so a 30-way
     * double-tap all see one [BootstrapConsumeResult.Consumed]).
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<BootstrapConsumeResult>>()

    /**
     * Settled-token cache. Once a token reaches a terminal state, any *later*
     * `consume` call (a manual refresh tap, an auto-update worker run) sees this
     * entry and short-circuits with [BootstrapConsumeResult.AlreadyConsumed] —
     * no network round-trip.
     */
    private val settled = ConcurrentHashMap<String, BootstrapConsumeResult.AlreadyConsumed>()

    /**
     * Consumes the bootstrap subscription at [url], stamping produced profiles
     * with [groupId].
     *
     * Idempotent per token: once a token has reached a terminal state, every
     * later call returns [BootstrapConsumeResult.AlreadyConsumed] with no
     * network call. Callers that race the *same* in-flight consumption all
     * observe the winner's live result. Never throws.
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
                claim.deferred.await()
            }

            is Claim.Winner -> {
                val result = fetchOnce(url, groupId)
                mutex.withLock {
                    if (result.isTerminal) {
                        settled[key] = BootstrapConsumeResult.AlreadyConsumed(terminalStamp(result))
                    }
                    inFlight.remove(key)
                }
                claim.deferred.complete(result)
                result
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

    /** Epoch-millis stamp carried by a terminal result. */
    private fun terminalStamp(result: BootstrapConsumeResult): Long =
        when (result) {
            is BootstrapConsumeResult.Consumed -> result.consumedAtMillis
            is BootstrapConsumeResult.AlreadyConsumed -> result.consumedAtMillis
            else -> clockMillis()
        }

    private suspend fun fetchOnce(
        url: String,
        groupId: String,
    ): BootstrapConsumeResult =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == HttpGone -> {
                            BootstrapConsumeResult.AlreadyConsumed(clockMillis())
                        }

                        !response.isSuccessful -> {
                            BootstrapConsumeResult.NetworkError("HTTP ${response.code}")
                        }

                        else -> {
                            val body =
                                try {
                                    response.body.readBoundedSubscriptionPayload()
                                } catch (_: SubscriptionPayloadTooLargeException) {
                                    return@use BootstrapConsumeResult.ParseError("bootstrap payload exceeds limit")
                                }
                            parseBody(body, groupId)
                        }
                    }
                }
            } catch (error: IOException) {
                BootstrapConsumeResult.NetworkError(error.message ?: "network unreachable")
            }
        }

    @Suppress("ReturnCount")
    private fun parseBody(
        body: String,
        groupId: String,
    ): BootstrapConsumeResult {
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
            val selector = SelectorUrltestGroupImport.import(body, groupId)
            if (selector !is SelectorUrltestImportResult.Success) {
                return BootstrapConsumeResult.ParseError("invalid bootstrap selector")
            }
            return BootstrapConsumeResult.Consumed(
                profiles = singBox.profiles,
                consumedAtMillis = clockMillis(),
                packageRoutingRules = singBox.packageRoutingRules,
                amneziaWgProfiles = singBox.amneziaWgProfiles,
                subscriptionMirrors = singBox.subscriptionMirrors ?: SubscriptionMirrorSet(),
                // These ids belong to singBox.profiles, not the selector's independent parse.
                cloudflareMemberIds = singBox.cloudflareMemberIds.orEmpty(),
                isSelector = selector.group != null,
                failover = selector.failoverPolicy.toSelectorFailover(),
            )
        }
        val base64 = Base64SubscriptionParser.parse(body, groupId)
        if (base64.profiles.isNotEmpty() && base64.profiles.fitsSubscriptionLimits()) {
            return BootstrapConsumeResult.Consumed(base64.profiles, clockMillis())
        }
        return BootstrapConsumeResult.ParseError("no profiles in bootstrap payload")
    }

    private val BootstrapConsumeResult.isTerminal: Boolean
        get() =
            this is BootstrapConsumeResult.Consumed ||
                this is BootstrapConsumeResult.AlreadyConsumed
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
