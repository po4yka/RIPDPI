package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModeNone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

internal class MasqueRelayValidationTest {
    @Test
    fun `masque without auth mode or secrets is valid and resolves to no auth mode`() {
        assertNull(resolveMasqueAuthModeSupport(null))
        assertNull(resolveMasqueAuthModeSupport(RelayCredentialRecord(profileId = "p")))
        validateMasqueRelayCredentials("p", null, null)
        validateMasqueRelayCredentials("p", null, RelayCredentialRecord(profileId = "p"))
    }

    @Test
    fun `explicit none mode wins over stored secrets and needs no credentials`() {
        val staleToken = RelayCredentialRecord(profileId = "p", masqueAuthMode = "none", masqueAuthToken = "t")
        assertEquals(RelayMasqueAuthModeNone, resolveMasqueAuthModeSupport(staleToken))
        validateMasqueRelayCredentials("p", RelayMasqueAuthModeNone, null)
    }

    @Test
    fun `selected masque auth mode without its secret is rejected`() {
        val empty = RelayCredentialRecord(profileId = "p")
        assertThrows(IllegalArgumentException::class.java) {
            validateMasqueRelayCredentials("p", RelayMasqueAuthModeBearer, empty)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMasqueRelayCredentials("p", RelayMasqueAuthModeCloudflareMtls, empty)
        }
    }

    @Test
    fun `secrets without an explicit mode infer a mode`() {
        val token = RelayCredentialRecord(profileId = "p", masqueAuthToken = "t")
        assertEquals(RelayMasqueAuthModeBearer, resolveMasqueAuthModeSupport(token))
    }
}
