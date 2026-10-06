package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import kotlinx.coroutines.sync.Mutex

/** One recovery transaction owner shared by profile and standalone mutations. */
internal class ProfileMutationRuntime(
    val stores: ProfileMutationStores,
    awgProfiles: AwgProfileDao,
    awgCredentials: AwgCredentialStore,
    val journal: ProfileMutationJournal,
    val mutationGeneration: ProfileMutationGenerationPublisher,
    val pauseAuthority: PauseIntentAuthority,
) {
    val warpRevisions = WarpRuntimeMutationRevisions()

    val mutex = Mutex()
    val intentProjection = ProfileMutationIntentProjection()
    val recoveryReader = ProfileMutationJournalRecoveryReader(journal)
    val replayWriter = ProfileMutationReplayWriter(stores, awgProfiles, awgCredentials, pauseAuthority)
    val catalog = ProfileUtilityCatalogPublisher(stores, pauseAuthority)

    val journalRecovery =
        ProfileMutationJournalRecovery(
            recoveryReader,
            replayWriter::replay,
            replayWriter::replayMeasuredReconstructed,
            replayWriter::replayReconstructed,
            journal,
            pauseAuthority,
            mutationGeneration,
            catalog,
            { intent ->
                if (intent is WarpUpsertIntent) {
                    warpRevisions.changed(intent.profile.id)
                } else {
                    publishCommittedWarpRevision(warpRevisions, intent, null, null)
                }
            },
        )

    val afterImageWriter =
        ProfileMutationAfterImageWriter(
            mutex,
            journalRecovery::recover,
            stores,
            journal,
            pauseAuthority,
            intentProjection,
            replayWriter,
            catalog,
            mutationGeneration,
            warpRevisions,
        )
    val standalone: StandaloneAwgMutationCoordinator =
        JournaledStandaloneAwgMutations(
            stores,
            pauseAuthority,
            mutex,
            journalRecovery,
            afterImageWriter,
        )
}
