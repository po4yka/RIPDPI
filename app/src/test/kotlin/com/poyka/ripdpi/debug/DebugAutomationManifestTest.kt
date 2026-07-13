package com.poyka.ripdpi.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugAutomationManifestTest {
    @Test
    fun `debug automation alias requires shell dump permission without restricting main activity`() {
        val debugManifest = sourceFile("src/debug/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml").readText()
        val automationAlias =
            debugManifest
                .substringAfter(".activities.DebugAutomationActivity")
                .substringBefore(">")
        val mainManifest = sourceFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        val mainActivity =
            mainManifest
                .substringAfter(".activities.MainActivity")
                .substringBefore(">")

        assertTrue(automationAlias.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(automationAlias.contains("android:targetActivity=\".activities.MainActivity\""))
        assertFalse(mainActivity.contains("android:permission="))
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates
            .map(::File)
            .first(File::exists)
}
