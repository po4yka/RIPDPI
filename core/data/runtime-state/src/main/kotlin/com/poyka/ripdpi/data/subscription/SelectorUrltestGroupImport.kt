package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Failover policy attached to a selector [ProxyGroup] imported from a sing-box
 * bundle.
 */
sealed interface FailoverPolicy {
    /** No `urltest` was present: the group switches manually only. */
    data object Manual : FailoverPolicy

    /**
     * Latency-driven failover ported from a sing-box `urltest` outbound.
     *
     * @param probeUrl the URL probed for reachability / latency.
     * @param intervalSeconds probe cadence, in seconds.
     * @param toleranceMs latency tolerance band, in milliseconds.
     */
    data class Urltest(
        val probeUrl: String,
        val intervalSeconds: Int,
        val toleranceMs: Int,
    ) : FailoverPolicy
}

/**
 * Maps an import-layer [FailoverPolicy] onto the persisted [SelectorFailover]
 * mirror stored on a [ProxyGroup]. [FailoverPolicy.Manual] has no probe policy and
 * maps to `null`.
 */
fun FailoverPolicy.toSelectorFailover(): SelectorFailover? =
    when (this) {
        FailoverPolicy.Manual -> {
            null
        }

        is FailoverPolicy.Urltest -> {
            SelectorFailover(
                probeUrl = probeUrl,
                intervalSeconds = intervalSeconds,
                toleranceMs = toleranceMs,
            )
        }
    }

/** Outcome of a [SelectorUrltestGroupImport] run. */
sealed interface SelectorUrltestImportResult {
    /**
     * Import succeeded.
     *
     * @param profiles the concrete outbound profiles (selector/urltest excluded).
     * @param group the selector group, or `null` when the bundle had no selector.
     * @param memberOrder the selector member tags in declared order, urltest tag removed.
     * @param defaultMemberTag the selector `default`, or `null`.
     * @param failoverPolicy [FailoverPolicy.Urltest] when a urltest was present, else [FailoverPolicy.Manual].
     */
    data class Success(
        val profiles: List<ProxyProfile>,
        val group: ProxyGroup?,
        val memberOrder: List<String>,
        val defaultMemberTag: String?,
        val failoverPolicy: FailoverPolicy,
        val skipped: List<SingBoxSkippedNode> = emptyList(),
        val cloudflareMemberIds: Set<String>? = null,
    ) : SelectorUrltestImportResult

    /** Import was rejected as a unit (e.g. a selector named a missing tag, or the JSON was malformed). */
    data class Error(
        val message: String,
        val reason: SingBoxSkipReason? = null,
        val detail: String? = null,
    ) : SelectorUrltestImportResult
}

/**
 * Promotes the `selector` + `urltest` outbound pair the ripdpi-vpn-deploy
 * deployer emits into a RIPDPI selector [ProxyGroup] with a failover policy.
 *
 * Concrete outbounds are mapped by [SingBoxSubscriptionParser]. A `selector`
 * entry becomes a [ProxyGroup] with `isSelector=true`, member order taken from
 * its `outbounds[]` (minus the urltest tag), and `default` carried through as
 * [SelectorUrltestImportResult.defaultMemberTag]. A `urltest` entry becomes the
 * group's [FailoverPolicy.Urltest]. Tag → profile resolution is order
 * independent (a forward-referencing selector resolves); a selector naming a
 * tag absent from the bundle rejects the whole import. A single concrete
 * outbound with no selector produces one profile and no group; an orphaned
 * urltest with no selector is skipped.
 */
object SelectorUrltestGroupImport {
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 60 * 60

    /**
     * Imports [payload] into a [SelectorUrltestImportResult]. Concrete profiles
     * and the generated group are stamped with [groupId].
     */
    @Suppress("ReturnCount")
    fun import(
        payload: String,
        groupId: String,
    ): SelectorUrltestImportResult {
        val outbounds =
            when (val extracted = SingBoxSubscriptionParser.extractOutbounds(payload)) {
                is SingBoxSubscriptionParser.OutboundExtraction.Failure -> {
                    return SelectorUrltestImportResult.Error(extracted.message)
                }

                is SingBoxSubscriptionParser.OutboundExtraction.Outbounds -> {
                    extracted.entries
                }
            }

        // First pass: collect the concrete outbound profiles by tag, and the
        // selector / urltest metadata entries.
        val parsed = SingBoxSubscriptionParser.parse(payload, groupId)
        val profiles =
            when (parsed) {
                is SingBoxParseResult.Error -> return SelectorUrltestImportResult.Error(parsed.message)
                is SingBoxParseResult.Success -> parsed.profiles
            }
        val profileTags = profiles.mapTo(mutableSetOf()) { it.displayName }

        var selector: JsonObject? = null
        var urltest: JsonObject? = null
        outbounds.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            when (obj.string("type")?.lowercase()) {
                "selector" -> selector = obj
                "urltest" -> urltest = obj
            }
        }

