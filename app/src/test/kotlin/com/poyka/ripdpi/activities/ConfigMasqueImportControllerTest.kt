package com.poyka.ripdpi.activities

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigMasqueImportControllerTest {
    @Test
    fun `picker action and selected pkcs12 uri remain in retained state`() {
        var importedPkcs12: Triple<Uri, String?, Long>? = null
        val controller =
            ConfigMasqueImportController(
                importCertificateChain = { _, _ -> },
                importPrivateKey = { _, _ -> },
                importPkcs12 = { uri, password, sessionId -> importedPkcs12 = Triple(uri, password, sessionId) },
            )
        val uri = Uri.parse("content://fixture/client-identity.p12")
        val sessionId = 42L

        controller.begin(MasqueImportAction.Pkcs12, sessionId)
        assertEquals(MasqueImportAction.Pkcs12, controller.state.value.pendingAction)
        assertEquals(sessionId, controller.state.value.pendingSessionId)
        controller.onDocumentPicked(uri)

        assertEquals(null, controller.state.value.pendingAction)
        assertEquals(null, controller.state.value.pendingSessionId)
        assertEquals(uri, controller.state.value.pendingPkcs12Uri)
        assertEquals(sessionId, controller.state.value.pendingPkcs12SessionId)

        controller.importPendingPkcs12("fixture-password")

        assertEquals(Triple(uri, "fixture-password", sessionId), importedPkcs12)
        assertEquals(null, controller.state.value.pendingPkcs12Uri)
        assertEquals(null, controller.state.value.pendingPkcs12SessionId)
    }
}
