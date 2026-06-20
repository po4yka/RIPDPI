package com.poyka.ripdpi.seed

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.subscription.AmneziaWgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal const val SEED_PREFS_NAME = "simple_flavor_seed_state"
internal const val SEED_KEY_SEEDED = "config_seeded"
private const val ASSET_NAME = "embedded-relay-bundle.json"

/**
 * Stable group id for the seeded config. Deterministic (not a random UUID) so that if an
 * LMK kill lands between [ProxyGroupRepository.add] and the seeded-flag flush, the next
 * launch's re-seed upserts the SAME group (add() dedupes by id) instead of orphaning an
 * empty duplicate "Simple Config" group.
 */
internal const val SIMPLE_SEED_GROUP_ID = "00000000-0000-4000-8000-simpleflavor1"

/**
 * Prefix for the per-relay store id minted when seeding. Each seeded relay kind gets a
 * distinct stable id ("simple-seed-<Kind>") so several relay profiles coexist in
 * [RelayProfileStore] — the default single "default" slot would make each relay overwrite
 * the previous one, leaving only the last and silently dropping the others from the
 * failover candidate set.
 */
internal const val SEED_RELAY_PROFILE_ID_PREFIX = "simple-seed-"

/**
 * First-launch seeder for the `simple` product flavor.
 *
 * Reads a compiled-in sing-box bundle from [ASSET_NAME] in assets, parses it
 * with [SingBoxSubscriptionParser], and persists each profile via the shared
 * reuse points ([ProxyGroupRepository], [RelayProfileActivator],
 * [AwgProfileRepository]). A boolean flag in [SEED_PREFS_NAME] guards against
 * re-seeding on subsequent launches.
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
    ) : SimpleFlavorSeeder {
        private val prefs by lazy {
            context.getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
        }

        override suspend fun seed() {
            if (prefs.getBoolean(SEED_KEY_SEEDED, false)) {
                Logger.i { "ConfigSeeder: already seeded, skipping" }
                return
            }

            val json = readBundle() ?: return

            val groupId = SIMPLE_SEED_GROUP_ID
            val result = SingBoxSubscriptionParser.parse(json, groupId)

            when (result) {
                is SingBoxParseResult.Error -> {
                    Logger.w { "ConfigSeeder: parse error — ${result.message}" }
                    return
                }

                is SingBoxParseResult.Success -> {
                    proxyGroupRepository.add(
                        ProxyGroup(
                            id = groupId,
                            name = "Simple Config",
                            type = ProxyGroupType.BASIC,
                            order = proxyGroupRepository.list().size,
                            isSelector = false,
                            subscription = null,
                        ),
                    )

                    // Activate each relay under a DISTINCT, stable per-kind id so every
                    // relay in the bundle survives in RelayProfileStore (a shared id would
                    // overwrite — only the last would remain, dropping the rest from the
                    // failover candidate set). VLESS+REALITY is activated LAST so it lands
                    // as the initial active transport (failover priority 0).
                    val orderedProfiles =
                        result.profiles.sortedBy { if (it is ProxyProfile.VlessReality) 1 else 0 }
                    var activatedCount = 0
                    var skippedCount = 0
                    for (profile in orderedProfiles) {
                        val applied = relayProfileActivator.activate(profile, seedRelayProfileId(profile))
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

                    for (awgProfile in result.amneziaWgProfiles) {
                        val request = awgProfile.toActivationRequest()
                        awgProfileRepository.save(awgProfile.displayName, request)
                    }
                    Logger.i {
                        "ConfigSeeder: saved ${result.amneziaWgProfiles.size} AWG profile(s)"
                    }

                    prefs.edit().putBoolean(SEED_KEY_SEEDED, true).apply()
                    Logger.i { "ConfigSeeder: seed complete" }
                }
            }
        }

        /**
         * Stable, collision-free store id for a seeded relay, keyed by its concrete
         * [ProxyProfile] kind so distinct relay kinds never share the default slot.
         */
        private fun seedRelayProfileId(profile: ProxyProfile): String =
            "$SEED_RELAY_PROFILE_ID_PREFIX${profile::class.simpleName}"

        /**
         * Reads the embedded bundle JSON. Returns `null` when the asset is
         * absent or blank — no flag is set so a later drop-in triggers a seed.
         * Protected open so unit tests can inject an in-memory string.
         */
        protected open fun readBundle(): String? =
            try {
                context.assets.open(ASSET_NAME).use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText().takeIf { it.isNotBlank() }
                        ?: run {
                            Logger.i { "ConfigSeeder: $ASSET_NAME is blank; skipping seed" }
                            null
                        }
                }
            } catch (_: IOException) {
                Logger.i { "ConfigSeeder: no $ASSET_NAME asset found; skipping seed" }
                null
            }
    }

/**
 * Maps an [AmneziaWgSubscriptionProfile] from the RIPDPI sing-box bundle
 * extension to an [AwgActivationRequest] ready for [AwgProfileRepository.save].
 *
 * Profile id is left blank — [AwgProfileRepository.save] mints the stable
 * `"awg-<UUID>"` id. Nullable AWG parameter fields default to 0/empty per
 * [AwgActivationObfuscation] defaults, matching the existing
 * [AwgProfileForm.toActivationRequest] mapper.
 */
internal fun AmneziaWgSubscriptionProfile.toActivationRequest(): AwgActivationRequest =
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
