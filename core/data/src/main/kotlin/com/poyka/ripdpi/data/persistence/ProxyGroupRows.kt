package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupBlobStore
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

internal class ProxyGroupRows(
    private val records: ProfileRecordAccess,
) {
    fun read(): String? = records.read(Namespace, Current)?.decodeToString()

    fun list(): List<ProxyGroup> = read()?.let { RipDpiJson.decodeFromString(Serializer, it) } ?: emptyList()

    fun write(json: String) {
        val groups = RipDpiJson.decodeFromString(Serializer, json)
        records.write(Namespace, Current, json.encodeToByteArray())
        val selection = ProfileSelectionRows(records)
        if (groups.none { it.id == selection.activeGroup() }) selection.setActiveGroup(null)
    }

    fun replace(groups: List<ProxyGroup>) =
        write(RipDpiJson.encodeToString(Serializer, groups.sortedBy(ProxyGroup::order)))

    fun clear() {
        records.clear(Namespace)
        ProfileSelectionRows(records).setActiveGroup(null)
    }

    companion object {
        const val Namespace = "proxy_groups"
        const val Current = "current"
        val Serializer = ListSerializer(ProxyGroup.serializer())
    }
}

@Singleton
internal class RoomProxyGroupBlobStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : ProxyGroupBlobStore {
        override suspend fun read() = database.read { it.groups.read() }

        override suspend fun write(json: String) = database.write { it.groups.write(json) }

        override suspend fun clear() = database.write { it.groups.clear() }

        override suspend fun update(transform: (String?) -> String?): String? =
            database.write { rows ->
                transform(rows.groups.read()).also { if (it == null) rows.groups.clear() else rows.groups.write(it) }
            }

        override fun observe() =
            database
                .observe(ProxyGroupRows.Namespace)
                .map { it[ProxyGroupRows.Current]?.decodeToString() }
                .distinctUntilChanged()
    }
