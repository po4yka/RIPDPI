package com.poyka.ripdpi.data

/** Stages a complete immutable change; the persistence owner publishes it only after checked commit. */
internal fun ProfileUtilityState.acceptApplied(receipt: RuntimeAppliedUseReceipt): ProfileUtilityState {
    check(catalogReady && receipt.catalogGeneration == catalogGeneration && receipt.references.all(catalog::contains)) {
        "Applied profile catalog was superseded"
    }
    val existing = acknowledged.firstOrNull { it.identity == receipt.identity }
    if (existing != null) {
        check(existing == receipt) { "Conflicting applied runtime acknowledgment" }
        return this
    }
    check(
        acknowledged.none {
            it.identity.runtimeId == receipt.identity.runtimeId && it.identity.mode == receipt.identity.mode &&
                it.identity.revision >= receipt.identity.revision
        },
    ) { "Applied runtime revision was superseded" }
    val recordsUse = receipt.recordsUse && receipt.references.isNotEmpty()
    val sequence = if (recordsUse) Math.addExact(lastSequence, 1L) else lastSequence
    val updated =
        if (recordsUse) {
            receipt.references.map { RecentProfileUse(it, sequence, receipt.appliedAtMillis, receipt.identity) } +
                recents.filterNot { it.reference in receipt.references }
        } else {
            recents
        }
    return copy(
        recents = updated.take(MaxRecentProfiles),
        lastSequence = sequence,
        acknowledged = (acknowledged + receipt).takeLast(MaxAppliedAcknowledgments),
    )
}

internal fun ProfileUtilityState.withCatalog(references: Set<ProfileUtilityReference>): ProfileUtilityState =
    if (catalogReady && catalog == references) {
        this
    } else {
        copy(
            catalogGeneration = Math.addExact(catalogGeneration, 1L),
            catalogReady = true,
            catalog = references,
            favorites = favorites.intersect(references),
            recents = recents.filter { it.reference in references },
        )
    }

internal const val MaxRecentProfiles = 20
internal const val MaxAppliedAcknowledgments = 64
