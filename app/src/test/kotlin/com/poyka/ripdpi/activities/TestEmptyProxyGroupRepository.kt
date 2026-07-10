package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object TestEmptyProxyGroupRepository : ProxyGroupRepository {
    override suspend fun add(group: ProxyGroup) = Unit

    override suspend fun update(group: ProxyGroup) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun list(): List<ProxyGroup> = emptyList()

    override fun groups(): Flow<List<ProxyGroup>> = flowOf(emptyList())
}
