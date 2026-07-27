package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress

internal data class DirectDnsUnderlayLease<T>(
    val network: T,
    val networkGeneration: Long,
    val policyGeneration: Long,
    val leaseGeneration: Long,
)

internal data class ResolverUnderlaySnapshot<T>(
    val network: T,
    val generation: Long,
)

internal fun <T, R> resolveOnCurrentUnderlay(
    snapshot: () -> ResolverUnderlaySnapshot<T>?,
    resolve: (T) -> R,
): R? {
    val initial = snapshot() ?: return null
    val result = resolve(initial.network)
    return result.takeIf { snapshot() == initial }
}

private data class DirectDnsPreparedPolicy(
    val token: Long,
    val policyGeneration: Long,
    val candidates: Set<InetAddress>,
    val networkGeneration: Long?,
    val directUnderlayRequired: Boolean,
)

internal class DirectDnsUnderlayLeaseState<T> {
    private var networkGeneration = 0L
    private var policyGeneration = 0L
    private var leaseGeneration = 0L
    private var preparationToken = 0L
    private var network: T? = null
    private var networkDns: Set<InetAddress> = emptySet()
    private var preparedPolicy: DirectDnsPreparedPolicy? = null
    private var committedPolicy: DirectDnsPreparedPolicy? = null
    private var preparedLease: DirectDnsUnderlayLease<T>? = null
    private var committedLease: DirectDnsUnderlayLease<T>? = null

    fun capture(
        candidate: T?,
        snapshotGeneration: Long,
        eligible: Boolean,
        dnsServers: Set<InetAddress>,
    ): Boolean {
        val nextNetwork = candidate.takeIf { eligible }
        val nextDns = dnsServers.takeIf { eligible }.orEmpty()
        if (network == nextNetwork && networkGeneration == snapshotGeneration && networkDns == nextDns) return false
        networkGeneration = snapshotGeneration
        leaseGeneration += 1
        network = nextNetwork
        networkDns = nextDns
        val previousCommitted = committedLease
        refreshLeases()
        return previousCommitted != committedLease
    }

    fun invalidate(candidate: T): Boolean {
        if (network != candidate) return false
        networkGeneration += 1
        leaseGeneration += 1
        network = null
        networkDns = emptySet()
        refreshLeases()
        return true
    }

    fun preparePolicy(
        candidates: Set<InetAddress>,
        complete: Boolean,
        networkGeneration: Long?,
        required: Boolean = candidates.isNotEmpty(),
    ): Long {
        policyGeneration += 1
        preparationToken += 1
        preparedPolicy =
            DirectDnsPreparedPolicy(
                token = preparationToken,
                policyGeneration = policyGeneration,
                candidates = candidates.takeIf { complete }.orEmpty(),
                networkGeneration = networkGeneration.takeIf { complete },
                directUnderlayRequired = required,
            )
        preparedLease = buildLease(preparedPolicy, preparedLease)
        return preparationToken
    }

    fun commitPrepared(token: Long): Boolean {
        val policy = preparedPolicy?.takeIf { it.token == token } ?: return false
        committedPolicy = policy
        committedLease = preparedLease
        preparedPolicy = null
        preparedLease = null
        return true
    }

    fun abortPrepared(token: Long): Boolean {
        if (preparedPolicy?.token != token) return false
        preparedPolicy = null
        preparedLease = null
        return true
    }

    fun failClosed(token: Long): Boolean {
        val prepared = preparedPolicy?.takeIf { it.token == token }
        val committed = committedPolicy?.takeIf { it.token == token }
        val replacement = prepared ?: committed ?: return false
        preparedPolicy = null
        preparedLease = null
        committedPolicy =
            replacement.copy(
                candidates = emptySet(),
                networkGeneration = null,
            )
        committedLease = null
        return true
    }

    fun stop() {
        networkGeneration += 1
        leaseGeneration += 1
        network = null
        networkDns = emptySet()
        preparedPolicy = null
        committedPolicy = null
        preparedLease = null
        committedLease = null
    }

    fun clearCommittedIfSame(snapshot: DirectDnsUnderlayLease<T>?) {
        if (committedLease === snapshot) committedLease = null
    }

