package com.poyka.ripdpi.services

import java.io.FileDescriptor
import java.lang.reflect.Field

internal fun interface ProtectSocketFileDescriptorIntExtractor {
    fun extract(fileDescriptor: FileDescriptor): ProtectSocketFdExtractionResult
}

internal sealed interface ProtectSocketFdExtractionResult {
    data class Extracted(
        val value: Int,
    ) : ProtectSocketFdExtractionResult

    data class Failed(
        val error: ProtectSocketFdExtractionError,
    ) : ProtectSocketFdExtractionResult
}

internal sealed interface ProtectSocketFdExtractionError {
    val detail: String

    data class MissingDescriptorField(
        private val namesTried: List<String>,
    ) : ProtectSocketFdExtractionError {
        override val detail: String = "FileDescriptor field missing: tried ${namesTried.joinToString()}"
    }

    data class InaccessibleDescriptorField(
        private val cause: Throwable,
    ) : ProtectSocketFdExtractionError {
        override val detail: String =
            "FileDescriptor field inaccessible: ${cause.message ?: cause::class.java.simpleName}"
    }

    data class InvalidDescriptorValue(
        private val value: Int,
    ) : ProtectSocketFdExtractionError {
        override val detail: String = "FileDescriptor value was invalid: $value"
    }
}

internal object ReflectiveProtectSocketFileDescriptorIntExtractor :
    ProtectSocketFileDescriptorIntExtractor {
    private val descriptorFieldNames = listOf("descriptor", "fd")
    private val fieldResult by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { resolveDescriptorField() }

    override fun extract(fileDescriptor: FileDescriptor): ProtectSocketFdExtractionResult {
        val fieldResolution =
            fieldResult.getOrElse { error ->
                return ProtectSocketFdExtractionResult.Failed(mapFieldResolutionError(error))
            }
        val readResult = readFieldValue(fieldResolution, fileDescriptor)
        return readResult.getOrElse { error ->
            ProtectSocketFdExtractionResult.Failed(
                ProtectSocketFdExtractionError.InaccessibleDescriptorField(error),
            )
        }
    }

    private fun readFieldValue(
        field: Field,
        fileDescriptor: FileDescriptor,
    ): Result<ProtectSocketFdExtractionResult> {
        val intResult = runCatching { field.getInt(fileDescriptor) }
        val error = intResult.exceptionOrNull()
        if (error is RuntimeException && !error.isInaccessibleObjectException()) throw error
        return intResult.map { value ->
            if (value >= 0) {
                ProtectSocketFdExtractionResult.Extracted(value)
            } else {
                ProtectSocketFdExtractionResult.Failed(
                    ProtectSocketFdExtractionError.InvalidDescriptorValue(value),
                )
            }
        }
    }

    private fun resolveDescriptorField(): Result<Field> {
        val results = descriptorFieldNames.map { tryGetDeclaredField(it) }
        return results.firstOrNull { it.isSuccess }
            ?: results.firstOrNull { it.exceptionOrNull() !is NoSuchFieldException }
            ?: Result.failure(
                results.firstNotNullOfOrNull { it.exceptionOrNull() as? NoSuchFieldException }
                    ?: NoSuchFieldException("No descriptor field found in ${FileDescriptor::class.java.name}"),
            )
    }

    private fun tryGetDeclaredField(name: String): Result<Field> {
        val fieldResult =
            runCatching {
                val field = FileDescriptor::class.java.getDeclaredField(name)
                field.isAccessible = true
                field
            }
        val error = fieldResult.exceptionOrNull()
        if (error is RuntimeException && !error.isInaccessibleObjectException()) throw error
        return fieldResult
    }

    private fun mapFieldResolutionError(error: Throwable): ProtectSocketFdExtractionError =
        when (error) {
            is NoSuchFieldException -> {
                ProtectSocketFdExtractionError.MissingDescriptorField(descriptorFieldNames)
            }

            is SecurityException -> {
                ProtectSocketFdExtractionError.InaccessibleDescriptorField(error)
            }

            is RuntimeException -> {
                if (error.isInaccessibleObjectException()) {
                    ProtectSocketFdExtractionError.InaccessibleDescriptorField(error)
                } else {
                    throw error
                }
            }

            else -> {
                throw error
            }
        }

    private fun RuntimeException.isInaccessibleObjectException(): Boolean =
        javaClass.name == "java.lang.reflect.InaccessibleObjectException"
}
