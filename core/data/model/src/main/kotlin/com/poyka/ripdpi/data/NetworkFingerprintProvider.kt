package com.poyka.ripdpi.data

interface NetworkFingerprintProvider {
    fun capture(): NetworkFingerprint?
}