    fun preparedLease(token: Long): DirectDnsUnderlayLease<T>? =
        preparedLease?.takeIf { preparedPolicy?.token == token }

    fun committedLease(): DirectDnsUnderlayLease<T>? = committedLease

    fun preparedRequiresDirectUnderlay(token: Long): Boolean =
        preparedPolicy?.takeIf { it.token == token }?.directUnderlayRequired == true

    fun committedRequiresDirectUnderlay(): Boolean = committedPolicy?.directUnderlayRequired == true

    fun policySnapshot(): Pair<Long, Set<InetAddress>>? =
        if (network != null && networkDns.isNotEmpty()) networkGeneration to networkDns else null

    fun isCommittedCurrent(generation: Long): Boolean = committedLease?.leaseGeneration == generation

    private fun refreshLeases() {
        preparedLease = buildLease(preparedPolicy, preparedLease)
        committedLease = buildLease(committedPolicy, committedLease)
    }

    private fun buildLease(
        policy: DirectDnsPreparedPolicy?,
        current: DirectDnsUnderlayLease<T>?,
    ): DirectDnsUnderlayLease<T>? {
        val currentNetwork = network
        val currentPolicy = policy ?: return null
        val complete = currentPolicy.candidates.isNotEmpty() && networkDns.isNotEmpty()
        val exactGeneration = currentPolicy.networkGeneration == networkGeneration
        val exactDns = networkDns == currentPolicy.candidates
        val authorized = complete && exactGeneration && exactDns
        return if (currentNetwork != null && authorized) {
            current?.takeIf {
                it.network == currentNetwork &&
                    it.networkGeneration == networkGeneration &&
                    it.policyGeneration == currentPolicy.policyGeneration
            } ?: run {
                leaseGeneration += 1
                DirectDnsUnderlayLease(
                    currentNetwork,
                    networkGeneration,
                    currentPolicy.policyGeneration,
                    leaseGeneration,
                )
            }
        } else {
            null
        }
    }
}

internal interface DirectDnsFileDescriptor : AutoCloseable {
    val fileDescriptor: FileDescriptor
}

internal interface DirectDnsSocketBindingOps<T> {
    fun protect(fd: Int): Boolean

    fun duplicate(fd: Int): DirectDnsFileDescriptor

    fun bind(
        network: T,
        fileDescriptor: FileDescriptor,
    )
}

internal fun isDirectDnsUnderlayEligible(
    hasInternet: Boolean,
    validated: Boolean,
    notVpn: Boolean,
    captivePortal: Boolean,
): Boolean = hasInternet && validated && notVpn && !captivePortal

internal fun <T> applyPreparedDirectDnsLease(
    current: DirectDnsUnderlayLease<T>?,
    directUnderlayRequired: Boolean = current != null,
    publish: (List<T>?) -> Boolean,
    clearIfSame: (DirectDnsUnderlayLease<T>?) -> Unit,
    stillCurrent: () -> Boolean = { true },
): Boolean {
    val accepted = runCatching { publish(vpnUnderlay(current, directUnderlayRequired)) }.getOrDefault(false)
    return when {
        !accepted -> clearRejectedDirectDnsLease(current, directUnderlayRequired, publish, clearIfSame)
        current != null && !stillCurrent() -> clearStaleDirectDnsLease(current, publish, clearIfSame)
        else -> current != null
    }
}

private fun <T> clearRejectedDirectDnsLease(
    current: DirectDnsUnderlayLease<T>?,
    directUnderlayRequired: Boolean,
    publish: (List<T>?) -> Boolean,
    clearIfSame: (DirectDnsUnderlayLease<T>?) -> Unit,
): Boolean {
    clearIfSame(current)
    if (current != null && directUnderlayRequired) runCatching { publish(emptyList()) }
    return false
}

private fun <T> clearStaleDirectDnsLease(
    current: DirectDnsUnderlayLease<T>,
    publish: (List<T>?) -> Boolean,
    clearIfSame: (DirectDnsUnderlayLease<T>?) -> Unit,
): Boolean {
    clearIfSame(current)
    runCatching { publish(emptyList()) }
    return false
}

