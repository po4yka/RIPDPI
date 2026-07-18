package com.poyka.ripdpi.activities

internal data class MasqueImportOperation(
    val sessionId: Long,
    val certificateGeneration: Long?,
    val privateKeyGeneration: Long?,
)

internal class ConfigMasqueImportGenerationTracker {
    private var certificateGeneration = 0L
    private var privateKeyGeneration = 0L

    fun begin(
        sessionId: Long,
        certificate: Boolean,
        privateKey: Boolean,
    ): MasqueImportOperation {
        if (certificate) certificateGeneration += 1
        if (privateKey) privateKeyGeneration += 1
        return MasqueImportOperation(
            sessionId = sessionId,
            certificateGeneration = certificateGeneration.takeIf { certificate },
            privateKeyGeneration = privateKeyGeneration.takeIf { privateKey },
        )
    }

    fun isCurrent(operation: MasqueImportOperation): Boolean =
        operation.certificateGeneration?.let { it == certificateGeneration } != false &&
            operation.privateKeyGeneration?.let { it == privateKeyGeneration } != false
}
