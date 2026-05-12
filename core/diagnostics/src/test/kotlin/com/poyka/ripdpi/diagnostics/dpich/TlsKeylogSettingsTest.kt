package com.poyka.ripdpi.diagnostics.dpich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TlsKeylogSettingsTest {
    @Test
    fun settingDisabledByDefault() {
        val settings = TlsKeylogSettings()

        assertNull(settings.effectiveTlsKeylogPath(privacyModeEnabled = false))
    }

    @Test
    fun pathOutsideAllowedRootsIsRejected() {
        val filesDir = createTempDirectory().toFile()
        val validator = TlsKeylogPathValidator(appFilesDir = filesDir)

        val error = runCatching { validator.validate("/etc/passwd") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("TLS keylog path must be inside app storage", error?.message)
    }

    @Test
    fun pathInsideFilesDirIsAccepted() {
        val filesDir = createTempDirectory().toFile()
        val path = filesDir.resolve("diagnostics/tls.keys").absolutePath
        val validator = TlsKeylogPathValidator(appFilesDir = filesDir)

        assertEquals(File(path).canonicalPath, validator.validate(path))
    }

    @Test
    fun pathInsideSafRootIsAccepted() {
        val filesDir = createTempDirectory().toFile()
        val safRoot = createTempDirectory().toFile()
        val path = safRoot.resolve("tls.keys").absolutePath
        val validator = TlsKeylogPathValidator(appFilesDir = filesDir, safRoots = listOf(safRoot))

        assertEquals(File(path).canonicalPath, validator.validate(path))
    }

    @Test
    fun privacyModeOverridesStoredPath() {
        val settings = TlsKeylogSettings(path = "/app/files/tls.keys")

        assertNull(settings.effectiveTlsKeylogPath(privacyModeEnabled = true))
    }

    @Test
    fun debugModeControlsVisibility() {
        assertEquals(false, TlsKeylogSettings(debugModeEnabled = false).visibleInSettings)
        assertEquals(true, TlsKeylogSettings(debugModeEnabled = true).visibleInSettings)
    }
}
