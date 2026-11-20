package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import java.util.UUID

/** A locally imported bundle: no source link and never auto-updated. */
internal fun ProxyGroup.isLocalBundle(): Boolean =
    type == ProxyGroupType.SUBSCRIPTION && subscription?.let { it.link.isBlank() && !it.autoUpdate } == true

/**
 * Every server tag the bundle names, supported or skipped, so the identity does not change with what
 * this app version can run. The deployer names each server after its host and protocol, so the set stays
 * the same when keys, passwords or addresses rotate and differs between deployments.
 */
internal fun localBundleTags(text: String): Set<String> =
    (SingBoxSubscriptionParser.parse(text, "") as? SingBoxParseResult.Success)
        ?.let { parsed ->
            (
                parsed.profiles.map { it.displayName } + parsed.skipped.map { it.label } +
                    parsed.amneziaWgProfiles.map { it.displayName }
            ).toSet()
        }.orEmpty()

/**
 * The group a local import of a bundle with [tags] writes to: the local group with the same tag set, else,
 * for a payload without tags or a group saved before tags were stored, the one with the same [name], else a
 * new group placed after the others.
 */
internal fun List<ProxyGroup>.localBundleTarget(
    name: String,
    tags: Set<String>,
): ProxyGroup {
    val local = filter { it.isLocalBundle() }
    return local.firstOrNull { tags.isNotEmpty() && it.subscription?.localBundleTags == tags }
        ?: local.firstOrNull { it.subscription?.localBundleTags.isNullOrEmpty() && it.name == name }
        ?: ProxyGroup(
            id = UUID.randomUUID().toString(),
            name = name,
            type = ProxyGroupType.SUBSCRIPTION,
            order = size,
            isSelector = false,
            subscription = Subscription(),
        )
}
