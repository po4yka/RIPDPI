package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Domain transformations share the storage transaction and observe only committed groups. */
@Singleton
class ProxyGroupCatalog
    @Inject
    constructor(
        private val blobStore: ProxyGroupBlobStore,
    ) {
        private val serializer = ListSerializer(ProxyGroup.serializer())

        suspend fun snapshot(): List<ProxyGroup> = decode(blobStore.read())

        suspend fun load(): List<ProxyGroup> = snapshot()

        fun groups(): Flow<List<ProxyGroup>> = blobStore.observe().map(::decode).distinctUntilChanged()

        suspend fun add(group: ProxyGroup) {
            change { it.filterNot { existing -> existing.id == group.id } + group.withNewOwnershipGeneration() }
        }

        suspend fun update(transform: (List<ProxyGroup>) -> List<ProxyGroup>) {
            change { current ->
                val owners = current.associateBy(ProxyGroup::id)
                transform(current).map { it.ownedAfter(owners[it.id]) }
            }
        }

        suspend fun updateGroup(
            id: String,
            transform: (ProxyGroup) -> ProxyGroup,
        ): ProxyGroup? {
            var result: ProxyGroup? = null
            change { groups ->
                groups.map { current ->
                    if (current.id != id) {
                        current
                    } else {
                        transform(current)
                            .also { require(it.id == id) }
                            .ownedAfter(current)
                            .also { result = it }
                    }
                }
            }
            return result
        }

        suspend fun replace(groups: List<ProxyGroup>) =
            replaceAfterImage(groups.map { it.withNewOwnershipGeneration() })

        suspend fun replaceAfterImage(groups: List<ProxyGroup>) {
            change { groups }
        }

        private suspend fun change(transform: (List<ProxyGroup>) -> List<ProxyGroup>) {
            blobStore.update { encoded ->
                RipDpiJson.encodeToString(serializer, transform(decode(encoded)).sortedBy(ProxyGroup::order))
            }
        }

        private fun decode(encoded: String?): List<ProxyGroup> =
            encoded?.let { RipDpiJson.decodeFromString(serializer, it) } ?: emptyList()
    }

fun ProxyGroup.withNewOwnershipGeneration(): ProxyGroup = copy(ownershipGeneration = UUID.randomUUID().toString())

private fun ProxyGroup.ownedAfter(current: ProxyGroup?): ProxyGroup {
    val candidate = copy(ownershipGeneration = current?.ownershipGeneration.orEmpty())
    return if (candidate == current) current else candidate.withNewOwnershipGeneration()
}
