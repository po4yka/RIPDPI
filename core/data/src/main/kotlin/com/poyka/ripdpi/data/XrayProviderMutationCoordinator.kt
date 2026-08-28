package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord

interface XrayProviderMutationCoordinator {
    suspend fun upsertXrayProvider(
        profileId: String,
        profile: XrayProfile,
        selection: XrayProviderSelectionRecord,
        modeAfterImage: String,
    )

    suspend fun selectNativeProvider(
        selection: XrayProviderSelectionRecord,
        modeAfterImage: String,
    )
}
