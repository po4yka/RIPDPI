package com.poyka.ripdpi.core.detection.community

import kotlinx.serialization.Serializable

@Serializable
data class CommunityStats(
    val totalReports: Int = 0,
    val verdictDistribution: Map<String, Int> = emptyMap(),
    val averageStealthScore: Double = 0.0,
    val byCountry: Map<String, CommunityCountryStats> = emptyMap(),
    val isLocalOnly: Boolean = false,
)

@Serializable
data class CommunityCountryStats(
    val totalReports: Int = 0,
    val detectedPercent: Double = 0.0,
    val averageStealthScore: Double = 0.0,
)
