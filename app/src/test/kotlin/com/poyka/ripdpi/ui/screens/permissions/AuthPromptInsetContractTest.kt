package com.poyka.ripdpi.ui.screens.permissions

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthPromptInsetContractTest {
    @Test
    fun `shared auth footer reserves the navigation bar inset`() {
        val source = source().readText()
        val footer = source.substringAfter("footer = {").substringBefore("    ) {")

        assertTrue(footer.contains(".navigationBarsPadding()"))
        assertTrue(footer.indexOf(".navigationBarsPadding()") < footer.indexOf("footerBottomPadding"))
    }

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/screens/permissions/VpnPermissionScreen.kt"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/screens/permissions/VpnPermissionScreen.kt"),
        ).first(File::isFile)
}
