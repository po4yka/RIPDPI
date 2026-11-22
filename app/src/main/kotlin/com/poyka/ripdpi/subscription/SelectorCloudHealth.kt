package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.EndpointHealthOrder
import com.poyka.ripdpi.data.subscription.orderByEndpointHealth

internal data class SelectorReserves(
    val ordinary: List<ProxyProfile>,
    val down: List<ProxyProfile>,
) {
    fun ordinaryFailed(failedIds: Set<String>): Boolean = ordinary.all { it.id in failedIds }
}

/** The member after [failedId], so successive sweeps give the down slot to each down member in turn. */
internal fun List<ProxyProfile>.after(failedId: String?): ProxyProfile? =
    getOrNull(indexOfFirst { it.id == failedId } + 1) ?: firstOrNull()

internal fun SelectorProbeScope.automaticActive(profileId: String?): ProxyProfile? =
    members.firstOrNull { it.id == profileId && it.id !in cloudflareMemberIds }

internal fun SelectorProbeScope.reserves(
    activeId: String?,
    now: Long,
): SelectorReserves {
    val eligible = members.filterNot { it.id == activeId || it.id in cloudflareMemberIds }
    val ordinary =
        (
            orderByEndpointHealth(
                eligible,
                memberHealth,
                now,
            ) as? EndpointHealthOrder.Ranked
        )?.members.orEmpty()
    val ordinaryIds = ordinary.mapTo(hashSetOf()) { it.id }
    return SelectorReserves(ordinary, eligible.filterNot { it.id in ordinaryIds })
}
