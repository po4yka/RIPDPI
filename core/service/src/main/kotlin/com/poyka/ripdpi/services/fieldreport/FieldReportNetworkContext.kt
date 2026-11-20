package com.poyka.ripdpi.services.fieldreport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val HttpOk = 200
private const val HintTimeoutSeconds = 15L
private const val MaxHintBytes = 1_024L
private const val MillisPerSecond = 1_000L
private const val HintSchemaVersion = 1L
private const val MinHintTtlSeconds = 300L
private const val MaxHintTtlSeconds = 86_400L
private const val MaxHintAsn = 4_294_967_295L

/** Wait after a fetch that gave no hint (non-200, invalid body, tunnelled, or a network change). */
private const val NoHintIntervalSeconds = 3_600L

/** Upper bound of the random delay before a fetch, so it does not follow the bundle request at a fixed offset. */
private const val MaxStartJitterMillis = 30_000L

/** The extra wait after each fetch is random in 0..interval/JitterDivisor. */
private const val JitterDivisor = 4L

private val HintKeys = setOf("schema_version", "asn", "country", "observed_at", "ttl_seconds")
private val HintObservedAtPattern =
    Regex("[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):00:00Z")

/** The app's default network: [handle] identifies it for the hint cache, [network] binds the hint request. */
internal data class DefaultNetwork(
    val handle: Long,
    val networkType: String,
    val vpn: Boolean,
    val network: Network? = null,
)

/** A valid `client-network-hint` v1 body; it is usable until [expiresAtMillis] (observed_at + ttl_seconds). */
internal data class NetworkHint(
    val asn: Long,
    val country: String,
    val ttlSeconds: Long,
    val expiresAtMillis: Long,
)

/** Sends GET to the hint endpoint directly on [network]; the 200 body, or null for any other outcome. */
internal fun interface NetworkHintFetcher {
    suspend fun fetch(
        endpoint: String,
        cohortToken: String,
        network: DefaultNetwork,
    ): String?
}

/**
 * Parses a `client-network-hint` v1 body with every schema rule, or returns null when one fails. An `observed_at`
 * after [nowMillis] (server clock ahead) counts from [nowMillis], so a hint never outlives its ttl on this device.
 */
internal fun parseNetworkHint(
    body: String,
    nowMillis: Long,
): NetworkHint? {
    val hint =
        (runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject)
            ?.takeIf { it.keys == HintKeys && it.integer("schema_version") == HintSchemaVersion }
            ?: return null
    val asn = hint.integer("asn")?.takeIf { it in 1..MaxHintAsn }
    val country = hint.text("country")?.takeIf(CountryPattern::matches)
    val ttl = hint.integer("ttl_seconds")?.takeIf { it in MinHintTtlSeconds..MaxHintTtlSeconds }
    val observedAt =
        hint
            .text("observed_at")
            ?.takeIf(HintObservedAtPattern::matches)
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    // The ttl and the expiry instant it gives.
    val validity =
        if (ttl != null && observedAt != null) ttl to minOf(observedAt, nowMillis) + ttl * MillisPerSecond else null
    return if (asn != null && country != null && validity != null) {
        NetworkHint(asn, country, validity.first, validity.second)
    } else {
        null
    }
}

private fun JsonObject.integer(key: String): Long? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless {
            it.isString
        }?.longOrNull

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * The network label of a field-report hour. A cellular default network is labelled by its operator MCC-MNC, read on
 * the device. Any other network is labelled by the server network hint when one is cached for that same network,
 * otherwise by no identity. The hint is kept only in memory, for one network handle, until it expires.
 */
