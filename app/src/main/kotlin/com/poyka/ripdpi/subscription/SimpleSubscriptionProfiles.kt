package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import com.poyka.ripdpi.data.subscription.ClashSubscriptionParser
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode
import com.poyka.ripdpi.data.subscription.splitNativeProfiles

private val UriSchemePattern = Regex("[a-z][a-z0-9+.-]{1,15}")

/**
 * Parses a Clash or link-list payload into the profiles this app can run and the entries it skipped.
 * An entry is named by its position or line number, because a display name can be an address.
 * Returns `null` when nothing parses: the payload is not a subscription, so naming "line 1" of a
 * web page would mislead.
 */
internal fun parseSimpleSubscription(
    body: String,
    groupId: String,
): Pair<List<ProxyProfile>, List<SingBoxSkippedNode>>? {
    val (parsed, unparsed) =
        if (ClashSubscriptionParser.looksLikeClash(body)) parseClash(body, groupId) else parseLinkList(body, groupId)
    return parsed.takeIf { it.isNotEmpty() }?.let { profiles ->
        val (usable, unusable) = splitNativeProfiles(profiles)
        usable to unparsed + unusable
    }
}

private fun parseClash(
    body: String,
    groupId: String,
): Pair<List<ProxyProfile>, List<SingBoxSkippedNode>> {
    val clash = ClashSubscriptionParser.parse(body, groupId)
    // A negative node index reports the whole document, not one node: nothing is importable.
    if (clash.errors.any { it.nodeIndex < 0 }) return emptyList<ProxyProfile>() to emptyList()
    return clash.profiles to
        clash.errors.map {
            SingBoxSkippedNode(it.nodeIndex, "#${it.nodeIndex + 1}", SingBoxSkipReason.INVALID_SETTINGS)
        }
}

private fun parseLinkList(
    body: String,
    groupId: String,
): Pair<List<ProxyProfile>, List<SingBoxSkippedNode>> {
    val base64 = Base64SubscriptionParser.parse(body, groupId)
    return base64.profiles to
        base64.warnings.map {
            SingBoxSkippedNode(
                it.lineNumber,
                "#${it.lineNumber}",
                SingBoxSkipReason.INVALID_SETTINGS,
                // Only the URI scheme is kept; the rest of the line can hold credentials.
                it.line.substringBefore("://", "").takeIf(UriSchemePattern::matches),
            )
        }
}
