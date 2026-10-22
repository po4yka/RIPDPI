package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** Shared storage/publication owner for ordinary group mutations and journal replay. */
@Singleton
class ProxyGroupCatalog
    @Inject
    constructor(
        private val blobStore: ProxyGroupBlobStore,
    ) {
        private val serializer = ListSerializer(ProxyGroup.serializer())
        private val mutex = Mutex()
        private val state = MutableStateFlow<List<ProxyGroup>?>(null)

        /** Recovery/catalog inspection must not seed startup observers. */
        suspend fun snapshot(): List<ProxyGroup> = mutex.withLock { readGroups() }

        suspend fun load(): List<ProxyGroup> = mutex.withLock { readGroups().also { state.value = it } }

        fun groups(): Flow<List<ProxyGroup>> = state.filterNotNull()

        suspend fun update(transform: (List<ProxyGroup>) -> List<ProxyGroup>) {
            mutex.withLock { writeGroups(transform(readGroups())) }
        }

        suspend fun updateGroup(
            id: String,
            transform: (ProxyGroup) -> ProxyGroup,
        ): ProxyGroup? =
            mutex.withLock {
                val groups = readGroups()
                val current = groups.firstOrNull { it.id == id } ?: return@withLock null
                val updated = transform(current)
                writeGroups(groups.map { if (it.id == id) updated else it })
                updated
            }

        /** Called only inside the recovery owner; successful replay also repairs existing observers. */
        suspend fun replace(groups: List<ProxyGroup>) {
            mutex.withLock {
                if (state.value == null) readGroups()
                writeGroups(groups)
            }
        }

        private fun readGroups(): List<ProxyGroup> =
            blobStore.read()?.let { RipDpiJson.decodeFromString(serializer, it) } ?: emptyList()

        private fun writeGroups(groups: List<ProxyGroup>) {
            val ordered = groups.sortedBy(ProxyGroup::order)
            blobStore.write(RipDpiJson.encodeToString(serializer, ordered))
            state.value = ordered
        }
    }
