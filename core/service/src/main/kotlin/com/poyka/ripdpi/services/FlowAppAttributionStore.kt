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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** UID sentinel for an unresolvable flow owner (matches `Process.INVALID_UID`). */
internal const val InvalidUid = -1

/** Bytes of the SHA-256 prefix kept in the digest — mirrors the Rust 8-byte prefix. */
private const val DigestPrefixBytes = 8

/** Mask to read a [Byte] as an unsigned value for hex formatting. */
private const val UnsignedByteMask = 0xFF

/**
 * The owning-app attribution for a flow's destination.
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
 * Destination digest for the cross-`.so` join, mirroring the Rust
 * `direct_path_ip_set_digest` for a single destination IP: the lowercase hex of
 * the first [DigestPrefixBytes] bytes of `SHA-256(ip_string)`.
 *
 * The TUN `.so` passes the destination as a Rust `IpAddr::to_string()` string, so
 * hashing that exact string here yields the same digest the proxy `.so` derives
 * from a single-IP target set. Multi-IP target sets produce a different digest
 * and therefore fall back to unattributed — a documented limitation.
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
 * Process-wide map of `destination-IP digest -> owning app`, the Kotlin join point
 * between the tun2socks `.so` (which knows the originating app 5-tuple) and the
 * proxy `.so`'s direct-path learning signals (which carry only the destination
 * digest). See the completed `attribute-direct-mode-flows-to-app-package` task in git history.
 */
interface FlowAppAttributionStore {
    /**
     * Record a freshly seen flow's owning app. Called (via JNI) from the tun2socks
     * session when a new destination is first observed. Resolves UID -> package ->
     * version and stores the result keyed by the destination digest. A repeated
     * destination is a no-op (already resolved).
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

    /** Resolve only the owning UID without recording destination attribution. */
    fun resolveFlowUidOnly(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int

    /** Look up the attribution for a learning signal's `ipSetDigest`. */
    fun lookup(ipSetDigest: String): FlowAttribution.Attributed?

    /** Drop remembered attributions for [packageName] whose version differs from [newVersionCode]. */
    fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    )

    /** Clear all attributions (e.g. on tunnel session teardown). */
    fun clear()
}

@Singleton
class DefaultFlowAppAttributionStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FlowAppAttributionStore {
        private val attributions = ConcurrentHashMap<String, FlowAttribution>()

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
        ): Int {
            val digest = flowAttributionDigest(remoteIp)
            // Overwrite rather than putIfAbsent: the native queue already dedupes
            // noteFlow calls per destination (one per dest until evict_flow_if_current on
            // close), so a fresh call here means the destination was re-seen and
            // must be re-resolved -- e.g. after a different app reuses the IP.
            val (uid, attribution) = resolveAttribution(protocol, localIp, localPort, remoteIp, remotePort)
            attributions[digest] = attribution
            return uid
        }

        override fun resolveFlowUidOnly(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ): Int = resolveOwnerUid(protocol, localIp, localPort, remoteIp, remotePort)

        override fun lookup(ipSetDigest: String): FlowAttribution.Attributed? =
            attributions[ipSetDigest] as? FlowAttribution.Attributed

        override fun invalidateOnAppUpdate(
            packageName: String,
            newVersionCode: Long,
        ) {
            attributions.entries.removeIf { (_, attribution) ->
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
