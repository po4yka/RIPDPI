package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.subscription.AmneziaWgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.WireGuardSubscriptionProfile
import com.poyka.ripdpi.data.subscription.toActivationRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An imported member whose source identity remains stable when server parameters change. */
data class AwgSubscriptionProfile(
    val groupId: String,
    val memberId: String,
    val name: String,
    val request: AwgActivationRequest,
    /** Only parsed source fields can authorize replacement of saved network policy. */
    val dnsSpecified: Boolean = false,
    val allowedIpsSpecified: Boolean = false,
    val previousMemberId: String? = null,
) {
    // Structured fields avoid delimiter collisions. Neither field is a subscription URL or token.
    internal val origin: JsonObject
        get() = JsonObject(mapOf("groupId" to JsonPrimitive(groupId), "memberId" to JsonPrimitive(memberId)))
}

/** Sing-box tags identify members; INI callers supply their peer identity instead. */
fun AmneziaWgSubscriptionProfile.toAwgSubscriptionProfile(
    memberId: String = "tag:$displayName",
    previousTag: String? = null,
): AwgSubscriptionProfile =
    AwgSubscriptionProfile(
        groupId,
        memberId,
        displayName,
        toActivationRequest(),
        dnsSpecified,
        allowedIpsSpecified,
        previousTag
            ?.let {
                "tag:$it"
            },
    )

fun WireGuardSubscriptionProfile.toAwgSubscriptionProfile(): AwgSubscriptionProfile =
    AwgSubscriptionProfile(
        groupId,
        "peer:$peerPublicKey",
        displayName,
        toActivationRequest(),
        dnsSpecified,
        allowedIpsSpecified,
    )

/** Pure validation shared by parsing and the guarded persistence boundary. */
fun List<AwgSubscriptionProfile>.requireValidSubscriptionProfiles() {
    require(all { it.groupId.isNotBlank() && it.memberId.isNotBlank() }) { "AWG subscription identity is missing" }
    require(map { it.origin }.distinct().size == size) { "AWG subscription member identity is ambiguous" }
    forEach { it.request.obfuscation.requireSupportedProfile() }
}
