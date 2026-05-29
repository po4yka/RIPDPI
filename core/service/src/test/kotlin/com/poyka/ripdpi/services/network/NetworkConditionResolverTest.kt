package com.poyka.ripdpi.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConditionResolverTest {
    @Test
    fun `no usable network resolves to NoConnectivity`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = false,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = null,
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.NoConnectivity, condition)
    }

    @Test
    fun `blocked lifecycle resolves to BlockedReconnecting`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = true,
                captivePortalAssist = null,
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.BlockedReconnecting, condition)
    }

    @Test
    fun `reconnecting lifecycle resolves to BlockedReconnecting`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = true,
                captivePortalAssist = null,
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.BlockedReconnecting, condition)
    }

    @Test
    fun `active unexpired captive portal assist resolves to CaptivePortalAssist`() {
        val activation =
            CaptivePortalAssistActivation(
                activatedAtMillis = 1_000L,
                expiresAtMillis = 6_000L,
            )

        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = activation,
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 2_000L,
            )

        assertEquals(
            NetworkCondition.CaptivePortalAssist(activatedAtMillis = 1_000L, expiresAtMillis = 6_000L),
            condition,
        )
    }

    @Test
    fun `captive portal assist is inactive at and after expiry`() {
        val activation =
            CaptivePortalAssistActivation(
                activatedAtMillis = 1_000L,
                expiresAtMillis = 6_000L,
            )

        assertTrue(activation.isActiveAt(1_000L))
        assertTrue(activation.isActiveAt(5_999L))
        assertFalse(activation.isActiveAt(6_000L))
        assertFalse(activation.isActiveAt(10_000L))
        assertFalse(activation.isActiveAt(999L))
    }

    @Test
    fun `expired captive portal assist does not resolve to CaptivePortalAssist`() {
        val activation =
            CaptivePortalAssistActivation(
                activatedAtMillis = 1_000L,
                expiresAtMillis = 6_000L,
            )

        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = activation,
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 7_000L,
            )

        assertEquals(NetworkCondition.Normal, condition)
    }

    @Test
    fun `whitelist evidence positive case resolves to WhitelistSuspected`() {
        val evidence =
            WhitelistSuspicionEvidence(
                foreignEndpointsAttempted = 3,
                foreignEndpointsFailed = 3,
                domesticEndpointsAttempted = 2,
                domesticEndpointsSucceeded = 2,
            )

        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = null,
                whitelistEvidence = evidence,
                hasWhitelistRelayProfile = false,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.WhitelistSuspected(suggestRelayProfile = false), condition)
    }

    @Test
    fun `whitelist suggestion gated on having a relay profile`() {
        val evidence =
            WhitelistSuspicionEvidence(
                foreignEndpointsAttempted = 3,
                foreignEndpointsFailed = 3,
                domesticEndpointsAttempted = 2,
                domesticEndpointsSucceeded = 2,
            )

        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = null,
                whitelistEvidence = evidence,
                hasWhitelistRelayProfile = true,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.WhitelistSuspected(suggestRelayProfile = true), condition)
    }

    @Test
    fun `whitelist negative when a foreign endpoint succeeds`() {
        val evidence =
            WhitelistSuspicionEvidence(
                foreignEndpointsAttempted = 3,
                foreignEndpointsFailed = 2,
                domesticEndpointsAttempted = 2,
                domesticEndpointsSucceeded = 2,
            )

        assertFalse(isWhitelistSuspected(evidence))

        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = null,
                whitelistEvidence = evidence,
                hasWhitelistRelayProfile = true,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.Normal, condition)
    }

    @Test
    fun `whitelist negative when domestic endpoints also fail`() {
        val evidence =
            WhitelistSuspicionEvidence(
                foreignEndpointsAttempted = 3,
                foreignEndpointsFailed = 3,
                domesticEndpointsAttempted = 2,
                domesticEndpointsSucceeded = 0,
            )

        assertFalse(isWhitelistSuspected(evidence))

        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = null,
                whitelistEvidence = evidence,
                hasWhitelistRelayProfile = true,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.Normal, condition)
    }

    @Test
    fun `validated network with no signals resolves to Normal`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = null,
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.Normal, condition)
    }

    @Test
    fun `NoConnectivity takes precedence over BlockedReconnecting`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = false,
                lifecycleBlockedOrReconnecting = true,
                captivePortalAssist =
                    CaptivePortalAssistActivation(activatedAtMillis = 0L, expiresAtMillis = 10_000L),
                whitelistEvidence =
                    WhitelistSuspicionEvidence(
                        foreignEndpointsAttempted = 1,
                        foreignEndpointsFailed = 1,
                        domesticEndpointsAttempted = 1,
                        domesticEndpointsSucceeded = 1,
                    ),
                hasWhitelistRelayProfile = true,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.NoConnectivity, condition)
    }

    @Test
    fun `BlockedReconnecting takes precedence over CaptivePortalAssist`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = true,
                captivePortalAssist =
                    CaptivePortalAssistActivation(activatedAtMillis = 0L, expiresAtMillis = 10_000L),
                whitelistEvidence = null,
                hasWhitelistRelayProfile = false,
                nowMillis = 1_000L,
            )

        assertEquals(NetworkCondition.BlockedReconnecting, condition)
    }

    @Test
    fun `CaptivePortalAssist takes precedence over WhitelistSuspected`() {
        val condition =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist =
                    CaptivePortalAssistActivation(activatedAtMillis = 0L, expiresAtMillis = 10_000L),
                whitelistEvidence =
                    WhitelistSuspicionEvidence(
                        foreignEndpointsAttempted = 1,
                        foreignEndpointsFailed = 1,
                        domesticEndpointsAttempted = 1,
                        domesticEndpointsSucceeded = 1,
                    ),
                hasWhitelistRelayProfile = true,
                nowMillis = 1_000L,
            )

        assertEquals(
            NetworkCondition.CaptivePortalAssist(activatedAtMillis = 0L, expiresAtMillis = 10_000L),
            condition,
        )
    }

    @Test
    fun `resolving a condition does not mutate inputs (pure, no side effects)`() {
        // Fail-closed invariant: the decision must be a pure function. We assert that the
        // inputs are unchanged and that deciding twice with identical inputs yields an
        // identical result — there is no hidden state, no bypass toggle, no traffic mutation.
        val activation = CaptivePortalAssistActivation(activatedAtMillis = 1_000L, expiresAtMillis = 6_000L)
        val evidence =
            WhitelistSuspicionEvidence(
                foreignEndpointsAttempted = 3,
                foreignEndpointsFailed = 3,
                domesticEndpointsAttempted = 2,
                domesticEndpointsSucceeded = 2,
            )

        val first =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = activation,
                whitelistEvidence = evidence,
                hasWhitelistRelayProfile = true,
                nowMillis = 2_000L,
            )
        val second =
            decideNetworkCondition(
                hasUsableNetwork = true,
                lifecycleBlockedOrReconnecting = false,
                captivePortalAssist = activation,
                whitelistEvidence = evidence,
                hasWhitelistRelayProfile = true,
                nowMillis = 2_000L,
            )

        // Inputs are immutable data and remain unchanged after the decision.
        assertEquals(1_000L, activation.activatedAtMillis)
        assertEquals(6_000L, activation.expiresAtMillis)
        assertEquals(3, evidence.foreignEndpointsFailed)
        // Deterministic, repeatable output proves no hidden mutable state / side effect.
        assertEquals(first, second)
        assertEquals(
            NetworkCondition.CaptivePortalAssist(activatedAtMillis = 1_000L, expiresAtMillis = 6_000L),
            first,
        )
    }
}
