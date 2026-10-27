package com.poyka.ripdpi.security

interface PinVerifier {
    fun hashPin(pin: String): String

    fun verify(
        candidatePin: String,
        storedHash: String,
    ): Boolean

    /**
     * `true` when the key exists, `false` when the keystore was read and has no key, and `null` when
     * the keystore could not be read (a transient failure that says nothing about the key).
     */
    fun isKeyAvailable(): Boolean?
}
