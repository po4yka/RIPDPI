package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.pm.PackageInfoCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the set of VPN-family package names to watch for version changes.
 * Provided by the app-level DI module so that `core:service` does not depend on
 * `core:detection` directly.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VpnFamilyPackages

/**
 * Observes package replace/removal events for known VPN-app-family packages and
 * invalidates the cached DNS path preference when any of them is updated.
 *
 * Rationale: the preferred encrypted-DNS resolver is cached per network fingerprint
 * (networkScopeKey). A VPN app update may change which DNS path the device uses, so
 * stale verdicts could direct the VPN to an incompatible resolver endpoint.
 * Clearing the preference store forces re-selection on the next VPN session.
 *
 * It also performs the eager half of per-app flow-attribution invalidation: on
 * ANY app's package-replace/remove it drops that app's cached attribution from
 * [FlowAppAttributionStore] (version-scoped), so a later flow re-resolves the new
 * version instead of inheriting the old one. The lazy half (evict-on-flow-close +
 * version-keyed lookup) already provides correctness; this just makes it eager.
 */
@Singleton
open class DnsPathPreferenceInvalidator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val flowAppAttributionStore: FlowAppAttributionStore,
        @param:ApplicationScope private val appScope: CoroutineScope,
        @param:VpnFamilyPackages internal val trackedPackages: Set<String>,
    ) {
        private companion object {
            private val log = Logger.withTag("DnsPathInvalidator")

            /** Version that matches no real `longVersionCode`, so a removed app's entries are all dropped. */
            private const val REMOVED_APP_VERSION = -1L
        }

        /** Returns true if [packageName] belongs to a tracked VPN app family. */
        internal fun isTracked(packageName: String): Boolean = packageName in trackedPackages

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    // Flow attribution: applies to ANY app, not just the VPN family.
                    invalidateFlowAttribution(pkg)
                    if (!isTracked(pkg)) return
                    log.i { "VPN app package updated/removed: $pkg — invalidating DNS path preference cache" }
                    appScope.launch {
                        runCatching { networkDnsPathPreferenceStore.clearAll() }
                            .onFailure { log.w(it) { "Failed to clear DNS path preferences after $pkg update" } }
                    }
                }
            }

        /**
         * Drop [packageName]'s cached flow attribution. On replace, resolve the new
         * version so only stale-version entries are dropped; on removal (lookup
         * fails) use [REMOVED_APP_VERSION] so all of the package's entries go.
         */
        private fun invalidateFlowAttribution(packageName: String) {
            val newVersion =
                runCatching {
                    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
                }.getOrDefault(REMOVED_APP_VERSION)
            flowAppAttributionStore.invalidateOnAppUpdate(packageName, newVersion)
        }

        /** Register the broadcast receiver. Call once during application or service startup. */
        open fun register() {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
            context.registerReceiver(receiver, filter)
            log.d { "registered for PACKAGE_REPLACED/REMOVED on ${trackedPackages.size} VPN app packages" }
        }

        /** Unregister the broadcast receiver. Call when the registration context is destroyed. */
        open fun unregister() {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { log.w(it) { "unregisterReceiver failed (already unregistered?)" } }
        }
    }
