package com.poyka.ripdpi.services

import java.net.InetSocketAddress

private const val StructuredReadyPrefix = "RIPDPI-READY|"
private const val StructuredErrorPrefix = "RIPDPI-ERROR|"

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
        if (trimmed.isBlank()) {
            return null
        }
        spec.managedClientBridge
            ?.let { bridgeSpec ->
                parseManagedClientListenerLine(trimmed, bridgeSpec.methodName)
            }?.let { listener ->
                return SubprocessRelayOutputEvent.ManagedClientListener(listener)
            }
        return parseStructuredEvent(trimmed) ?: parsePlainError(trimmed)
    }

    private fun parseStructuredEvent(line: String): SubprocessRelayOutputEvent? {
        if (line.startsWith(StructuredReadyPrefix)) {
            val parts = line.split('|', limit = 3)
            return SubprocessRelayOutputEvent.Ready(
                runtimeKind = parts.getOrNull(1).orEmpty(),
                version = parts.getOrNull(2)?.takeIf(String::isNotBlank),
            )
        }
        if (line.startsWith(StructuredErrorPrefix)) {
            val parts = line.split('|', limit = 4)
            return SubprocessRelayOutputEvent.Error(
                runtimeKind = parts.getOrNull(1).orEmpty(),
                failureClass = parts.getOrNull(2).orEmpty(),
                message = parts.getOrNull(3).orEmpty(),
            )
        }
        return null
    }

    private fun parsePlainError(trimmed: String): SubprocessRelayOutputEvent.PlainError? =
        if (
            trimmed.startsWith("ENV-ERROR") ||
            trimmed.startsWith("VERSION-ERROR") ||
            trimmed.startsWith("PROXY-ERROR") ||
            trimmed.startsWith("CMETHOD-ERROR") ||
            trimmed.startsWith("SMETHOD-ERROR") ||
            trimmed.contains("[ERROR]") ||
            trimmed.contains(" error", ignoreCase = true)
        ) {
            SubprocessRelayOutputEvent.PlainError(trimmed)
        } else {
            null
        }

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