@Singleton
class FieldReportNetworkContext internal constructor(
    private val consent: suspend () -> Boolean,
    private val defaultNetwork: () -> DefaultNetwork?,
    private val operatorCode: () -> String?,
    private val fetcher: NetworkHintFetcher,
    private val clock: () -> Long,
    private val random: Random,
    private val pause: suspend (Long) -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: AppSettingsRepository,
        tlsClientFactory: OwnedTlsClientFactory,
    ) : this(
        consent = { settings.snapshot().fieldReportEnabled },
        defaultNetwork = { androidDefaultNetwork(context) },
        operatorCode = { androidOperatorCode(context) },
        fetcher = OkHttpNetworkHintFetcher(tlsClientFactory),
        clock = System::currentTimeMillis,
        random = Random.Default,
        pause = { delay(it) },
    )

    private data class CachedHint(
        val handle: Long,
        val hint: NetworkHint?,
        val nextFetchAtMillis: Long,
    )

    private val lock = Any()
    private var cached: CachedHint? = null
    private var hintEpoch = 0L
    private var inFlight: Job? = null

    /** Drops the cached hint; consent revocation calls it so no hint outlives the consent it was fetched under. */
    fun clearHint() {
        val pending =
            synchronized(lock) {
                hintEpoch++
                cached = null
                inFlight.also { inFlight = null }
            }
        pending?.cancel()
    }

    /** The label for an attempt measured now. */
    fun current(): FieldReportNetwork {
        val network = defaultNetwork()
        val hint =
            synchronized(lock) {
                // A network change drops the hint: it describes only the network it was fetched on.
                if (cached?.handle != network?.handle) cached = null
                cached?.hint?.takeIf { clock() < it.expiresAtMillis }
            }
        return when {
            network == null || network.vpn -> {
                FieldReportNetwork(SourceNone, "other")
            }

            network.networkType == "cellular" -> {
                simNetwork(operatorCode())
                    ?: FieldReportNetwork(SourceNone, "cellular")
            }

            hint != null -> {
                FieldReportNetwork(SourceServerHint, network.networkType, asn = hint.asn, country = hint.country)
            }

            else -> {
                FieldReportNetwork(SourceNone, network.networkType)
            }
        }
    }

    /**
     * Called with the bundle refresh. Fetches the hint from the first target that names a `network_hint_endpoint`
     * when consent is on, the default network is not cellular and not a VPN, and the last fetch on this network is
     * older than its interval (the hint ttl_seconds, or [NoHintIntervalSeconds] without a hint) plus random jitter.
     * The hint is discarded when the default network changed during the request.
     */
    suspend fun refreshHint(targets: List<RipdpiFieldReport>) {
        val (target, endpoint) =
            targets.firstNotNullOfOrNull { report -> report.networkHintEndpoint?.let { report to it } } ?: return
        val epoch = synchronized(lock) { hintEpoch }
        if (!consent()) return
        val before = defaultNetwork()?.takeUnless { it.vpn || it.networkType == "cellular" } ?: return
        coroutineScope {
            val task = launch(start = CoroutineStart.LAZY) { fetchHint(target, endpoint, before, epoch) }
            if (startHint(task, before, epoch)) {
                try {
                    task.join()
                } finally {
                    synchronized(lock) { if (inFlight === task) inFlight = null }
                }
            } else {
                task.cancel()
            }
        }
    }

    private fun startHint(
        task: Job,
        network: DefaultNetwork,
        epoch: Long,
    ): Boolean =
        synchronized(lock) {
            val retryAt = cached?.takeIf { it.handle == network.handle }?.nextFetchAtMillis
            if (epoch != hintEpoch || inFlight != null || (retryAt != null && clock() < retryAt)) {
                false
            } else {
                inFlight = task
                true
            }
        }

    private suspend fun fetchHint(
        target: RipdpiFieldReport,
        endpoint: String,
        before: DefaultNetwork,
        epoch: Long,
    ) {
        pause(random.nextLong(MaxStartJitterMillis))
        if (!consent() || defaultNetwork() != before) return
        currentCoroutineContext().ensureActive()
        val body = fetcher.fetch(endpoint, target.cohortToken, before)
        val sameNetwork = defaultNetwork() == before
        val now = clock()
        val hint = body?.let { parseNetworkHint(it, now) }?.takeIf { sameNetwork && now < it.expiresAtMillis }
        val intervalMillis = (hint?.ttlSeconds ?: NoHintIntervalSeconds) * MillisPerSecond
        val nextFetchAt = now + intervalMillis + random.nextLong(intervalMillis / JitterDivisor + 1)
        if (consent()) {
            synchronized(lock) {
                if (epoch == hintEpoch) cached = CachedHint(before.handle, hint, nextFetchAt)
            }
        }
    }
}

private fun androidDefaultNetwork(context: Context): DefaultNetwork? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity?.activeNetwork
    val capabilities = network?.let { connectivity?.getNetworkCapabilities(it) }
    if (network == null || capabilities == null) return null
    val type =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    return DefaultNetwork(
        network.networkHandle,
        type,
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        network,
    )
}

/**
 * The registered network operator's MCC+MNC on the default data subscription, else the SIM operator's.
 * getNetworkOperator and getSimOperator need no runtime permission (only the telephony feature), so no
 * READ_PHONE_STATE grant is used; a missing feature or a platform error gives null.
 */
private fun androidOperatorCode(context: Context): String? =
    runCatching {
        val defaultManager = context.getSystemService(TelephonyManager::class.java)
        val subscription = SubscriptionManager.getDefaultDataSubscriptionId()
        val telephony =
            if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                defaultManager
            } else {
                defaultManager?.createForSubscriptionId(subscription)
            }
        telephony?.networkOperator?.takeIf { simNetwork(it) != null } ?: telephony?.simOperator
    }.getOrNull()

/**
 * Sends the hint request on the bound underlying network with no proxy, never through the tunnel listener: the
 * resolver drops loopback addresses, and a connection to a proxy or a loopback address discards the response.
 */
internal class OkHttpNetworkHintFetcher(
    private val tlsClientFactory: OwnedTlsClientFactory,
) : NetworkHintFetcher {
    override suspend fun fetch(
        endpoint: String,
        cohortToken: String,
        network: DefaultNetwork,
    ): String? {
        val binding = network.network ?: return null
        return withContext(Dispatchers.IO) {
            val tunnelled = AtomicBoolean(false)
            val client =
                tlsClientFactory.create {
                    callTimeout(HintTimeoutSeconds, TimeUnit.SECONDS)
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    proxy(Proxy.NO_PROXY)
                    socketFactory(binding.socketFactory)
                    dns { host ->
                        binding.getAllByName(host).filterNot { it.isLoopbackAddress }.ifEmpty {
                            throw UnknownHostException(host)
                        }
                    }
                    eventListener(directOnlyListener(tunnelled))
                }
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $cohortToken")
                    .get()
                    .build()
            try {
                client.newCall(request).executeAsync().use { response ->
                    val source = response.body.source()
                    when {
                        response.code != HttpOk || tunnelled.get() -> null
                        source.request(MaxHintBytes + 1) -> null
                        else -> source.buffer.readUtf8()
                    }
                }
            } catch (_: IOException) {
                null
            }
        }
    }
}

internal fun directOnlyListener(tunnelled: AtomicBoolean): EventListener =
    object : EventListener() {
        override fun connectStart(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
        ) {
            if (proxy.type() != Proxy.Type.DIRECT || inetSocketAddress.address?.isLoopbackAddress != false) {
                tunnelled.set(true)
            }
        }
    }
