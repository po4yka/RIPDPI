package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.services.CandidatePhysicalNetworkToken

/** Drives the real pure callback state machine; no synthetic ready token and no claim of Android/native proof. */
internal class TestPhysicalNetworkEvidence {
    private val type = Class.forName("com.poyka.ripdpi.services.CandidatePhysicalNetworkObserver")
    private val observer = type.getDeclaredConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
    private val registration = type.getDeclaredMethod("beginRegistration").invoke(observer) as Long
    private var selected = "physical"

    init {
        handover(selected)
    }

    fun handover(network: String) {
        selected = network
        type
            .getDeclaredMethod(
                "available",
                Long::class.javaPrimitiveType,
                Any::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(observer, registration, network, true)
        type
            .getDeclaredMethod(
                "capabilities",
                Long::class.javaPrimitiveType,
                Any::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(observer, registration, network, "internet-not-vpn", true)
        links("initial-route")
    }

    fun capture(): CandidatePhysicalNetworkToken =
        checkNotNull(type.getDeclaredMethod("capture").invoke(observer) as CandidatePhysicalNetworkToken?)

    fun change() = links("changed-route")

    private fun links(fingerprint: String) {
        type
            .getDeclaredMethod(
                "links",
                Long::class.javaPrimitiveType,
                Any::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(observer, registration, selected, fingerprint, true)
    }
}
