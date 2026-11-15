package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.persistence.ProfileRows

internal fun ProfileRows.addImportedGroup(
    group: ProxyGroup,
    profileId: String,
) {
    val member = group.members.single()
    require(group.type == ProxyGroupType.BASIC && !group.isSelector && group.subscription == null)
    require(member.id == profileId && member.groupId == group.id)
    val current = groups.list()
    require(current.none { it.id == group.id }) { "Imported group already exists" }
    groups.replace(current + group.copy(order = current.size).withNewOwnershipGeneration())
}
