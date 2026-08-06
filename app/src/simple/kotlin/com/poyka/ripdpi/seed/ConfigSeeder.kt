package com.poyka.ripdpi.seed

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.toActivationRequest
import com.poyka.ripdpi.data.validateNativeRelayProfile
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal const val SEED_PREFS_NAME = "simple_flavor_seed_state"
internal const val SEED_KEY_SEEDED = "config_seeded"
internal const val SEED_KEY_VERSION = "config_seed_version"
internal const val SIMPLE_RELAY_BUNDLE_ASSET_NAME = "embedded-relay-bundle.json"
private const val CURRENT_SEED_VERSION = 6

/**
 * Stable group id for the seeded config. Deterministic (not a random UUID) so that if an
 * LMK kill lands between [ProxyGroupRepository.add] and the seeded-flag flush, the next
 * launch's re-seed upserts the SAME group (add() dedupes by id) instead of orphaning an
 * empty duplicate "Simple Config" group.
 */
internal const val SIMPLE_SEED_GROUP_ID = "00000000-0000-4000-8000-simpleflavor1"

/**
 * Prefix for the per-relay store id minted when seeding. The first profile of a kind keeps
 * the legacy stable id ("simple-seed-<Kind>"); additional profiles of the same kind receive
 * a 1-based occurrence suffix. This preserves upgrade compatibility while preventing two
 * REALITY endpoints (for example 443 plus 2053) from overwriting each other.
 */
internal const val SEED_RELAY_PROFILE_ID_PREFIX = "simple-seed-"

/** Stable id of the bundled AWG profile used by automatic simple-flavor failover. */
internal const val SIMPLE_SEED_AWG_PROFILE_ID = "simple-seed-awg"

internal fun seedAwgProfileId(index: Int): String {
    require(index >= 0)
    return if (index == 0) SIMPLE_SEED_AWG_PROFILE_ID else "$SIMPLE_SEED_AWG_PROFILE_ID-${index + 1}"
}

internal fun seedRelayProfileId(
    profile: ProxyProfile,
    kindOccurrence: Int = 0,
): String {
    require(kindOccurrence >= 0)
    val base = "$SEED_RELAY_PROFILE_ID_PREFIX${seedRelayProfileStableName(profile)}"
    return if (kindOccurrence == 0) base else "$base-${kindOccurrence + 1}"
}

/**
 * Stable persisted discriminator for bundled relay profiles.
 *
 * These values intentionally match the legacy source-level class names, but do not derive from
 * runtime class metadata: R8 is free to obfuscate [ProxyProfile] implementations in release APKs.
 */
internal fun seedRelayProfileStableName(profile: ProxyProfile): String =
    when (profile) {
        is ProxyProfile.Vless -> "Vless"
        is ProxyProfile.VlessReality -> "VlessReality"
        is ProxyProfile.Shadowsocks -> "Shadowsocks"
        is ProxyProfile.Trojan -> "Trojan"
        is ProxyProfile.Hysteria2 -> "Hysteria2"
        is ProxyProfile.AnyTls -> "AnyTls"
        is ProxyProfile.Mieru -> "Mieru"
        is ProxyProfile.Ssh -> "Ssh"
        is ProxyProfile.RawConfig -> "RawConfig"
    }

/**
 * Required configuration reconciler for the `simple` product flavor.
 *
 * Reads a compiled-in sing-box bundle from [SIMPLE_RELAY_BUNDLE_ASSET_NAME] in assets, parses it
 * with [SingBoxSubscriptionParser], and persists each profile via the shared
 * reuse points ([ProxyGroupRepository], [RelayProfileActivator],
 * [AwgProfileRepository]). Every launch reconciles the bundled profiles by their
 * stable ids and re-pins the first embedded VLESS+Reality profile as the normal
 * VPN runtime. The version in [SEED_PREFS_NAME] records the last completed seed.
 *
 * A missing or invalid required profile fails startup before any repository is
 * mutated. Deterministic ids make a retry safe if a storage failure interrupts
 * the reconciliation after writes have started.
 */
