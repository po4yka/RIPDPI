package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.ProfileSelections
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.selector.SelectorRecords
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.Serializable

internal class ProfileSelectionRows(
    private val records: ProfileRecordAccess,
) : SelectorRecords {
    fun snapshot() = ProfileSelections(xray(), awg(), string(WarpKey), activeGroup())

    fun xray(): XrayProviderSelectionRecord =
        records.read(Namespace, XrayKey)?.let {
            RipDpiJson
                .decodeFromString(XrayProviderSelectionRecord.serializer(), it.decodeToString())
                .also { record -> record.kind }
        } ?: XrayProviderSelectionRecord()

    fun setXray(selection: XrayProviderSelectionRecord) {
        selection.kind
        records.write(
            Namespace,
            XrayKey,
            RipDpiJson
                .encodeToString(XrayProviderSelectionRecord.serializer(), selection)
                .encodeToByteArray(),
        )
    }

    fun awg(): String? = string(AwgKey)

    fun setAwg(id: String?) = setString(AwgKey, id)

    override fun activeGroup(): String? = string(ActiveGroupKey)

    override fun setActiveGroup(groupId: String?) = setString(ActiveGroupKey, groupId)

    override fun choice(groupId: String): SelectorSelectionSnapshot {
        val bytes = records.read(ChoiceNamespace, groupId)
        val stored = decodeChoice(bytes)
        val initial =
            if (bytes == null) {
                ProxyGroupRows(records).list().singleOrNull { it.id == groupId && it.isSelector }?.let { group ->
                    group.initialMemberId?.takeIf { id -> group.members.any { it.id == id && it.groupId == groupId } }
                }
            } else {
                null
            }
        return stored.copy(
            profileId = stored.profileId ?: initial,
            revision = Math.addExact(stored.revision, choiceEpoch()),
        )
    }

    override fun setChoice(
        groupId: String,
        profileId: String?,
        manual: Boolean,
    ) {
        val before = decodeChoice(records.read(ChoiceNamespace, groupId))
        val next = StoredChoice(profileId, profileId != null && manual, Math.addExact(before.revision, 1L))
        records.write(
            ChoiceNamespace,
            groupId,
            RipDpiJson
                .encodeToString(StoredChoice.serializer(), next)
                .encodeToByteArray(),
        )
    }

    /**
     * A refresh that drops the selected member falls back to the group's default member, as a fresh import would, so
     * the group stays startable and a running session reloads onto a member that exists.
     */
    fun replaceUnavailableChoice(group: ProxyGroup): Boolean {
        val storedId = decodeChoice(records.read(ChoiceNamespace, group.id)).profileId
        if (storedId == null || group.members.any { it.id == storedId }) return false
        val default =
            group.initialMemberId?.takeIf { id -> group.members.any { it.id == id } } ?: group.members.firstOrNull()?.id
        setChoice(group.id, default, false)
        return true
    }

    override fun clearSelections() {
        val groupsWithPreference = ProxyGroupRows(records).list().filter { it.initialMemberId != null }.map { it.id }
        (records.keys(ChoiceNamespace) + groupsWithPreference).distinct().forEach { setChoice(it, null, false) }
        records.write(Namespace, ChoiceEpochKey, Math.addExact(choiceEpoch(), 1L).toString().encodeToByteArray())
        setActiveGroup(null)
    }

    private fun choiceEpoch(): Long = records.read(Namespace, ChoiceEpochKey)?.decodeToString()?.toLong() ?: 0L

    private fun string(id: String): String? =
        records.read(Namespace, id)?.let {
            RipDpiJson.decodeFromString<String>(it.decodeToString()).takeIf(String::isNotBlank)
        }

    private fun setString(
        id: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            records.delete(Namespace, id)
        } else {
            records.write(Namespace, id, RipDpiJson.encodeToString(value).encodeToByteArray())
        }
    }

    companion object {
        const val Namespace = "profile_selection"
        const val ChoiceNamespace = "selector_choices"
        const val XrayKey = "xray"
        const val AwgKey = "awg_active"
        const val WarpKey = "warp_active"
        const val ActiveGroupKey = "selector_active"
        const val ChoiceEpochKey = "selector_epoch"

        fun decodeChoice(bytes: ByteArray?): SelectorSelectionSnapshot {
            val stored =
                bytes?.let { RipDpiJson.decodeFromString(StoredChoice.serializer(), it.decodeToString()) }
                    ?: StoredChoice()
            return SelectorSelectionSnapshot(stored.profileId, stored.manual, stored.revision)
        }
    }
}

@Serializable
private data class StoredChoice(
    val profileId: String? = null,
    val manual: Boolean = false,
    val revision: Long = 0,
)
