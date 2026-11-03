package com.poyka.ripdpi.ui.screens.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectionImportOwnershipTest {
    @Test
    fun `config route is the sole clipboard navigation owner`() {
        val routeSource = source("ConnectionScreen.kt").readText()
        val menuSource = source("ConnectionImportMenu.kt").readText()

        assertEquals(
            "Clipboard navigation must be consumed exactly once",
            1,
            Regex("""consumeNavigation\(\)""").findAll(routeSource + menuSource).count(),
        )
        assertFalse(menuSource.contains("ClipboardImportViewModel"))
        assertFalse(menuSource.contains("LaunchedEffect"))
        assertTrue(routeSource.contains("clipboardImportViewModel::onImportFromClipboard"))
    }

    private fun source(name: String): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/screens/connection/$name"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/screens/connection/$name"),
        ).first(File::isFile)
}
