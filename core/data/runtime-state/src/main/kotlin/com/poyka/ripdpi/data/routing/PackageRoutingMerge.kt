package com.poyka.ripdpi.data.routing

/**
 * A conflict surfaced when a subscription-imported rule disagrees with a
 * user-authored rule for the same package. The user rule is never overwritten;
 * the per-app routing screen opens a confirm dialog
 * ("keep mine" / "use subscription" / "merge per-rule") to resolve it.
 */
data class PackageRoutingConflict(
    val packageName: String,
    val userAction: PackageRoutingAction,
    val subscriptionAction: PackageRoutingAction,
)

/**
 * Result of a [PackageRoutingMerge.merge] pass: the new [store] with the
 * subscription's non-conflicting rules applied, plus the list of [conflicts]
 * that need user resolution. Conflicting subscription rules are intentionally
 * **not** applied to [store] — they linger only in [conflicts] until the user
 * decides.
 */
data class PackageRoutingMergeResult(
    val store: PackageRoutingStore,
    val conflicts: List<PackageRoutingConflict>,
) {
    /**
     * A redacted, log-safe one-line summary: counts only, never package names.
     * Shape: `per-app routing: N from sub, M user-set, K conflicts`.
     */
    fun toRedactedDiagnostics(): String {
        val fromSub = store.rules.count { it.origin is PackageRoutingRuleOrigin.Subscription }
        val userSet = store.rules.count { it.origin == PackageRoutingRuleOrigin.User }
        return "per-app routing: $fromSub from sub, $userSet user-set, ${conflicts.size} conflicts"
    }
}

/**
 * Merges subscription-imported per-app routing rules into a
 * [PackageRoutingStore] under a subscription-ID-tagged namespace.
 *
 * Merge semantics:
 * - The subscription's previously-tagged rules are dropped wholesale, then the
 *   freshly imported set is applied — refresh replaces the tagged set
 *   atomically, so a rule removed upstream does not linger.
 * - User-authored rules are never touched. A subscription rule whose package
 *   already has a user-authored rule with a different action becomes a
 *   [PackageRoutingConflict] and is **not** applied; the user rule stands until
 *   the conflict is resolved.
 * - A subscription rule whose package has a user rule with the *same* action is
 *   a no-op (no conflict, nothing to apply — the user already wants that).
 *
 * The conflict-resolution UI ("keep mine" / "use subscription" / "merge
 * per-rule") lives in `app/`; this layer only produces the conflict records.
 */
object PackageRoutingMerge {
    /**
     * Imports [importedRules] (each expected to carry a
     * [PackageRoutingRuleOrigin.Subscription] origin matching [subscriptionId])
     * into [store], replacing [subscriptionId]'s previous tagged set.
     */
    fun merge(
        store: PackageRoutingStore,
        subscriptionId: String,
        importedRules: List<PackageRoutingRule>,
    ): PackageRoutingMergeResult {
        // Drop this subscription's prior tagged set so refresh is atomic.
        val withoutSubscription = store.removeSubscriptionRules(subscriptionId)
        val userByPackage =
            withoutSubscription
                .userRules()
                .associateBy { it.packageName }

        val conflicts = mutableListOf<PackageRoutingConflict>()
        val applicable = mutableListOf<PackageRoutingRule>()
        for (rule in importedRules) {
            val normalized =
                rule.copy(origin = PackageRoutingRuleOrigin.Subscription(subscriptionId))
            val userRule = userByPackage[normalized.packageName]
            when {
                userRule == null -> {
                    applicable += normalized
                }

                userRule.action == normalized.action -> {
                    // The user already wants this exact action; nothing to apply, no conflict.
                    Unit
                }

                else -> {
                    conflicts +=
                        PackageRoutingConflict(
                            packageName = normalized.packageName,
                            userAction = userRule.action,
                            subscriptionAction = normalized.action,
                        )
                }
            }
        }
        return PackageRoutingMergeResult(
            store = PackageRoutingStore(withoutSubscription.rules + applicable),
            conflicts = conflicts,
        )
    }
}
