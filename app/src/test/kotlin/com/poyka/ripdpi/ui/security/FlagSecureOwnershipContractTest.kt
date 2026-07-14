package com.poyka.ripdpi.ui.security

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FlagSecureOwnershipContractTest {
    @Test
    fun `FLAG_SECURE has one production owner`() {
        val sourceRoot =
            listOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
                .first(File::isDirectory)
        val owners =
            sourceRoot
                .walkTopDown()
                .filter { file ->
                    file.extension == "kt" &&
                        "FLAG_SECURE" in file.readText()
                }.map(File::getName)
                .toList()

        assertEquals(
            "FLAG_SECURE writes must be centralized in the secure window owner",
            listOf("SecureWindowEffect.kt"),
            owners,
        )
    }
}
