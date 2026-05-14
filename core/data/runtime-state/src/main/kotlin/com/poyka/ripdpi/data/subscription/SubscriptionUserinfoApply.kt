package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.Subscription

/**
 * Folds a parsed [SubscriptionUserinfo] into a [Subscription] entity.
 *
 * The [Subscription] entity already carries `subscriptionUserinfo`, `bytesUsed`,
 * `bytesRemaining` and `expiryDate` fields; this extension is the bridge that
 * populates them from a parsed `Subscription-Userinfo` response header at
 * refresh time.
 *
 * Robustness: a numeric field the header omitted (parsed as `null`) is written
 * as `0L` — the entity's existing default — rather than left at its previous
 * value, so a refresh always reflects the latest header. The raw header text is
 * preserved in [Subscription.subscriptionUserinfo] for diagnostics.
 *
 * @param rawHeader the raw `Subscription-Userinfo` header value, kept verbatim
 *     on the entity. Defaults to empty when only a pre-parsed [userinfo] is
 *     available.
 */
fun Subscription.withUserinfo(
    userinfo: SubscriptionUserinfo,
    rawHeader: String = "",
): Subscription =
    copy(
        subscriptionUserinfo = rawHeader,
        bytesUsed = userinfo.bytesUsed ?: 0L,
        bytesRemaining = userinfo.bytesRemaining ?: 0L,
        expiryDate = userinfo.expire ?: 0L,
    )

/**
 * Parses [rawHeader] as a `Subscription-Userinfo` value and folds it into this
 * [Subscription] in one step. A blank header leaves the accounting fields at
 * their `0L` defaults. Never throws.
 */
fun Subscription.withUserinfoHeader(rawHeader: String): Subscription =
    withUserinfo(SubscriptionUserinfo.parse(rawHeader), rawHeader = rawHeader)