internal fun <T> vpnUnderlay(
    current: DirectDnsUnderlayLease<T>?,
    directUnderlayRequired: Boolean,
): List<T>? = current?.let { listOf(it.network) } ?: emptyList<T>().takeIf { directUnderlayRequired }

internal fun <T> leaseGenerationOrZero(current: DirectDnsUnderlayLease<T>?): Long = current?.leaseGeneration ?: 0L

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> registerPublishedCallback(
    lock: Any,
    prepare: () -> T?,
    register: (T) -> Unit,
    rollback: (T) -> Unit,
): Boolean =
    synchronized(lock) {
        val candidate = prepare() ?: return@synchronized false
        try {
            register(candidate)
            true
        } catch (error: RuntimeException) {
            rollback(candidate)
            throw error
        }
    }

internal fun <T> bindDirectDnsSocketLease(
    fd: Int,
    generation: Long,
    lease: () -> DirectDnsUnderlayLease<T>?,
    isCurrent: (Long) -> Boolean,
    ops: DirectDnsSocketBindingOps<T>,
): Boolean {
    val snapshot = lease()?.takeIf { it.leaseGeneration == generation } ?: return false
    val protected = runCatching { ops.protect(fd) }.getOrDefault(false)
    return protected &&
        runCatching {
            ops.duplicate(fd).use { duplicate -> ops.bind(snapshot.network, duplicate.fileDescriptor) }
        }.isSuccess &&
        isCurrent(generation)
}