@Singleton
open class ConfigSeeder
    @Inject
    constructor(
        @ApplicationContext protected val context: Context,
        private val proxyGroupRepository: ProxyGroupRepository,
        private val relayProfileActivator: RelayProfileActivator,
        private val awgProfileRepository: AwgProfileRepository,
        private val settingsRepository: AppSettingsRepository,
    ) : SimpleFlavorSeeder {
        private val prefs by lazy {
            context.getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
        }

        override suspend fun seed() {
            val json = checkNotNull(readBundle()) { "Required embedded relay bundle is unavailable" }

            val groupId = SIMPLE_SEED_GROUP_ID
            val result = SingBoxSubscriptionParser.parse(json, groupId)

            when (result) {
                is SingBoxParseResult.Error -> {
                    error("Required embedded relay bundle is invalid")
                }

                is SingBoxParseResult.Success -> {
                    check(result.skipped.isEmpty()) {
                        "Required embedded relay bundle contains unsupported relay profiles"
                    }
                    val primaryReality =
                        result.profiles.filterIsInstance<ProxyProfile.VlessReality>().firstOrNull()
                            ?: error("Required embedded relay bundle has no VLESS+Reality primary")
                    check(
                        result.profiles
                            .filterIsInstance<ProxyProfile.Vless>()
                            .any { profile -> profile.xhttpPath != null },
                    ) {
                        "Required embedded relay bundle has no TCP-diverse VLESS/xHTTP reserve"
                    }
                    check(result.profiles.any { it is ProxyProfile.Hysteria2 }) {
                        "Required embedded relay bundle has no Hysteria2 reserve"
                    }
                    check(result.amneziaWgProfiles.isNotEmpty()) {
                        "Required embedded relay bundle has no AWG reserve"
                    }
                    result.profiles.forEach { profile ->
                        check(validateNativeRelayProfile(profile)) {
                            "Embedded relay profile ${profile::class.simpleName} is invalid"
                        }
                    }
                    val awgRequests = result.amneziaWgProfiles.map { it.toActivationRequest() }
                    awgRequests.forEach { it.requireRuntimeReady() }

                    val existingGroup = proxyGroupRepository.list().firstOrNull { it.id == groupId }
                    proxyGroupRepository.add(
                        (
                            existingGroup
                                ?: ProxyGroup(
                                    id = groupId,
                                    name = "Simple Config",
                                    type = ProxyGroupType.BASIC,
                                    order = proxyGroupRepository.list().size,
                                    isSelector = false,
                                    subscription = null,
                                )
                        ).copy(packageRoutingRules = result.packageRoutingRules),
                    )

                    // Persist every bundled transport so diagnostics can exercise them. Assign
                    // ids in declaration order before reversing activation; the explicit pin
                    // below makes the first VLESS+Reality profile the normal runtime selection.
                    val occurrencesByKind = mutableMapOf<String, Int>()
                    val orderedProfiles =
                        result.profiles
                            .map { profile ->
                                val kind = seedRelayProfileStableName(profile)
                                val occurrence = occurrencesByKind.getOrDefault(kind, 0)
                                occurrencesByKind[kind] = occurrence + 1
                                profile to seedRelayProfileId(profile, occurrence)
                            }.asReversed()
                    for ((profile, profileId) in orderedProfiles) {
                        val applied =
                            relayProfileActivator.activate(
                                profile = profile,
                                profileId = profileId,
                                tlsFingerprintOverride =
                                    TlsFingerprintProfileFirefoxStable.takeIf {
                                        profile is ProxyProfile.Hysteria2
                                    },
                            )
                        check(applied) {
                            "Embedded relay profile ${profile::class.simpleName} is not relay-activatable"
                        }
                    }
                    Logger.i { "ConfigSeeder: activated ${orderedProfiles.size} relay profile(s)" }

                    result.amneziaWgProfiles
                        .zip(awgRequests)
                        .forEachIndexed { index, (awgProfile, request) ->
                            val profileId = seedAwgProfileId(index)
                            awgProfileRepository.save(
                                name = awgProfile.displayName,
                                request = request,
                                existingId = profileId,
                            )
                        }
                    Logger.i {
                        "ConfigSeeder: saved ${result.amneziaWgProfiles.size} AWG profile(s)"
                    }

                    pinPrimaryRuntime(primaryReality)

                    check(
                        prefs
                            .edit()
                            .putBoolean(SEED_KEY_SEEDED, true)
                            .putInt(SEED_KEY_VERSION, CURRENT_SEED_VERSION)
                            .commit(),
                    ) { "Failed to persist simple seed state" }
                    Logger.i { "ConfigSeeder: seed complete" }
                }
            }
        }

        private suspend fun pinPrimaryRuntime(primaryReality: ProxyProfile.VlessReality) {
            check(
                relayProfileActivator.activate(
                    profile = primaryReality,
                    profileId = seedRelayProfileId(primaryReality),
                ),
            ) { "Embedded VLESS+Reality primary is not relay-activatable" }
            settingsRepository.update {
                setRipdpiMode(Mode.VPN.preferenceValue)
                setSimpleFailoverAwgProfileId("")
            }
        }

        /**
         * Reads the embedded bundle JSON. Returns `null` when the asset is
         * absent or blank; [seed] treats that as a required startup failure.
         * Protected open so unit tests can inject an in-memory string.
         */
        protected open fun readBundle(): String? =
            try {
                context.assets.open(SIMPLE_RELAY_BUNDLE_ASSET_NAME).use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText().takeIf { it.isNotBlank() }
                        ?: run {
                            Logger.i { "ConfigSeeder: $SIMPLE_RELAY_BUNDLE_ASSET_NAME is blank; skipping seed" }
                            null
                        }
                }
            } catch (_: IOException) {
                Logger.i { "ConfigSeeder: no $SIMPLE_RELAY_BUNDLE_ASSET_NAME asset found; skipping seed" }
                null
            }
    }
