package com.poyka.ripdpi.diagnostics

interface HomeAnalysisAugmentationSource {
    suspend fun networkCharacter(): HomeNetworkCharacterSummary?

    suspend fun routingSanity(): HomeRoutingSanitySummary?

    suspend fun bufferbloat(): HomeBufferbloatResult?

    suspend fun dnsCharacterization(): HomeDnsCharacterization?
}
