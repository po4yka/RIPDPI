package com.poyka.ripdpi.core.detection.community

import kotlinx.serialization.Serializable

@Serializable
data class CommunitySubmission(
    val fingerprintHash: String,
    val verdict: String,
    val stealthScore: Int,
    val countryCode: String,
    val ispCategory: String,
    val methodologyVersion: String,
    val checkerCount: Int,
    val timestamp: Long,
)

@Serializable
data class CommunityStats(
    val totalReports: Int = 0,
    val verdictDistribution: Map<String, Int> = emptyMap(),
    val averageStealthScore: Double = 0.0,
    val byCountry: Map<String, CommunityCountryStats> = emptyMap(),
)

@Serializable
data class CommunityCountryStats(
    val totalReports: Int = 0,
    val detectedPercent: Double = 0.0,
    val averageStealthScore: Double = 0.0,
)
