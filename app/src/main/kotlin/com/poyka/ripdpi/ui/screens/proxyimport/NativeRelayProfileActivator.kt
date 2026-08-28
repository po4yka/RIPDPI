package com.poyka.ripdpi.ui.screens.proxyimport

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.validateNativeRelayProfile
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import java.util.UUID
import javax.inject.Inject

/**
 * Records a single-profile [ProxyGroupType.BASIC] group for a parsed
 * [ProxyProfile] and activates it as the live native relay.
 *
 * Group creation lives here; the profile→relay mapping itself is delegated to
 * [RelayProfileActivator], which is the single place that writes the
 * `RelayProfileRecord` / `RelayCredentialRecord` / `AppSettings` triple. The
 * share-link import-confirmation screen, the Xray config import flow, and the
 * dedicated profile editors therefore all share one mapping and cannot drift.
 *
 * [supports] pre-filters the kinds that map to a native relay backend; any other
 * profile still gets a group marker but does not enable the relay (so it never
 * yields a connection that fails at connect time).
 */
class NativeRelayProfileActivator
    @Inject
    constructor(
        private val repository: ProxyGroupRepository,
        private val relayProfileActivator: RelayProfileActivator,
    ) {
        /** True when [profile] maps to a native relay backend reachable from import. */
        fun supports(profile: ProxyProfile): Boolean =
            when (profile) {
                is ProxyProfile.Trojan,
                is ProxyProfile.Shadowsocks,
                is ProxyProfile.AnyTls,
                is ProxyProfile.VlessReality,
                is ProxyProfile.Ssh,
                is ProxyProfile.Mieru,
                -> true

                is ProxyProfile.Vless -> profile.xhttpPath != null || profile.xhttpHost != null

                else -> false
            }

        /**
         * Adds a single-profile [ProxyGroupType.BASIC] group for [profile] and, when it
         * is a relay-activatable kind, activates it as the live native relay. Suspends
         * until the group, stores, and settings have been written.
         */
        suspend fun activate(profile: ProxyProfile) {
            validateNativeRelayProfile(profile)
            val groupId = UUID.randomUUID().toString()
            repository.add(
                ProxyGroup(
                    id = groupId,
                    name = profile.displayName,
                    type = ProxyGroupType.BASIC,
                    order = repository.list().size,
                    isSelector = false,
                    subscription = null,
                ),
            )
            relayProfileActivator.activate(profile)
        }
    }
