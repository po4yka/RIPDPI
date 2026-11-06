package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.flow.Flow

data class ProfileSelections(
    val xray: XrayProviderSelectionRecord = XrayProviderSelectionRecord(),
    val awgProfileId: String? = null,
    val warpProfileId: String? = null,
    val selectorGroupId: String? = null,
)

/** Canonical saved provider selection, available after credential unlock. */
interface ProfileSelectionStore {
    suspend fun snapshot(): ProfileSelections

    fun observe(): Flow<ProfileSelections>
}
