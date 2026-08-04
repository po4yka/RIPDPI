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
private const val CURRENT_SEED_VERSION = 4

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
    val base = "$SEED_RELAY_PROFILE_ID_PREFIX${profile::class.simpleName}"
    return if (kindOccurrence == 0) base else "$base-${kindOccurrence + 1}"
}

/**
 * First-launch seeder for the `simple` product flavor.
 *
 * Reads a compiled-in sing-box bundle from [SIMPLE_RELAY_BUNDLE_ASSET_NAME] in assets, parses it
 * with [SingBoxSubscriptionParser], and persists each profile via the shared
 * reuse points ([ProxyGroupRepository], [RelayProfileActivator],
 * [AwgProfileRepository]). A version in [SEED_PREFS_NAME] guards the diagnostic
 * profile import, while every launch re-pins the first embedded VLESS+Reality
 * profile as the normal VPN runtime.
 *
 * Missing asset — the flag is NOT set so dropping the file in later still
 * triggers a seed. Parse error — same: flag not set so a corrected bundle
 * seeds on the next launch.
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
            val json = readBundle() ?: return

            val groupId = SIMPLE_SEED_GROUP_ID
            val result = SingBoxSubscriptionParser.parse(json, groupId)

            when (result) {
                is SingBoxParseResult.Error -> {
                    Logger.w { "ConfigSeeder: parse error — ${result.message}" }
                    return
                }

                is SingBoxParseResult.Success -> {
                    val primaryReality =
                        result.profiles.filterIsInstance<ProxyProfile.VlessReality>().firstOrNull()
                            ?: run {
                                Logger.w { "ConfigSeeder: embedded bundle has no VLESS+Reality primary; refusing seed" }
                                return
                            }
                    if (!validateNativeRelayProfile(primaryReality)) {
                        Logger.w { "ConfigSeeder: embedded VLESS+Reality primary is invalid; refusing seed" }
                        return
                    }

                    if (prefs.getInt(SEED_KEY_VERSION, 0) >= CURRENT_SEED_VERSION) {
                        pinPrimaryRuntime(primaryReality)
                        Logger.i { "ConfigSeeder: restored embedded VLESS+Reality primary" }
                        return
                    }

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
                                val kind = requireNotNull(profile::class.simpleName)
                                val occurrence = occurrencesByKind.getOrDefault(kind, 0)
                                occurrencesByKind[kind] = occurrence + 1
                                profile to seedRelayProfileId(profile, occurrence)
                            }.asReversed()
                    var activatedCount = 0
                    var skippedCount = 0
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
                        if (applied) {
                            activatedCount++
                        } else {
                            skippedCount++
                            Logger.i {
                                "ConfigSeeder: profile kind ${profile::class.simpleName} not relay-activatable, skipped"
                            }
                        }
                    }
                    Logger.i {
                        "ConfigSeeder: activated $activatedCount relay profile(s), skipped $skippedCount"
                    }

                    result.amneziaWgProfiles.forEachIndexed { index, awgProfile ->
                        val profileId = seedAwgProfileId(index)
                        val request = awgProfile.toActivationRequest()
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

                    prefs
                        .edit()
                        .putBoolean(SEED_KEY_SEEDED, true)
                        .putInt(SEED_KEY_VERSION, CURRENT_SEED_VERSION)
                        .apply()
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
         * absent or blank — no flag is set so a later drop-in triggers a seed.
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
