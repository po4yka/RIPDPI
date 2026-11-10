package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppsScriptRelayCertificateStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `CA storage survives store recreation in app files`() {
        val first = AppsScriptRelayCertificateStore(directory.root).dataDirectory
        first.mkdirs()
        val marker = first.resolve("persistent-marker")
        marker.writeText("retained")
        val reopened = AppsScriptRelayCertificateStore(directory.root).dataDirectory
        assertEquals(directory.root.canonicalFile, reopened.parentFile.canonicalFile)
        assertEquals("retained", reopened.resolve("persistent-marker").readText())
    }
}
