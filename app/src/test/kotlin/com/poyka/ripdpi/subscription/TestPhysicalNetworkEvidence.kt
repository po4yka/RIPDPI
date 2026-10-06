package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.services.CandidatePhysicalNetworkToken

/** Drives the real pure callback state machine; no synthetic ready token and no claim of Android/native proof. */
internal class TestPhysicalNetworkEvidence {
    private val type = Class.forName("com.poyka.ripdpi.services.CandidatePhysicalNetworkObserver")
    private val observer = type.getDeclaredConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
    private val registration = type.getDeclaredMethod("beginRegistration").invoke(observer) as Long

    init {
        type
            .getDeclaredMethod(
                "available",
                Long::class.javaPrimitiveType,
                Any::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(observer, registration, "physical", true)
        type
            .getDeclaredMethod(
                "capabilities",
                Long::class.javaPrimitiveType,
                Any::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(observer, registration, "physical", "internet-not-vpn", true)
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
            ).invoke(observer, registration, "physical", fingerprint, true)
    }
}
