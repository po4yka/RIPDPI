package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.MigrationMetadata
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxBundleErrorCode
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.subscription.toSelectorFailover

internal fun bundleRejection(
    result: SingBoxParseResult,
    expired: Boolean,
): RefreshOutcome.Failure? =
    when {
        result is SingBoxParseResult.Error && result.recognizedBundle -> {
            rejected(
                ImportRejection
                    .SchemaVersion(result.foundSchemaVersion)
                    .takeIf { result.code == SingBoxBundleErrorCode.UNSUPPORTED_SCHEMA_VERSION },
            )
        }

        expired -> {
            RefreshOutcome.Failure(SubscriptionRefreshFailure.EXPIRED, SubscriptionLifecycleState.EXPIRED, false)
        }

        else -> {
            null
        }
    }

/**
 * A recognised bundle must not fall through to AWG-only success after its relay topology or payload
 * policy fails, or after every relay entry is skipped: that would erase the last working members.
 * A refused AmneziaWG entry or selector default is never replaced by what is left of the bundle.
 */
internal fun bundleRefusal(
    selector: SelectorUrltestImportResult,
    members: List<ProxyProfile>,
    unusable: List<SingBoxSkippedNode>,
    skipped: List<SingBoxSkippedNode>,
    singBox: SingBoxParseResult.Success?,
): RefreshOutcome.Failure? {
    val selected = selector as? SelectorUrltestImportResult.Success
    val failedNode = (selector as? SelectorUrltestImportResult.Error)?.node
    val noUsableMember = members.isEmpty() && (singBox?.profiles?.isNotEmpty() == true || skipped.isNotEmpty())
    val refusedWhole =
        skipped.firstOrNull { it.amneziaWg }
            ?: unusable.firstOrNull { selected?.group != null && it.label == selected.defaultMemberTag }
    val refuses = selector is SelectorUrltestImportResult.Error || noUsableMember || refusedWhole != null
    return if (singBox != null && refuses) {
        rejected((failedNode ?: refusedWhole ?: skipped.firstOrNull())?.let(ImportRejection::Entry))
    } else {
        null
    }
}

internal fun selectorOutcome(
    selected: SelectorUrltestImportResult.Success,
    members: List<ProxyProfile>,
    skipped: List<SingBoxSkippedNode>,
    singBox: SingBoxParseResult.Success?,
): RefreshOutcome.Updated =
    RefreshOutcome.Updated(
        members,
        selected.failoverPolicy.toSelectorFailover(),
        cloudflareMemberIds = selected.cloudflareMemberIds?.intersect(members.mapTo(mutableSetOf()) { it.id }),
        isSelector = selected.group != null,
        // A skipped member cannot stay the initial selection or keep its health entry.
        initialMemberId = selected.group?.initialMemberId?.takeIf { id -> members.any { it.id == id } },
        memberHealth = selected.memberHealth.filterKeys { tag -> members.any { it.displayName == tag } },
        fieldReport = selected.fieldReport,
        tokenExpiresAtEpochMillis = singBox?.tokenExpiresAtEpochMillis,
        packageRoutingRules = singBox?.packageRoutingRules.orEmpty(),
        skipped = skipped,
    )

internal fun simpleSubscriptionOutcome(
    body: String,
    groupId: String,
): RefreshOutcome {
    val simple = parseSimpleSubscription(body, groupId)
    return when {
        simple == null -> rejected(null)
        simple.first.isEmpty() -> rejected(simple.second.firstOrNull()?.let(ImportRejection::Entry))
        else -> RefreshOutcome.Updated(simple.first, null, skipped = simple.second)
    }
}

internal fun rejected(rejection: ImportRejection?): RefreshOutcome.Failure =
    RefreshOutcome.Failure(SubscriptionRefreshFailure.PARSE_ERROR, null, false, rejection)

internal fun payloadTooLargeFailure(): RefreshOutcome.Failure =
    RefreshOutcome.Failure(
        failure = SubscriptionRefreshFailure.PAYLOAD_TOO_LARGE,
        lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
        retry = false,
    )

internal sealed interface RefreshOutcome {
    data class Updated(
        val profiles: List<ProxyProfile>,
        val failover: SelectorFailover?,
        val awgProfiles: List<AwgSubscriptionProfile> = emptyList(),
        val subscriptionUserinfo: String = "",
        val profileTitle: String = "",
        val tokenExpiresAtEpochMillis: Long? = null,
        val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
        val clearRelayMembers: Boolean = false,
        val subscriptionMirrors: SubscriptionMirrorSet? = null,
        val dnsMorph: com.poyka.ripdpi.data.subscription.DnsMorphBridge? = null,
        val cloudflareMemberIds: Set<String>? = null,
        val isSelector: Boolean = false,
        val initialMemberId: String? = null,
        val memberHealth: Map<String, EndpointHealth> = emptyMap(),
        val fieldReport: RipdpiFieldReport? = null,
        val migration: MigrationMetadata? = null,
        val payloadSha256: String = "",
        val skipped: List<SingBoxSkippedNode> = emptyList(),
        /** Set only by a local import; `null` keeps the stored tags. */
        val localBundleTags: Set<String>? = null,
    ) : RefreshOutcome

    data class Failure(
        val failure: SubscriptionRefreshFailure,
        val lifecycleState: SubscriptionLifecycleState?,
        val retry: Boolean,
        val rejection: ImportRejection? = null,
    ) : RefreshOutcome
}
