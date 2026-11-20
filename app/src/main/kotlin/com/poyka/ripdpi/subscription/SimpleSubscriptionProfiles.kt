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
 * A Clash node is named by its `name`; a share link by its line number, because its display name can
 * be an address. Returns `null` when nothing parses: the payload is not a subscription, so naming
 * "line 1" of a web page would mislead.
 */
internal fun parseSimpleSubscription(
    body: String,
    groupId: String,
): Pair<List<ProxyProfile>, List<SingBoxSkippedNode>>? {
    val parsed: List<ProxyProfile>
    val unparsed: List<SingBoxSkippedNode>
    val names: Set<String>
    if (ClashSubscriptionParser.looksLikeClash(body)) {
        val clash = ClashSubscriptionParser.parse(body, groupId)
        // A negative node index reports the whole document, not one node.
        if (clash.errors.any { it.nodeIndex < 0 }) return null
        parsed = clash.profiles
        names = parsed.mapTo(mutableSetOf()) { it.displayName }
        unparsed =
            clash.errors.map {
                SingBoxSkippedNode(it.nodeIndex, "#${it.nodeIndex + 1}", SingBoxSkipReason.INVALID_SETTINGS)
            }
    } else {
        val base64 = Base64SubscriptionParser.parse(body, groupId)
        parsed = base64.profiles
        names = emptySet()
        unparsed =
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
    if (parsed.isEmpty()) return null
    val (usable, unusable) = splitNativeProfiles(parsed, names)
    return usable to unparsed + unusable
}
