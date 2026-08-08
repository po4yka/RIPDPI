package com.poyka.ripdpi.services

import android.system.Os
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason

internal class ProtectSocketFdProtector(
    private val protectFailureMonitor: VpnProtectFailureMonitor,
    private val fdProtector: (Int) -> Boolean,
    private val clock: () -> Long,
    private val beforeProtectAncillaryFds: () -> Unit,
    private val fileDescriptorIntExtractor: ProtectSocketFileDescriptorIntExtractor,
) {
    private companion object {
        private val log = Logger.withTag("ProtectSocket")
        private const val MISSING_ANCILLARY_FD_DETAIL =
            "protect request did not include an SCM_RIGHTS file descriptor"
    }

    fun protectAncillaryFds(session: ProtectSocketClientSession): Boolean {
        val fds = session.ancillaryFileDescriptors.orEmpty()
        if (fds.isEmpty()) {
            reportProtectFailure(
                fd = -1,
                reason = FailureReason.NativeError(MISSING_ANCILLARY_FD_DETAIL),
                detail = MISSING_ANCILLARY_FD_DETAIL,
            )
            return false
        }

        beforeProtectAncillaryFds()

        var allProtected = true
        for (fd in fds) {
            when (val extracted = fileDescriptorIntExtractor.extract(fd)) {
                is ProtectSocketFdExtractionResult.Extracted -> {
                    if (!protectFd(extracted.value)) {
                        allProtected = false
                    }
                }

                is ProtectSocketFdExtractionResult.Failed -> {
                    reportProtectFailure(
                        fd = -1,
                        reason = FailureReason.NativeError(extracted.error.detail),
                        detail = extracted.error.detail,
                    )
                    allProtected = false
                }
            }
            runCatching { Os.close(fd) }
        }
        return allProtected
    }

    private fun protectFd(fdInt: Int): Boolean {
        val protectResult =
            runCatching { fdProtector(fdInt) }
                .fold(
                    onSuccess = { protected ->
                        if (protected) {
                            ProtectResult.Protected
                        } else {
                            ProtectResult.Rejected("VpnService.protect() returned false")
                        }
                    },
                    onFailure = { error ->
                        ProtectResult.Failed(
                            reason =
                                when (error) {
                                    is SecurityException -> {
                                        FailureReason.PermissionLost("VPN")
                                    }

                                    else -> {
                                        FailureReason.NativeError(
                                            "VpnService.protect() failed for fd=$fdInt: " +
                                                "${error.message ?: "unknown error"}",
                                        )
                                    }
                                },
                            detail =
                                error.message
                                    ?: "VpnService.protect() threw ${error::class.java.simpleName}",
                        )
                    },
                )
        when (protectResult) {
            ProtectResult.Protected -> {
                log.d { "protected fd=$fdInt" }
                return true
            }

            is ProtectResult.Rejected -> {
                reportProtectFailure(
                    fd = fdInt,
                    reason = FailureReason.PermissionLost("VPN"),
                    detail = protectResult.detail,
                )
            }

            is ProtectResult.Failed -> {
                reportProtectFailure(
                    fd = fdInt,
                    reason = protectResult.reason,
                    detail = protectResult.detail,
                )
            }
        }
        return false
    }

    private fun reportProtectFailure(
        fd: Int,
        reason: FailureReason,
        detail: String,
    ) {
        protectFailureMonitor.report(
            VpnProtectFailureEvent(
                fd = fd,
                reason = reason,
                detail = detail,
                detectedAt = clock(),
            ),
        )
        log.e { "vpn protect failed for fd=$fd: $detail" }
    }
}

private sealed interface ProtectResult {
    data object Protected : ProtectResult

    data class Rejected(
        val detail: String,
    ) : ProtectResult

    data class Failed(
        val reason: FailureReason,
        val detail: String,
    ) : ProtectResult
}
