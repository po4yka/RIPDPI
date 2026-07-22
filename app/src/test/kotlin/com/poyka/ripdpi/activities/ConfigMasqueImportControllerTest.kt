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
        var importedPkcs12: Pair<Uri, String?>? = null
        val controller =
            ConfigMasqueImportController(
                importCertificateChain = {},
                importPrivateKey = {},
                importPkcs12 = { uri, password -> importedPkcs12 = uri to password },
            )
        val uri = Uri.parse("content://fixture/client-identity.p12")

        controller.begin(MasqueImportAction.Pkcs12)
        assertEquals(MasqueImportAction.Pkcs12, controller.state.value.pendingAction)
        controller.onDocumentPicked(uri)

        assertEquals(null, controller.state.value.pendingAction)
        assertEquals(uri, controller.state.value.pendingPkcs12Uri)

        controller.importPendingPkcs12("fixture-password")

        assertEquals(uri to "fixture-password", importedPkcs12)
        assertEquals(null, controller.state.value.pendingPkcs12Uri)
    }
}
