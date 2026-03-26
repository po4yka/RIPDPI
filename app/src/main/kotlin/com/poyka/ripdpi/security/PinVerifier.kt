package com.poyka.ripdpi.security

interface PinVerifier {
    fun hashPin(pin: String): String
    fun verify(candidatePin: String, storedHash: String): Boolean
    fun isKeyAvailable(): Boolean
}
