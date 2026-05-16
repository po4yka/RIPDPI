package com.poyka.ripdpi.services.redaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashReportContentTest {
    @Test
    fun `crash report contains config hash`() {
        val report =
            CrashReportPayload.build(
                configHash = "sha256-fixture-config-hash",
                profileId = "fixture-profile-id-1",
                stateReason = "tunnel_failed",
            )
        assertEquals("sha256-fixture-config-hash", report.configHash)
    }

    @Test
    fun `crash report contains profile id`() {
        val report =
            CrashReportPayload.build(
                configHash = "sha256-fixture-config-hash",
                profileId = "fixture-profile-id-1",
                stateReason = "tunnel_failed",
            )
        assertEquals("fixture-profile-id-1", report.profileId)
    }

    @Test
    fun `crash report contains state reason`() {
        val report =
            CrashReportPayload.build(
                configHash = "sha256-fixture-config-hash",
                profileId = "fixture-profile-id-1",
                stateReason = "tunnel_failed",
            )
        assertEquals("tunnel_failed", report.stateReason)
    }

    @Test
    fun `crash report toString does not expose raw server address`() {
        val fixtureServer = "fixture-vpn-server.example.com"
        val report =
            CrashReportPayload.build(
                configHash = "hash-of-${fixtureServer.length}-chars",
                profileId = "fixture-profile-id-2",
                stateReason = "dns_blocked",
            )
        assertFalse(
            "raw server must not appear in crash report",
            report.toString().contains(fixtureServer),
        )
    }

    @Test
    fun `crash report toString does not expose raw fixture password`() {
        val fixturePassword = "fixture-password-2"
        val report =
            CrashReportPayload.build(
                configHash = "sha256-fixture-config-hash-b",
                profileId = "fixture-profile-id-3",
                stateReason = "auth_failed",
            )
        assertFalse(
            "raw password must not appear in crash report",
            report.toString().contains(fixturePassword),
        )
    }

    @Test
    fun `two reports with same fields are equal`() {
        val a =
            CrashReportPayload.build(
                configHash = "sha256-abc",
                profileId = "fixture-profile-id-4",
                stateReason = "ok",
            )
        val b =
            CrashReportPayload.build(
                configHash = "sha256-abc",
                profileId = "fixture-profile-id-4",
                stateReason = "ok",
            )
        assertEquals(a, b)
    }
}
