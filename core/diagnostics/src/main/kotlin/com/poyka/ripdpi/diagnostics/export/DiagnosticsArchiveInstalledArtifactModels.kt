package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveInstalledArtifact(
    val collectionStatus: DiagnosticsArchiveInstalledArtifactCollectionStatus,
    val baseApkSha256: String? = null,
    val splitApkSha256: List<String> = emptyList(),
    val currentSignerCertificateSha256: List<String> = emptyList(),
    val signingLineage: DiagnosticsArchiveSigningLineageBand = DiagnosticsArchiveSigningLineageBand.UNAVAILABLE,
    val packagedNativeLibrarySha256: List<DiagnosticsArchiveInstalledNativeLibrary> = emptyList(),
    val debuggable: Boolean? = null,
    val failures: List<DiagnosticsArchiveInstalledArtifactFailure> = emptyList(),
)

@Serializable
internal enum class DiagnosticsArchiveInstalledArtifactCollectionStatus {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
    INCONSISTENT,
}

@Serializable
internal enum class DiagnosticsArchiveSigningLineageBand {
    SINGLE_CURRENT,
    SINGLE_WITH_HISTORY,
    MULTIPLE_CURRENT,
    UNAVAILABLE,
}

@Serializable
internal data class DiagnosticsArchiveInstalledNativeLibrary(
    val abi: DiagnosticsArchiveNativeAbi,
    val name: String,
    val sha256: String,
)

@Serializable
internal enum class DiagnosticsArchiveNativeAbi {
    ARM64,
    ARM32,
    X86_64,
    X86,
    OTHER,
}

@Serializable
internal enum class DiagnosticsArchiveInstalledArtifactFailure {
    PACKAGE_UNAVAILABLE,
    PACKAGE_QUERY_FAILED,
    SOURCE_UNAVAILABLE,
    BASE_APK_READ_FAILED,
    SPLIT_APK_READ_FAILED,
    SIGNERS_UNAVAILABLE,
    NATIVE_LIBRARY_SCAN_FAILED,
    NATIVE_LIBRARY_MISSING,
    NATIVE_LIBRARY_CONFLICT,
    ARTIFACT_CHANGED_DURING_COLLECTION,
}
