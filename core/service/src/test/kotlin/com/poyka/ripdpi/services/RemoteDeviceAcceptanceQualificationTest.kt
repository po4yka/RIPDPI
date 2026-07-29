package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceQualificationTest {
    @Test
    fun `UID bridge failure prevents an otherwise passing acceptance result`() {
        val qualified =
            passingAcceptanceReport().withUidPolicyQualification(
                RemoteDeviceUidPolicyQualification(
                    unprivilegedBindToDevice = BindToDeviceProbeOutcome.BridgeFailure.wireValue,
                    uidPolicyEligible = true,
                    uidPolicyArmed = true,
                ),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, qualified.status)
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, qualified.step(StepUidPolicyQualification).status)
        assertEquals(ErrorUidPolicyBridgeFailure, qualified.step(StepUidPolicyQualification).errorClass)
        val rendered = renderRemoteDeviceAcceptanceReport(qualified)
        assertTrue(rendered.contains("\"result\": \"incomplete\""))
        assertTrue(rendered.contains("\"errorClass\": \"uid_policy_bridge_failure\""))
    }

    @Test
    fun `unavailable recovery persistence makes acceptance explicitly incomplete`() {
        val report =
            passingAcceptanceReport().withRecoveryReceipt(
                RemoteDeviceRecoveryReceipt(
                    persistenceAvailability = "device_protected_storage_unavailable",
                ),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.status)
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.step(StepRecoveryReceiptPersistence).status)
        assertEquals(
            ErrorRecoveryReceiptPersistenceUnavailable,
            report.step(StepRecoveryReceiptPersistence).errorClass,
        )
        val rendered = renderRemoteDeviceAcceptanceReport(report)
        assertTrue(rendered.contains("\"result\": \"incomplete\""))
        assertTrue(rendered.contains("\"persistenceAvailability\": \"device_protected_storage_unavailable\""))
        assertTrue(rendered.contains("\"errorClass\": \"recovery_receipt_persistence_unavailable\""))
    }

    @Test
    fun `runtime recovery persistence write failure makes acceptance explicitly incomplete`() {
        val report =
            passingAcceptanceReport().withRecoveryReceipt(
                RemoteDeviceRecoveryReceipt(persistenceAvailability = "write_failed"),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.status)
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.step(StepRecoveryReceiptPersistence).status)
        assertEquals(
            ErrorRecoveryReceiptPersistenceWriteFailed,
            report.step(StepRecoveryReceiptPersistence).errorClass,
        )
        val rendered = renderRemoteDeviceAcceptanceReport(report)
        assertTrue(rendered.contains("\"persistenceAvailability\": \"write_failed\""))
        assertTrue(rendered.contains("\"errorClass\": \"recovery_receipt_persistence_write_failed\""))
    }

    @Test
    fun `ineligible or unarmed UID policy prevents an otherwise passing acceptance result`() {
        val report = passingAcceptanceReport()

        val ineligible =
            report.withUidPolicyQualification(
                RemoteDeviceUidPolicyQualification(
                    unprivilegedBindToDevice = BindToDeviceProbeOutcome.PermissionDenied.wireValue,
                    uidPolicyEligible = false,
                    uidPolicyArmed = false,
                ),
            )
        val unarmed =
            report.withUidPolicyQualification(
                RemoteDeviceUidPolicyQualification(
                    unprivilegedBindToDevice = BindToDeviceProbeOutcome.Supported.wireValue,
                    uidPolicyEligible = true,
                    uidPolicyArmed = false,
                ),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, ineligible.status)
        assertEquals(ErrorUidPolicyIneligible, ineligible.step(StepUidPolicyQualification).errorClass)
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, unarmed.status)
        assertEquals(ErrorUidPolicyNotArmed, unarmed.step(StepUidPolicyQualification).errorClass)
    }

    @Test
    fun `eligible armed UID policy allows an otherwise passing acceptance result`() {
        val qualified =
            passingAcceptanceReport().withUidPolicyQualification(
                RemoteDeviceUidPolicyQualification(
                    unprivilegedBindToDevice = BindToDeviceProbeOutcome.Supported.wireValue,
                    uidPolicyEligible = true,
                    uidPolicyArmed = true,
                ),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Pass, qualified.status)
        assertEquals(RemoteDeviceAcceptanceStatus.Pass, qualified.step(StepUidPolicyQualification).status)
        assertNull(qualified.step(StepUidPolicyQualification).errorClass)
    }

    private fun passingAcceptanceReport() =
        RemoteDeviceAcceptanceReport(
            status = RemoteDeviceAcceptanceStatus.Pass,
            device = RemoteDeviceAcceptanceDevice("unknown", "unknown", 0, "unknown"),
            steps =
                listOf(
                    RemoteDeviceAcceptanceStep(
                        id = StepUidPolicyQualification,
                        status = RemoteDeviceAcceptanceStatus.Pass,
                    ),
                    RemoteDeviceAcceptanceStep(
                        id = StepRecoveryReceiptPersistence,
                        status = RemoteDeviceAcceptanceStatus.Pass,
                    ),
                ),
        )

    private fun RemoteDeviceAcceptanceReport.step(id: String): RemoteDeviceAcceptanceStep =
        steps.first { step -> step.id == id }
}
