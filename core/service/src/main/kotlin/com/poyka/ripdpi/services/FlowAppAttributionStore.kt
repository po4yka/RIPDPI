package com.poyka.ripdpi.services

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import co.touchlab.kermit.Logger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** UID sentinel for an unresolvable flow owner (matches `Process.INVALID_UID`). */
internal const val InvalidUid = -1

/** Bytes of the SHA-256 prefix kept in the digest — mirrors the Rust 8-byte prefix. */
private const val DigestPrefixBytes = 8

/** Mask to read a [Byte] as an unsigned value for hex formatting. */
private const val UnsignedByteMask = 0xFF

/**
 * The owning-app attribution for a resolved flow.
 *
 * The package name is held **in memory only** for the per-app direct-mode policy
 * decision; it is never persisted or exported (see
 * `.claude/rules/network-fingerprint-privacy.md`).
 */
sealed interface FlowAttribution {
    data class Attributed(
        val packageName: String,
        val versionCode: Long,
    ) : FlowAttribution

    /** Conservative verdict: shared/unknown UID, multiple packages, or lookup failure. */
    data object Unattributed : FlowAttribution
}

/**
 * Destination digest for telemetry parity, mirroring the Rust
 * `direct_path_ip_set_digest` for a single destination IP: the lowercase hex of
 * the first [DigestPrefixBytes] bytes of `SHA-256(ip_string)`.
 *
 * App identity is never inferred from this digest.
 */
fun flowAttributionDigest(destIp: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(destIp.toByteArray(Charsets.UTF_8))
    return bytes.take(DigestPrefixBytes).joinToString("") { byte -> "%02x".format(byte.toInt() and UnsignedByteMask) }
}

/**
 * Decide attribution from a resolved UID and its packages. Pure and conservative:
 * an invalid UID, no packages, a shared UID (more than one package), or a missing
 * version all collapse to [FlowAttribution.Unattributed].
 */
internal fun decideAttribution(
    uid: Int,
    packagesForUid: Array<String>?,
    versionOf: (String) -> Long?,
): FlowAttribution {
    val packageName = if (uid == InvalidUid) null else packagesForUid?.singleOrNull()
    val versionCode = packageName?.let(versionOf)
    return if (packageName != null && versionCode != null) {
        FlowAttribution.Attributed(packageName, versionCode)
    } else {
        FlowAttribution.Unattributed
    }
}

/**
 * Session-owned map from opaque application scopes to transient package identities.
 */
interface FlowAppAttributionStore {
    /** Start a new TUN ownership epoch. */
    fun beginSession(): Long = 0L

    /** Retire only the epoch which owns this token. */
    fun endSession(session: Long) = clear()

    /** Resolve a request only while its TUN epoch remains current. */
    fun resolveSessionFlowUid(
        session: Long,
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
        requestKind: Int,
    ): Int = resolveFlowRequest(this, protocol, localIp, localPort, remoteIp, remotePort, requestKind)

    /** Resolve the owning UID and an optional opaque application scope. */
    fun resolveSessionOwner(
        session: Long,
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
        requestKind: Int,
    ): FlowOwnerResolution =
        FlowOwnerResolution(
            resolveSessionFlowUid(session, protocol, localIp, localPort, remoteIp, remotePort, requestKind),
        )

    /**
     * Resolve a freshly seen flow in the active TUN session.
     */
    fun noteFlow(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    )

    /** Resolve and record the flow, returning its UID for native admission or [InvalidUid]. */
    fun resolveFlowUid(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int {
        noteFlow(protocol, localIp, localPort, remoteIp, remotePort)
        return InvalidUid
    }

    /** Resolve only the owning UID without allocating an application scope. */
    fun resolveFlowUidOnly(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int

    /** Look up an opaque application scope; a missing scope has no IP fallback. */
    fun lookup(appScope: String): FlowAttribution.Attributed?

    /** Drop remembered attributions for [packageName] whose version differs from [newVersionCode]. */
    fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    )

    /** Clear all attributions (e.g. on tunnel session teardown). */
    fun clear()
}

// Matches the native attribution cache bound (ripdpi-flow-app-attribution CACHE_CAPACITY).
private const val FlowAttributionCacheCapacity = 4096

@Singleton
class DefaultFlowAppAttributionStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FlowAppAttributionStore {
        private val attributions = FlowAttributionCache(FlowAttributionCacheCapacity)

        override fun beginSession(): Long = attributions.beginSession()

        override fun endSession(session: Long) = attributions.endSession(session)

        override fun resolveSessionFlowUid(
            session: Long,
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
            requestKind: Int,
        ): Int = resolveSessionOwner(session, protocol, localIp, localPort, remoteIp, remotePort, requestKind).uid

        override fun resolveSessionOwner(
            session: Long,
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
            requestKind: Int,
        ): FlowOwnerResolution {
            val revision = attributions.resolutionRevision(session) ?: return FlowOwnerResolution(InvalidUid)
            val (uid, attribution) =
                if (requestKind == AdmissionOnlyFlowRequest) {
                    resolveOwnerUid(protocol, localIp, localPort, remoteIp, remotePort) to null
                } else {
                    resolveAttribution(protocol, localIp, localPort, remoteIp, remotePort)
                }
            return attributions.commitOwner(session, revision, uid, attribution)
        }

        override fun noteFlow(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ) {
            resolveFlowUid(protocol, localIp, localPort, remoteIp, remotePort)
        }

        override fun resolveFlowUid(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ): Int =
            resolveSessionFlowUid(attributions.currentSession(), protocol, localIp, localPort, remoteIp, remotePort, 0)

        override fun resolveFlowUidOnly(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ): Int =
            resolveSessionFlowUid(
                attributions.currentSession(),
                protocol,
                localIp,
                localPort,
                remoteIp,
                remotePort,
                AdmissionOnlyFlowRequest,
            )

        override fun lookup(appScope: String): FlowAttribution.Attributed? =
            attributions.get(appScope) as? FlowAttribution.Attributed

        override fun invalidateOnAppUpdate(
            packageName: String,
            newVersionCode: Long,
        ) {
            attributions.removeIf { attribution ->
                attribution is FlowAttribution.Attributed &&
                    attribution.packageName == packageName &&
                    attribution.versionCode != newVersionCode
            }
        }

        override fun clear() {
            attributions.clear()
        }

        private fun resolveAttribution(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ): Pair<Int, FlowAttribution> {
            val uid = resolveOwnerUid(protocol, localIp, localPort, remoteIp, remotePort)
            return if (uid == InvalidUid) {
                InvalidUid to FlowAttribution.Unattributed
            } else {
                uid to
                    decideAttribution(uid, context.packageManager.getPackagesForUid(uid)) { pkg ->
                        runCatching {
                            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(pkg, 0))
                        }.getOrNull()
                    }
            }
        }

        private fun resolveOwnerUid(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return InvalidUid
            return runCatching {
                val connectivity =
                    context.getSystemService(ConnectivityManager::class.java)
                        ?: return@runCatching InvalidUid
                connectivity.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(InetAddress.getByName(localIp), localPort),
                    InetSocketAddress(InetAddress.getByName(remoteIp), remotePort),
                )
            }.getOrElse { error ->
                log.w(error) { "flow owner resolution failed" }
                InvalidUid
            }
        }

        private companion object {
            private val log = Logger.withTag("FlowAppAttribution")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class FlowAppAttributionStoreModule {
    @Binds
    @Singleton
    abstract fun bindFlowAppAttributionStore(store: DefaultFlowAppAttributionStore): FlowAppAttributionStore
}
