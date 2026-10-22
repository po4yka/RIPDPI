package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.subscription.MigrationTarget
import com.poyka.ripdpi.data.subscription.migrationFingerprint

/** Captures actual stored identities after every recoverable subscription write completes. */
suspend fun AwgProfileRepository.saveMigrationTargets(profiles: List<AwgSubscriptionProfile>): List<MigrationTarget> =
    saveSubscriptionProfiles(profiles).map { saved ->
        MigrationTarget(saved.name, saved.id, true, saved.request.migrationFingerprint())
    }
