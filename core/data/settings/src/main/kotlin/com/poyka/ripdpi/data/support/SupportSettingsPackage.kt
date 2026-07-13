package com.poyka.ripdpi.data.support

import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val SupportSettingsPackageSchema: Int = 1

@Serializable
data class SupportSettingsPackage(
    val schema: Int = SupportSettingsPackageSchema,
    val title: String = "",
    val reason: String = "",
    val operations: List<SupportSettingsOperation> = emptyList(),
    val restartPolicy: String = RestartPolicyAsk,
)

@Serializable
data class SupportSettingsOperation(
    val op: String,
    val path: String,
    val value: JsonElement = JsonNull,
    @SerialName("sensitive_reason")
    val sensitiveReason: String = "",
)

const val SupportSettingsOpSet: String = "set"
const val RestartPolicyAsk: String = "ask"
const val RestartPolicyNever: String = "never"
const val RestartPolicyRequired: String = "required"

sealed interface SupportSettingsPackageDecodeResult {
    data class Success(
        val value: SupportSettingsPackage,
    ) : SupportSettingsPackageDecodeResult

    enum class Error : SupportSettingsPackageDecodeResult {
        Empty,
        Malformed,
        UnsupportedSchema,
        NoOperations,
    }
}

object SupportSettingsPackageCodec {
    fun decode(payload: String): SupportSettingsPackageDecodeResult {
        if (payload.isBlank()) return SupportSettingsPackageDecodeResult.Error.Empty
        val parsed = decodePackage(payload)
        return when {
            parsed == null -> SupportSettingsPackageDecodeResult.Error.Malformed
            parsed.schema != SupportSettingsPackageSchema -> SupportSettingsPackageDecodeResult.Error.UnsupportedSchema
            parsed.operations.isEmpty() -> SupportSettingsPackageDecodeResult.Error.NoOperations
            else -> SupportSettingsPackageDecodeResult.Success(parsed)
        }
    }

    private fun decodePackage(payload: String): SupportSettingsPackage? =
        try {
            RipDpiContractJson.decodeFromString(SupportSettingsPackage.serializer(), payload)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
