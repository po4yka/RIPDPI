package com.poyka.ripdpi.services

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [DnsLeakFailureClassifier] maps symptom sets to typed [DnsLeakFailureClass] variants.
 *
 * The 7 failure scenarios from the spec:
 *   1. Remote resolver outage
 *   2. Bootstrap resolver failure
 *   3. Proxy outbound failure
 *   4. Android Private DNS enabled
 *   5. Wi-Fi/LTE switch (network switch)
 *   6. Captive portal
 *   7. Core crash
 */
class DnsLeakFailureCaseTest {
    private val classifier = DnsLeakFailureClassifier()

    @Test
    fun remoteResolverOutageSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(remoteResolverUnreachable = true))
        assertTrue(result is DnsLeakFailureClass.RemoteResolverOutage)
    }

    @Test
    fun bootstrapResolverFailureSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(bootstrapFailed = true))
        assertTrue(result is DnsLeakFailureClass.BootstrapResolverFailure)
    }

    @Test
    fun proxyOutboundFailureSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(proxyOutboundFailed = true))
        assertTrue(result is DnsLeakFailureClass.ProxyOutboundFailure)
    }

    @Test
    fun androidPrivateDnsSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(androidPrivateDnsEnabled = true))
        assertTrue(result is DnsLeakFailureClass.AndroidPrivateDnsEnabled)
    }

    @Test
    fun networkSwitchSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(networkSwitchOccurred = true))
        assertTrue(result is DnsLeakFailureClass.NetworkSwitch)
    }

    @Test
    fun captivePortalSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(captivePortalDetected = true))
        assertTrue(result is DnsLeakFailureClass.CaptivePortal)
    }

    @Test
    fun coreCrashSymptomsClassifyCorrectly() {
        val result = classifier.classify(DnsLeakSymptoms(coreCrashDetected = true))
        assertTrue(result is DnsLeakFailureClass.CoreCrash)
    }

    @Test
    fun remoteResolverOutageTakesPriorityOverNetworkSwitch() {
        val result =
            classifier.classify(
                DnsLeakSymptoms(
                    remoteResolverUnreachable = true,
                    networkSwitchOccurred = true,
                ),
            )
        assertTrue(result is DnsLeakFailureClass.RemoteResolverOutage)
    }

    @Test
    fun noSymptomsYieldsRemoteResolverOutage() {
        // With no symptoms the safest diagnosis is an outage (fail-closed)
        val result = classifier.classify(DnsLeakSymptoms())
        assertTrue(result is DnsLeakFailureClass.RemoteResolverOutage)
    }
}
