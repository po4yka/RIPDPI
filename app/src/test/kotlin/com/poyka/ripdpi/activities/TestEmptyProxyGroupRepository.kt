package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object TestEmptyProxyGroupRepository : ProxyGroupRepository {
    override suspend fun add(group: ProxyGroup) = Unit

    override suspend fun update(group: ProxyGroup) = Unit

    override suspend fun replaceAll(
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
        groups: List<ProxyGroup>,
    ) {
        val preparation =
            com.poyka.ripdpi.data.ProfileMutationPreparation(
                com.poyka.ripdpi.data.ProfileMutationOrigin.Compensation,
                receipt.authority,
            )
        list().forEach { delete(preparation, it.id) }
        groups.forEach { add(it) }
    }

    override suspend fun compensateReplacement(groups: List<ProxyGroup>) {
        replaceAll(
            com.poyka.ripdpi.data
                .testPauseAuthority()
                .supersede(com.poyka.ripdpi.data.RuntimeUserCommand.Stop),
            groups,
        )
    }

    override suspend fun delete(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        id: String,
    ) = Unit

    override suspend fun list(): List<ProxyGroup> = emptyList()

    override fun groups(): Flow<List<ProxyGroup>> = flowOf(emptyList())
}