        val selectorEntry = selector
        if (selectorEntry == null) {
            // No selector: a urltest by itself is an orphan and is skipped.
            return SelectorUrltestImportResult.Success(
                profiles = profiles,
                group = null,
                memberOrder = emptyList(),
                defaultMemberTag = null,
                failoverPolicy = FailoverPolicy.Manual,
                skipped = parsed.skipped,
                cloudflareMemberIds = parsed.cloudflareMemberIds,
            )
        }

        val urltestTag = urltest?.string("tag")
        val rawMembers =
            (selectorEntry["outbounds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        // Second pass: resolve every selector member tag against the concrete
        // profile tags. The urltest tag is group metadata, not a member.
        val memberOrder = rawMembers.filter { it != urltestTag }
        val missing = memberOrder.filterNot { it in profileTags }
        missingSelectorMemberError(missing, parsed.skipped)?.let { return it }

        val failoverPolicy = failoverPolicy(urltest)

        // Resolve the member tags back to their concrete profiles, in declared
        // selector order, so the group durably carries its candidate set. A
        // non-activatable node (RawConfig: tuic/vmess/wireguard or a
        // blank-credential node) must not become a selectable member that can
        // never connect (audit P1-3).
        val membersByTag = profiles.associateBy { it.displayName }
        val memberProfiles =
            memberOrder
                .mapNotNull(membersByTag::get)
                .filterNot { it is ProxyProfile.RawConfig }

        return SelectorUrltestImportResult.Success(
            profiles = profiles,
            group =
                selectorGroup(groupId, selectorEntry, memberProfiles, failoverPolicy, parsed.packageRoutingRules)
                    .copy(
                        cloudflareMemberIds =
                            parsed.cloudflareMemberIds.orEmpty().intersect(
                                memberProfiles.map { it.id }.toSet(),
                            ),
                    ),
            memberOrder = memberOrder,
            defaultMemberTag = selectorEntry.string("default"),
            failoverPolicy = failoverPolicy,
            skipped = parsed.skipped,
            cloudflareMemberIds = parsed.cloudflareMemberIds,
        )
    }

    private fun missingSelectorMemberError(
        missing: List<String>,
        skipped: List<SingBoxSkippedNode>,
    ): SelectorUrltestImportResult.Error? {
        if (missing.isEmpty()) return null
        val rejectedMember = skipped.firstOrNull { it.label in missing }
        return if (rejectedMember != null) {
            SelectorUrltestImportResult.Error(
                message =
                    "selector member ${rejectedMember.label} uses unsupported " +
                        rejectedMember.detail.orEmpty(),
                reason = rejectedMember.reason,
                detail = rejectedMember.detail,
            )
        } else {
            SelectorUrltestImportResult.Error(
                "selector references tag(s) absent from the bundle: ${missing.joinToString(", ")}",
            )
        }
    }

    private fun failoverPolicy(urltest: JsonObject?): FailoverPolicy =
        urltest?.let { entry ->
            FailoverPolicy.Urltest(
                probeUrl = entry.string("url").orEmpty(),
                intervalSeconds = parseInterval(entry.string("interval")),
                toleranceMs = entry.int("tolerance") ?: 0,
            )
        } ?: FailoverPolicy.Manual

    private fun selectorGroup(
        groupId: String,
        selector: JsonObject,
        members: List<ProxyProfile>,
        failoverPolicy: FailoverPolicy,
        packageRoutingRules: List<PackageRoutingRule>,
    ): ProxyGroup =
        ProxyGroup(
            id = groupId,
            name = selector.string("tag") ?: groupId,
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = true,
            members = members,
            failover = failoverPolicy.toSelectorFailover(),
            packageRoutingRules = packageRoutingRules,
        )

    /**
     * Parses a sing-box duration string (`"5m"`, `"30s"`, `"1h"`, or a bare
     * second count) into whole seconds. Unparseable input yields `0`.
     */
    @Suppress("ReturnCount")
    private fun parseInterval(raw: String?): Int {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        value.toIntOrNull()?.let { return it }
        val unit = value.last()
        val magnitude = value.dropLast(1).toIntOrNull() ?: return 0
        return when (unit) {
            's', 'S' -> magnitude
            'm', 'M' -> magnitude * SECONDS_PER_MINUTE
            'h', 'H' -> magnitude * SECONDS_PER_HOUR
            else -> 0
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }
}
