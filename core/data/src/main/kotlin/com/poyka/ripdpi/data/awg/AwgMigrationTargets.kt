package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.subscription.MigrationTarget
import com.poyka.ripdpi.data.subscription.migrationFingerprint

/** Captures actual stored identities after every recoverable subscription write completes. */
suspend fun AwgProfileRepository.saveMigrationTargets(profiles: List<AwgSubscriptionProfile>): List<MigrationTarget> =
    saveSubscriptionProfiles(profiles).map(SavedAwgProfile::toMigrationTarget)

/** Uses the exact rehydrated after-image, including its assigned row id and merged key fields. */
fun SavedAwgProfile.toMigrationTarget(): MigrationTarget =
    MigrationTarget(name, id, true, request.migrationFingerprint())
