package com.poyka.ripdpi.services

import java.net.InetSocketAddress

private const val StructuredReadyPrefix = "RIPDPI-READY|"
private const val StructuredErrorPrefix = "RIPDPI-ERROR|"
private const val StructuredUpstreamOkPrefix = "RIPDPI-UPSTREAM-OK|"
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

    /** An upstream open succeeded, first after start or after a reported failure. */
    data class UpstreamOk(
        val runtimeKind: String,
    ) : SubprocessRelayOutputEvent

    data class PlainError(
        val message: String,
    ) : SubprocessRelayOutputEvent
}

internal class SubprocessRelayOutputParser {
    private val naiveUpstreamParser = NaiveUpstreamOutputParser()
    private var managedVersion = false
    private var managedComplete = false
    private var managedFailed = false
    private var pendingListener: InetSocketAddress? = null

    /** Drops per-process parser state; the supervisor calls it on each process start. */
    fun reset() {
        naiveUpstreamParser.reset()
        managedVersion = false
        managedComplete = false
        managedFailed = false
        pendingListener = null
    }

    fun parse(
        line: String,
        spec: SubprocessSocksRelayLaunchSpec,
    ): SubprocessRelayOutputEvent? {
        val trimmed = redactSensitive(line.trim(), spec)
        if (spec.managedClientBridge != null) return parseManaged(trimmed, spec)

        // The upstream Chromium client does not speak the RIPDPI-* protocol; its lines are plain output.
        return when {
            trimmed.isBlank() -> null
            spec.binaryName == NaiveUpstreamBinaryName -> naiveUpstreamParser.parse(trimmed, spec.runtimeKind)
            else -> parseStructuredEvent(trimmed) ?: parsePlainError(trimmed)
        }
    }

    private fun parseManaged(
        line: String,
        spec: SubprocessSocksRelayLaunchSpec,
    ): SubprocessRelayOutputEvent? {
        if (managedFailed) return null
        val method = requireNotNull(spec.managedClientBridge).methodName
        val keyword = line.substringBefore(' ')
        val negotiationOpen = managedVersion && !managedComplete
        return when (keyword) {
            in PlainErrorPrefixes -> {
                managedError(line, spec)
            }

            "VERSION" -> {
                if (managedVersion || line != "VERSION 1") {
                    managedError("Invalid managed PT version negotiation", spec)
                } else {
                    managedVersion = true
                    null
                }
            }

            "CMETHOD" -> {
                if (!negotiationOpen) {
                    managedError("Managed PT method outside negotiation", spec)
                } else if (line.split(' ').getOrNull(1) != method) {
                    null
                } else {
                    val listener = parseManagedClientListenerLine(line, method)
                    if (listener == null || pendingListener != null) {
                        managedError("Invalid managed PT SOCKS listener", spec)
                    } else {
                        pendingListener = listener
                        null
                    }
                }
            }

            "CMETHODS" -> {
                val listener = pendingListener
                if (line != "CMETHODS DONE" || !negotiationOpen || listener == null) {
                    managedError("Incomplete managed PT negotiation", spec)
                } else {
                    managedComplete = true
                    SubprocessRelayOutputEvent.ManagedClientListener(listener)
                }
            }

            else -> {
                parseStructuredEvent(line)
            }
        }
    }

    private fun managedError(
        message: String,
        spec: SubprocessSocksRelayLaunchSpec,
    ): SubprocessRelayOutputEvent.Error {
        managedFailed = true
        return SubprocessRelayOutputEvent.Error(spec.runtimeKind, "managed_pt_startup", message)
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

            line.startsWith(StructuredUpstreamOkPrefix) -> {
                SubprocessRelayOutputEvent.UpstreamOk(
                    runtimeKind = line.split('|').getOrNull(StructuredRuntimeKindIndex).orEmpty(),
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
