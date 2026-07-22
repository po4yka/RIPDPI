package com.poyka.ripdpi.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecreationStateOwnershipTest {
    @Test
    fun `activity result operations and large drafts use retained owners`() {
        val backupRoute = source("ui/screens/settings/BackupRestoreScreen.kt")
        val backupViewModel = source("backup/BackupRestoreViewModel.kt")
        val modeEditor = source("ui/screens/config/ModeEditorRoute.kt")
        val configViewModel = source("activities/ConfigViewModel.kt")
        val strategyRoute = source("ui/screens/settings/StrategyConfigRoute.kt")
        val domainRoute = source("ui/screens/settings/DomainBypassListScreen.kt")

        assertFalse(backupRoute.contains("pendingPassphrase by remember"))
        assertFalse(backupRoute.contains("pendingVariant by rememberSaveable"))
        assertTrue(backupViewModel.contains("pendingExport"))

        assertFalse(modeEditor.contains("pendingMasqueImportAction by remember"))
        assertFalse(modeEditor.contains("pendingPkcs12Uri by remember"))
        assertTrue(configViewModel.contains("masqueImportState"))

        assertFalse(strategyRoute.contains("configText by remember"))
        assertTrue(strategyRoute.contains("StrategyConfigEditorViewModel"))

        assertFalse(domainRoute.contains("var text by remember"))
        assertTrue(domainRoute.contains("viewModel::updateDraft"))
    }

    private fun source(relativePath: String): String =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/$relativePath"),
            File("app/src/main/kotlin/com/poyka/ripdpi/$relativePath"),
        ).first(File::isFile).readText()
}
