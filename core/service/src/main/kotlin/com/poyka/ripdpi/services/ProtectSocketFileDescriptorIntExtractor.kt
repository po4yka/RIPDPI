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
        val field =
            fieldResult.getOrElse { error ->
                return ProtectSocketFdExtractionResult.Failed(mapFieldResolutionError(error))
            }

        val value =
            try {
                field.getInt(fileDescriptor)
            } catch (error: IllegalAccessException) {
                return ProtectSocketFdExtractionResult.Failed(
                    ProtectSocketFdExtractionError.InaccessibleDescriptorField(error),
                )
            } catch (error: SecurityException) {
                return ProtectSocketFdExtractionResult.Failed(
                    ProtectSocketFdExtractionError.InaccessibleDescriptorField(error),
                )
            } catch (error: RuntimeException) {
                if (error.isInaccessibleObjectException()) {
                    return ProtectSocketFdExtractionResult.Failed(
                        ProtectSocketFdExtractionError.InaccessibleDescriptorField(error),
                    )
                }
                throw error
            }

        return if (value >= 0) {
            ProtectSocketFdExtractionResult.Extracted(value)
        } else {
            ProtectSocketFdExtractionResult.Failed(
                ProtectSocketFdExtractionError.InvalidDescriptorValue(value),
            )
        }
    }

    private fun resolveDescriptorField(): Result<Field> {
        var lastMissingField: NoSuchFieldException? = null
        for (candidate in descriptorFieldNames) {
            try {
                val field = FileDescriptor::class.java.getDeclaredField(candidate)
                field.isAccessible = true
                return Result.success(field)
            } catch (error: NoSuchFieldException) {
                lastMissingField = error
            } catch (error: SecurityException) {
                return Result.failure(error)
            } catch (error: RuntimeException) {
                if (error.isInaccessibleObjectException()) {
                    return Result.failure(error)
                }
                throw error
            }
        }
        return Result.failure(
            lastMissingField
                ?: NoSuchFieldException(
                    "No descriptor field found in ${FileDescriptor::class.java.name}",
                ),
        )
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