internal class VpnUnderlyingNetworkBinder(
    private val service: RipDpiVpnService,
    private val authority: DirectDnsUnderlayAuthority,
) {
    private val connectivityManager = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val state = DirectDnsUnderlayLeaseState<Network>()
    private val publicationLock = Any()
    private val socketBindingOps =
        object : DirectDnsSocketBindingOps<Network> {
            override fun protect(fd: Int): Boolean = service.protect(fd)

            override fun duplicate(fd: Int): DirectDnsFileDescriptor {
                val descriptor = ParcelFileDescriptor.fromFd(fd)
                return object : DirectDnsFileDescriptor {
                    override val fileDescriptor: FileDescriptor = descriptor.fileDescriptor

                    override fun close() = descriptor.close()
                }
            }

            override fun bind(
                network: Network,
                fileDescriptor: FileDescriptor,
            ) = network.bindSocket(fileDescriptor)
        }
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var callbackEpoch: Long? = null
    private var livePublicationEnabled = false

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission) return
        registerPublishedCallback(
            lock = publicationLock,
            prepare = {
                if (callback != null) {
                    null
                } else {
                    val epoch = authority.beginCallbackEpoch()
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            transition(epoch) { authority.onAvailable(epoch, network) }
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            transition(epoch) { authority.onCapabilitiesChanged(epoch, network, capabilities) }
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            transition(epoch) { authority.onLinkPropertiesChanged(epoch, network, linkProperties) }
                        }

                        override fun onLost(network: Network) {
                            transition(epoch) { authority.onLost(epoch, network) }
                        }
                    }.also { candidate ->
                        callback = candidate
                        callbackEpoch = epoch
                    }
                }
            },
            register = connectivityManager::registerDefaultNetworkCallback,
            rollback = {
                callback = null
                callbackEpoch?.let(authority::endCallbackEpoch)
                callbackEpoch = null
                livePublicationEnabled = false
                val directUnderlayRequired =
                    synchronized(this) {
                        val required = state.committedRequiresDirectUnderlay()
                        state.stop()
                        required
                    }
                publish(vpnUnderlay<Network>(null, directUnderlayRequired)?.toTypedArray())
            },
        )
    }

    fun stop() {
        val registeredCallback: ConnectivityManager.NetworkCallback?
        synchronized(publicationLock) {
            registeredCallback = callback
            callback = null
            callbackEpoch?.let(authority::endCallbackEpoch)
            callbackEpoch = null
            livePublicationEnabled = false
            val directUnderlayRequired =
                synchronized(this) {
                    val required = state.committedRequiresDirectUnderlay()
                    state.stop()
                    required
                }
            publish(vpnUnderlay<Network>(null, directUnderlayRequired)?.toTypedArray())
        }
        registeredCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
    }

    suspend fun awaitEligibleUnderlay() {
        val epoch =
            synchronized(publicationLock) { callbackEpoch }
                ?: error("VPN underlay observer is unavailable")
        while (true) {
            authority.awaitEligible(epoch)
            val stillEligible =
                synchronized(publicationLock) {
                    callbackEpoch == epoch && authority.snapshot(epoch) != null
                }
            if (stillEligible) return
        }
    }

    fun preparePolicy(
        candidates: List<String>,
        leaseGeneration: Long?,
    ): Long {
        val parsed = candidates.mapNotNull(::parseDirectDnsNumericAddress).toSet()
        return synchronized(publicationLock) {
            synchronized(this) {
                state.preparePolicy(
                    candidates = parsed,
                    complete = parsed.size == candidates.size,
                    networkGeneration = leaseGeneration,
                    required = candidates.isNotEmpty(),
                )
            }
        }
    }

    fun commitPreparedLease(token: Long): Boolean {
        synchronized(publicationLock) {
            val current = currentPreparedLeaseLocked(token)
            val directUnderlayRequired = synchronized(this) { state.preparedRequiresDirectUnderlay(token) }
            val published =
                runCatching {
                    publish(vpnUnderlay(current, directUnderlayRequired)?.toTypedArray())
                }.getOrDefault(false)
            val stillCurrent = current == null || currentPreparedLeaseLocked(token) === current
            val committed =
                synchronized(this) {
                    if (published && stillCurrent) {
                        state.commitPrepared(token)
                    } else {
                        state.failClosed(token)
                    }
                }
            livePublicationEnabled = true
            if (!published || !stillCurrent) applyCommittedLeaseLocked()
            return committed && published && stillCurrent && current != null
        }
    }

    fun abortPreparedLease(token: Long) {
        synchronized(publicationLock) {
            synchronized(this) { state.abortPrepared(token) }
        }
    }

    fun failClosedPreparedLease(token: Long) {
        synchronized(publicationLock) {
            if (!synchronized(this) { state.failClosed(token) }) return
            livePublicationEnabled = true
            applyCommittedLeaseLocked()
        }
    }

    private fun applyCommittedLeaseLocked(): Boolean {
        val current = currentCommittedLeaseLocked()
        val directUnderlayRequired = synchronized(this) { state.committedRequiresDirectUnderlay() }
        return applyPreparedDirectDnsLease(
            current = current,
            directUnderlayRequired = directUnderlayRequired,
            publish = { networks -> publish(networks?.toTypedArray()) },
            clearIfSame = { snapshot -> synchronized(this) { state.clearCommittedIfSame(snapshot) } },
            stillCurrent = { currentCommittedLeaseLocked() === current },
        )
    }

    fun applyToBuilder(
        builder: android.net.VpnService.Builder,
        token: Long,
    ) {
        synchronized(publicationLock) {
            val current = currentPreparedLeaseLocked(token)
            val directUnderlayRequired = synchronized(this) { state.preparedRequiresDirectUnderlay(token) }
            builder.setUnderlyingNetworks(vpnUnderlay(current, directUnderlayRequired)?.toTypedArray())
        }
    }

    @Keep
    fun directDnsLeaseGeneration(): Long =
        synchronized(publicationLock) {
            leaseGenerationOrZero(currentCommittedLeaseLocked())
        }

    @Keep
    fun isDirectDnsLeaseCurrent(generation: Long): Boolean =
        synchronized(publicationLock) { currentCommittedLeaseLocked()?.leaseGeneration == generation }

    @Keep
    fun bindDirectDnsSocket(
        fd: Int,
        generation: Long,
    ): Boolean =
        synchronized(publicationLock) {
            bindDirectDnsSocketLease(
                fd = fd,
                generation = generation,
                lease = ::currentCommittedLeaseLocked,
                isCurrent = { currentCommittedLeaseLocked()?.leaseGeneration == it },
                ops = socketBindingOps,
            )
        }

    fun resolveHost(host: String): Array<String> {
        val addresses =
            resolveOnCurrentUnderlay(
                snapshot = { synchronized(publicationLock) { currentResolverUnderlayLocked() } },
                resolve = { network -> network.getAllByName(host).mapNotNull { it.hostAddress }.toTypedArray() },
            )
        return addresses ?: throw java.net.UnknownHostException("underlying network changed")
    }

    private fun syncAuthority(epoch: Long) {
        val (generation, snapshot) = authority.observation(epoch) ?: return
        val changed =
            synchronized(this) {
                state.capture(
                    candidate = snapshot?.network,
                    snapshotGeneration = generation,
                    eligible = snapshot != null,
                    dnsServers = snapshot?.dnsServers.orEmpty(),
                )
            }
        if (shouldPublishDirectDnsLease(changed, livePublicationEnabled)) applyCommittedLeaseLocked()
    }

    private inline fun transition(
        epoch: Long,
        update: () -> Unit,
    ) {
        synchronized(publicationLock) {
            update()
            syncAuthority(epoch)
        }
    }

    private fun currentPreparedLeaseLocked(token: Long): DirectDnsUnderlayLease<Network>? {
        val snapshot = callbackEpoch?.let(authority::snapshot)
        return snapshot?.let { current ->
            synchronized(this) {
                state.preparedLease(token)?.takeIf { lease ->
                    lease.networkGeneration == current.generation &&
                        lease.network == current.network &&
                        state.policySnapshot()?.second == current.dnsServers
                }
            }
        }
    }

    private fun currentCommittedLeaseLocked(): DirectDnsUnderlayLease<Network>? {
        val snapshot = callbackEpoch?.let(authority::snapshot)
        return snapshot?.let { current ->
            synchronized(this) {
                state.committedLease()?.takeIf { lease ->
                    lease.networkGeneration == current.generation &&
                        lease.network == current.network &&
                        state.policySnapshot()?.second == current.dnsServers
                }
            }
        }
    }

    private fun currentResolverUnderlayLocked(): ResolverUnderlaySnapshot<Network>? =
        callbackEpoch
            ?.let(authority::snapshot)
            ?.let { snapshot -> ResolverUnderlaySnapshot(snapshot.network, snapshot.generation) }

    private fun publish(networks: Array<Network>?): Boolean =
        runCatching {
            service.setUnderlyingNetworks(networks)
        }.getOrDefault(false)

    private val hasPermission: Boolean
        get() =
            ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED
}

