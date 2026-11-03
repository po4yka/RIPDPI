package com.poyka.ripdpi.ui.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensitiveSavedStateContractTest {
    @Test
    fun `credential drafts never use rememberSaveable`() {
        val settings = source("ui/screens/settings/SettingsScreenLocalState.kt").readText()
        val modeEditor = source("ui/screens/connection/ModeEditorRoute.kt").readText()

        assertTrue(settings.contains("backupPinDraft by remember {"))
        assertFalse(settings.contains("backupPinDraft by rememberSaveable"))
        assertTrue(modeEditor.contains("SecureWindowEffect()"))

        val dialogs = source("ui/screens/connection/ModeEditorRouteDialogs.kt").readText()
        assertTrue(dialogs.contains("pkcs12Password by remember {"))
        assertFalse(dialogs.contains("pkcs12Password by rememberSaveable"))
        assertTrue(dialogs.contains("KeyboardType.Password"))
        assertTrue(dialogs.contains("TextObfuscationMode.Hidden"))

        val viewModel = source("ui/screens/connection/ConnectionViewModel.kt").readText()
        val recoverySupport = source("ui/screens/connection/ConnectionEditorRecoverySupport.kt").readText()
        val recoveryStore = source("ui/screens/connection/ConnectionEditorDraftStore.kt").readText()
        assertTrue(viewModel.contains("ConnectionEditorRecoverySessionIdSavedStateKey"))
        assertTrue(
            recoverySupport.contains("savedStateHandle[ConnectionEditorInvalidatedRecoverySessionIdsSavedStateKey]"),
        )
        assertFalse(
            viewModel.contains("savedStateHandle[ConnectionEditorRecoverySessionIdSavedStateKey] = editorSession"),
        )
        assertFalse(viewModel.contains("savedStateHandle[ConnectionEditorRecoverySessionIdSavedStateKey] = uiState"))
        assertTrue(recoveryStore.contains("AndroidKeyStore"))
        assertTrue(recoveryStore.contains("AES/GCM/NoPadding"))

        val workerTransport = source("ui/screens/settings/WsTunnelWorkerSettingsUi.kt").readText()
        assertTrue(workerTransport.contains("var bearer by remember {"))
        assertFalse(workerTransport.contains("bearer by rememberSaveable"))
        assertTrue(workerTransport.contains("SecureWindowEffect()"))
        assertTrue(workerTransport.contains("TextObfuscationMode.Hidden"))
    }

    private fun source(relativePath: String): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/$relativePath"),
            File("app/src/main/kotlin/com/poyka/ripdpi/$relativePath"),
        ).first(File::isFile)
}
