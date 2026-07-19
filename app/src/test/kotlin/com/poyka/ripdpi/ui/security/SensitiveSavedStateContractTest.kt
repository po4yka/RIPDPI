package com.poyka.ripdpi.ui.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensitiveSavedStateContractTest {
    @Test
    fun `credential drafts never use rememberSaveable`() {
        val settings = source("ui/screens/settings/SettingsScreenLocalState.kt").readText()
        val modeEditor = source("ui/screens/config/ModeEditorRoute.kt").readText()

        assertTrue(settings.contains("backupPinDraft by remember {"))
        assertFalse(settings.contains("backupPinDraft by rememberSaveable"))
        assertTrue(modeEditor.contains("pkcs12Password by remember {"))
        assertFalse(modeEditor.contains("pkcs12Password by rememberSaveable"))
        assertTrue(modeEditor.contains("SecureWindowEffect()"))

        val dialogs = source("ui/screens/config/ModeEditorRouteDialogs.kt").readText()
        assertTrue(dialogs.contains("KeyboardType.Password"))
        assertTrue(dialogs.contains("PasswordVisualTransformation()"))
    }

    private fun source(relativePath: String): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/$relativePath"),
            File("app/src/main/kotlin/com/poyka/ripdpi/$relativePath"),
        ).first(File::isFile)
}
