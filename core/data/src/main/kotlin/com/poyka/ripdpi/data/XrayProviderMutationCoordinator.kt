package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord

interface XrayProviderMutationCoordinator {
    suspend fun upsertXrayProvider(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        profile: XrayProfile,
        selection: XrayProviderSelectionRecord,
        modeAfterImage: String,
    ): ProfileMutationOutcome

    suspend fun selectNativeProvider(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        selection: XrayProviderSelectionRecord,
        modeAfterImage: String,
    ): ProfileMutationOutcome
}
