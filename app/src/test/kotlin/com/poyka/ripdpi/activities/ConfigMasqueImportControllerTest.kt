package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
                importCertificateChain = { _, _ -> true },
                importPrivateKey = { _, _ -> true },
                importPkcs12 = { uri, password, sessionId ->
                    importedPkcs12 = Triple(uri, password, sessionId)
                    true
                },
            )
        val uri = Uri.parse("content://fixture/client-identity.p12")
        val sessionId = 42L

        controller.begin(MasqueImportAction.Pkcs12, sessionId, "owner-42")
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

    @Test
    fun `picker action survives process recreation and rebinds to restored editor`() {
        val savedStateHandle = SavedStateHandle()
        var importedCertificate: Pair<Uri, Long>? = null
        val first =
            persistentController(savedStateHandle, onCertificate = { uri, sessionId ->
                importedCertificate = uri to sessionId
            })

        first.begin(MasqueImportAction.CertificateChain, 42L, "owner-42")
        val restored =
            persistentController(savedStateHandle, onCertificate = { uri, sessionId ->
                importedCertificate = uri to sessionId
            })
        restored.rebindPendingSession(84L, "owner-42")
        val uri = Uri.parse("content://fixture/client-chain.pem")
        restored.onDocumentPicked(uri)

        assertEquals(uri to 84L, importedCertificate)
        assertEquals(null, restoreConfigMasqueImportState(savedStateHandle).pendingAction)
    }

    @Test
    fun `picker result waits for restored editor before import`() {
        val savedStateHandle = SavedStateHandle()
        val imports = mutableListOf<Pair<Uri, Long>>()
        val first = persistentController(savedStateHandle, onCertificate = { uri, id -> imports += uri to id })
        first.begin(MasqueImportAction.CertificateChain, 42L, "owner-42")
        val restored = persistentController(savedStateHandle, onCertificate = { uri, id -> imports += uri to id })
        val uri = Uri.parse("content://fixture/early-client-chain.pem")

        restored.onDocumentPicked(uri)

        assertEquals(emptyList<Pair<Uri, Long>>(), imports)
        assertEquals(uri, restoreConfigMasqueImportState(savedStateHandle).pickedDocumentUri)

        restored.rebindPendingSession(84L, "owner-42")

        assertEquals(listOf(uri to 84L), imports)
        assertEquals(null, restoreConfigMasqueImportState(savedStateHandle).pickedDocumentUri)
    }

    @Test
    fun `picker result remains queued until importer accepts restored session`() {
        var acceptsImport = false
        val imports = mutableListOf<Pair<Uri, Long>>()
        val controller =
            ConfigMasqueImportController(
                importCertificateChain = { uri, sessionId ->
                    if (acceptsImport) imports += uri to sessionId
                    acceptsImport
                },
                importPrivateKey = { _, _ -> true },
                importPkcs12 = { _, _, _ -> true },
            )
        val uri = Uri.parse("content://fixture/retry-client-chain.pem")
        controller.begin(MasqueImportAction.CertificateChain, 42L, "owner-42")

        controller.onDocumentPicked(uri)

        assertEquals(uri, controller.state.value.pickedDocumentUri)
        acceptsImport = true
        controller.rebindPendingSession(84L, "owner-42")

        assertEquals(listOf(uri to 84L), imports)
        assertEquals(null, controller.state.value.pickedDocumentUri)
    }

    @Test
    fun `queued picker result is cleared for replacement editor owner`() {
        val savedStateHandle = SavedStateHandle()
        val imports = mutableListOf<Pair<Uri, Long>>()
        val first = persistentController(savedStateHandle, onCertificate = { uri, id -> imports += uri to id })
        val uri = Uri.parse("content://fixture/stale-client-chain.pem")
        first.begin(MasqueImportAction.CertificateChain, 42L, "owner-42")
        val restored =
            persistentController(savedStateHandle, onCertificate = { importedUri, id ->
                imports += importedUri to id
            })
        restored.onDocumentPicked(uri)

        restored.rebindPendingSession(84L, "replacement-owner-84")

        assertEquals(emptyList<Pair<Uri, Long>>(), imports)
        assertEquals(ConfigMasqueImportState(), restored.state.value)
        assertEquals(emptySet<String>(), savedStateHandle.keys())
    }

    @Test
    fun `pkcs12 uri survives process recreation without persisting password`() {
        val savedStateHandle = SavedStateHandle()
        var importedPkcs12: Triple<Uri, String?, Long>? = null
        val first = persistentController(savedStateHandle, onPkcs12 = { importedPkcs12 = it })
        val uri = Uri.parse("content://fixture/client-identity.p12")

        first.begin(MasqueImportAction.Pkcs12, 42L, "owner-42")
        first.onDocumentPicked(uri)
        val restored = persistentController(savedStateHandle, onPkcs12 = { importedPkcs12 = it })
        restored.rebindPendingSession(84L, "owner-42")
        restored.importPendingPkcs12("never-persist-this")

        assertEquals(Triple(uri, "never-persist-this", 84L), importedPkcs12)
        assertEquals(
            false,
            savedStateHandle.keys().any { key -> savedStateHandle.get<Any?>(key) == "never-persist-this" },
        )
        assertEquals(null, restoreConfigMasqueImportState(savedStateHandle).pendingPkcs12Uri)
    }

    @Test
    fun `pkcs12 import waits for restored editor before accepting password`() {
        val savedStateHandle = SavedStateHandle()
        val imports = mutableListOf<Triple<Uri, String?, Long>>()
        val first = persistentController(savedStateHandle, onPkcs12 = imports::add)
        val uri = Uri.parse("content://fixture/early-client-identity.p12")
        first.begin(MasqueImportAction.Pkcs12, 42L, "owner-42")
        first.onDocumentPicked(uri)
        val restored = persistentController(savedStateHandle, onPkcs12 = imports::add)

        restored.importPendingPkcs12("not-before-recovery")

        assertEquals(emptyList<Triple<Uri, String?, Long>>(), imports)
        assertEquals(uri, restored.state.value.pendingPkcs12Uri)

        restored.rebindPendingSession(84L, "owner-42")
        restored.importPendingPkcs12("after-recovery")

        assertEquals(listOf(Triple(uri, "after-recovery", 84L)), imports)
    }

    private fun persistentController(
        savedStateHandle: SavedStateHandle,
        onCertificate: (Uri, Long) -> Unit = { _, _ -> },
        onPkcs12: (Triple<Uri, String?, Long>) -> Unit = {},
    ): ConfigMasqueImportController =
        ConfigMasqueImportController(
            importCertificateChain = { uri, sessionId ->
                onCertificate(uri, sessionId)
                true
            },
            importPrivateKey = { _, _ -> true },
            importPkcs12 = { uri, password, sessionId ->
                onPkcs12(Triple(uri, password, sessionId))
                true
            },
            initialState = restoreConfigMasqueImportState(savedStateHandle),
            onStateChanged = { state -> persistConfigMasqueImportState(savedStateHandle, state) },
        )
}
