package com.poyka.ripdpi.services

import java.net.InetSocketAddress

private const val StructuredReadyPrefix = "RIPDPI-READY|"
private const val StructuredErrorPrefix = "RIPDPI-ERROR|"
private const val ReadyEventPartLimit = 3
private const val ErrorEventPartLimit = 4
private const val StructuredRuntimeKindIndex = 1
private const val StructuredReadyVersionIndex = 2
private const val StructuredErrorFailureClassIndex = 2
private const val StructuredErrorMessageIndex = 3

private val PlainErrorPrefixes = listOf("ENV-ERROR", "VERSION-ERROR", "PROXY-ERROR", "CMETHOD-ERROR", "SMETHOD-ERROR")

internal sealed interface SubprocessRelayOutputEvent {
    data class ManagedClientListener(
        val listener: InetSocketAddress,
    ) : SubprocessRelayOutputEvent

    data class Ready(
        val runtimeKind: String,
        val version: String?,
    ) : SubprocessRelayOutputEvent

    data class Error(
        val runtimeKind: String,
        val failureClass: String,
        val message: String,
    ) : SubprocessRelayOutputEvent

    data class PlainError(
        val message: String,
    ) : SubprocessRelayOutputEvent
}

internal class SubprocessRelayOutputParser {
    fun parse(
        line: String,
        spec: SubprocessSocksRelayLaunchSpec,
    ): SubprocessRelayOutputEvent? {
        val trimmed = redactSensitive(line.trim(), spec)
        val managedClientListener =
            spec.managedClientBridge
                ?.let { bridgeSpec ->
                    parseManagedClientListenerLine(trimmed, bridgeSpec.methodName)
                }

        return when {
            trimmed.isBlank() -> null
            managedClientListener != null -> SubprocessRelayOutputEvent.ManagedClientListener(managedClientListener)
            else -> parseStructuredEvent(trimmed) ?: parsePlainError(trimmed)
        }
    }

    private fun parseStructuredEvent(line: String): SubprocessRelayOutputEvent? =
        when {
            line.startsWith(StructuredReadyPrefix) -> {
                val parts = line.split('|', limit = ReadyEventPartLimit)
                SubprocessRelayOutputEvent.Ready(
                    runtimeKind = parts.getOrNull(StructuredRuntimeKindIndex).orEmpty(),
                    version = parts.getOrNull(StructuredReadyVersionIndex)?.takeIf(String::isNotBlank),
                )
            }

            line.startsWith(StructuredErrorPrefix) -> {
                val parts = line.split('|', limit = ErrorEventPartLimit)
                SubprocessRelayOutputEvent.Error(
                    runtimeKind = parts.getOrNull(StructuredRuntimeKindIndex).orEmpty(),
                    failureClass = parts.getOrNull(StructuredErrorFailureClassIndex).orEmpty(),
                    message = parts.getOrNull(StructuredErrorMessageIndex).orEmpty(),
                )
            }

            else -> {
                null
            }
        }

    private fun parsePlainError(trimmed: String): SubprocessRelayOutputEvent.PlainError? =
        if (isPlainError(trimmed)) {
            SubprocessRelayOutputEvent.PlainError(trimmed)
        } else {
            null
        }

    private fun isPlainError(trimmed: String): Boolean =
        PlainErrorPrefixes.any(trimmed::startsWith) ||
            trimmed.contains("[ERROR]") ||
            trimmed.contains(" error", ignoreCase = true)

    private fun redactSensitive(
        raw: String,
        spec: SubprocessSocksRelayLaunchSpec,
    ): String =
        spec.redactedValues
            .asSequence()
            .filter(String::isNotBlank)
            .fold(raw) { message, secret ->
                message.replace(secret, "<redacted>")
            }
}