private const val Ipv4OctetCount = 4
private const val MaxIpv4Octet = 255

internal fun parseDirectDnsNumericAddress(value: String): InetAddress? =
    parseDirectDnsIpv4(value) ?: parseDirectDnsIpv6(value)

private fun parseDirectDnsIpv4(value: String): InetAddress? {
    val octets = value.split('.')
    val parsed = octets.mapNotNull(::parseDirectDnsIpv4Octet)
    return parsed
        .takeIf { octets.size == Ipv4OctetCount && it.size == Ipv4OctetCount }
        ?.map(Int::toByte)
        ?.toByteArray()
        ?.let(InetAddress::getByAddress)
}

private fun parseDirectDnsIpv4Octet(value: String): Int? =
    value.toIntOrNull()?.takeIf { parsed -> parsed in 0..MaxIpv4Octet && parsed.toString() == value }

private fun parseDirectDnsIpv6(value: String): Inet6Address? {
    val numericGrammar =
        value.contains(':') &&
            !value.contains('%') &&
            value.all(::isDirectDnsIpv6Character)
    val parsed =
        value.takeIf { numericGrammar }?.let { numeric ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                numeric.takeIf(InetAddresses::isNumericAddress)?.let(InetAddresses::parseNumericAddress)
            } else {
                runCatching { InetAddress.getByName(numeric) }.getOrNull()
            }
        }
    return parsed as? Inet6Address
}

private fun isDirectDnsIpv6Character(value: Char): Boolean = value in "0123456789abcdefABCDEF:."

internal fun shouldPublishDirectDnsLease(
    snapshotChanged: Boolean,
    livePublicationEnabled: Boolean,
): Boolean = snapshotChanged && livePublicationEnabled
