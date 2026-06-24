package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest

/**
 * Maps an [AmneziaWgSubscriptionProfile] from a sing-box bundle or WireGuard-INI
 * subscription to an [AwgActivationRequest] ready for `AwgProfileRepository.save`.
 *
 * Profile id is left blank — `AwgProfileRepository.save` mints the stable
 * `"awg-<UUID>"` id. Nullable AWG parameter fields default to 0/empty per
 * [AwgActivationObfuscation] defaults, matching the existing
 * `AwgProfileForm.toActivationRequest` mapper.
 *
 * Lives in `:core:data:runtime-state` (which both the `simple` flavor's
 * `ConfigSeeder` and the `main` source set's `SubscriptionAutoUpdateWorker`
 * depend on) so a subscription-imported AmneziaWG/WireGuard profile is persisted
 * in every flavor through one shared mapper, not a flavor-local copy.
 */
fun AmneziaWgSubscriptionProfile.toActivationRequest(): AwgActivationRequest =
    AwgActivationRequest(
        profileId = "",
        privateKey = interfacePrivateKey,
        peerPublicKey = peerPublicKey,
        presharedKey = peerPresharedKey.orEmpty(),
        endpointHost = server,
        endpointPort = serverPort,
        interfaceAddressV4 = interfaceAddress.firstOrNull().orEmpty(),
        interfaceAddressV6 = interfaceAddress.drop(1).firstOrNull { it.contains(':') }.orEmpty(),
        mtu = mtu ?: AwgActivationRequest.DEFAULT_MTU,
        persistentKeepalive = persistentKeepalive ?: 0,
        obfuscation =
            AwgActivationObfuscation(
                jc = awg.jc ?: 0,
                jmin = awg.jmin ?: 0,
                jmax = awg.jmax ?: 0,
                s1 = awg.s1 ?: 0,
                s2 = awg.s2 ?: 0,
                s3 = awg.s3 ?: 0,
                s4 = awg.s4 ?: 0,
                h1 = awg.h1 ?: 0L,
                h2 = awg.h2 ?: 0L,
                h3 = awg.h3 ?: 0L,
                h4 = awg.h4 ?: 0L,
                i1 = awg.i1.orEmpty(),
                i2 = awg.i2.orEmpty(),
                i3 = awg.i3.orEmpty(),
                i4 = awg.i4.orEmpty(),
                i5 = awg.i5.orEmpty(),
            ),
    )

/**
 * Maps a vanilla [WireGuardSubscriptionProfile] to an [AwgActivationRequest].
 *
 * A plain WireGuard node is an AmneziaWG profile with ZERO obfuscation, so this
 * mirrors [AmneziaWgSubscriptionProfile.toActivationRequest] field-for-field but
 * with a default [AwgActivationObfuscation] (all knobs `0`/empty) — the native
 * AmneziaWG runtime degrades to plain WireGuard-over-UDP when every junk knob is
 * zeroed. Profile id is left blank for `AwgProfileRepository.save` to mint.
 */
fun WireGuardSubscriptionProfile.toActivationRequest(): AwgActivationRequest =
    AwgActivationRequest(
        profileId = "",
        privateKey = interfacePrivateKey,
        peerPublicKey = peerPublicKey,
        presharedKey = peerPresharedKey.orEmpty(),
        endpointHost = server,
        endpointPort = serverPort,
        interfaceAddressV4 = interfaceAddress.firstOrNull().orEmpty(),
        interfaceAddressV6 = interfaceAddress.drop(1).firstOrNull { it.contains(':') }.orEmpty(),
        mtu = mtu ?: AwgActivationRequest.DEFAULT_MTU,
        persistentKeepalive = persistentKeepalive ?: 0,
        obfuscation = AwgActivationObfuscation(),
    )
