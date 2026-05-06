package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus

internal class ServiceStatusPersistence(
    private val mode: Mode,
    private val sender: Sender,
    private val serviceStateStore: ServiceStateStore,
) {
    fun applyStatus(
        newStatus: ServiceStatus,
        failureReason: FailureReason?,
    ) {
        if (newStatus == ServiceStatus.Failed) {
            serviceStateStore.emitFailed(
                sender,
                failureReason ?: FailureReason.Unexpected(IllegalStateException("Unknown failure")),
            )
        }

        serviceStateStore.setStatus(statusFor(newStatus), mode)
    }

    private fun statusFor(newStatus: ServiceStatus): AppStatus =
        when (newStatus) {
            ServiceStatus.Connected -> AppStatus.Running

            ServiceStatus.Failed,
            ServiceStatus.Disconnected,
            -> AppStatus.Halted
        }
}
