package com.poyka.ripdpi.diagnostics

data class HomeDetectorCatalogSnapshot(
    val installedVpnDetectorCount: Int = 0,
    val topDetectorPackages: List<String> = emptyList(),
)

interface HomeDetectorCatalogSource {
    fun snapshot(): HomeDetectorCatalogSnapshot
}
