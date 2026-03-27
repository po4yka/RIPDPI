package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PolicyHandoverEvent
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class DiagnosticsScanLaunchOrigin(
    val storageValue: String,
) {
    USER_INITIATED("user_initiated"),
    AUTOMATIC_BACKGROUND("automatic_background"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromStorageValue(value: String?): DiagnosticsScanLaunchOrigin {
            val normalized = value?.trim()?.lowercase(Locale.US)
            return entries.firstOrNull { it.storageValue == normalized } ?: UNKNOWN
        }
    }
}

@Serializable
enum class DiagnosticsScanTriggerType(
    val storageValue: String,
) {
    POLICY_HANDOVER("policy_handover"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromStorageValue(value: String?): DiagnosticsScanTriggerType {
            val normalized = value?.trim()?.lowercase(Locale.US)
            return entries.firstOrNull { it.storageValue == normalized } ?: UNKNOWN
        }
    }
}

@Serializable
data class DiagnosticsScanLaunchTrigger(
    val type: DiagnosticsScanTriggerType = DiagnosticsScanTriggerType.UNKNOWN,
    val classification: String? = null,
    val occurredAt: Long? = null,
    val previousFingerprintHash: String? = null,
    val currentFingerprintHash: String? = null,
) {
    companion object {
        fun fromStorage(
            type: String?,
            classification: String?,
            occurredAt: Long?,
            previousFingerprintHash: String?,
            currentFingerprintHash: String?,
        ): DiagnosticsScanLaunchTrigger? {
            val resolvedType = DiagnosticsScanTriggerType.fromStorageValue(type)
            return if (
                resolvedType == DiagnosticsScanTriggerType.UNKNOWN &&
                classification == null &&
                occurredAt == null &&
                previousFingerprintHash == null &&
                currentFingerprintHash == null
            ) {
                null
            } else {
                DiagnosticsScanLaunchTrigger(
                    type = resolvedType,
                    classification = classification,
                    occurredAt = occurredAt,
                    previousFingerprintHash = previousFingerprintHash,
                    currentFingerprintHash = currentFingerprintHash,
                )
            }
        }
    }
}

internal fun DiagnosticsScanOrigin.toLaunchOrigin(): DiagnosticsScanLaunchOrigin =
    when (this) {
        DiagnosticsScanOrigin.USER_INITIATED -> DiagnosticsScanLaunchOrigin.USER_INITIATED
        DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND -> DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND
    }

internal fun PolicyHandoverEvent.toLaunchTrigger(): DiagnosticsScanLaunchTrigger =
    DiagnosticsScanLaunchTrigger(
        type = DiagnosticsScanTriggerType.POLICY_HANDOVER,
        classification = classification,
        occurredAt = occurredAt,
        previousFingerprintHash = previousFingerprintHash,
        currentFingerprintHash = currentFingerprintHash,
    )
