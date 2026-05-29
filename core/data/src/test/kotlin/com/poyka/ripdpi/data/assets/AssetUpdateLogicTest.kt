package com.poyka.ripdpi.data.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetUpdateLogicTest {
    @Test
    fun `update available when remote tag differs from stored`() {
        assertTrue(isAssetUpdateAvailable(storedTag = "2024.01.01", remoteTag = "2024.02.01"))
    }

    @Test
    fun `update available when never downloaded`() {
        assertTrue(isAssetUpdateAvailable(storedTag = null, remoteTag = "v1"))
        assertTrue(isAssetUpdateAvailable(storedTag = "", remoteTag = "v1"))
    }

    @Test
    fun `no update when tags match after trim`() {
        assertFalse(isAssetUpdateAvailable(storedTag = "v1", remoteTag = "v1"))
        assertFalse(isAssetUpdateAvailable(storedTag = "v1", remoteTag = " v1 "))
    }

    @Test
    fun `no update when remote tag is blank`() {
        assertFalse(isAssetUpdateAvailable(storedTag = "v1", remoteTag = null))
        assertFalse(isAssetUpdateAvailable(storedTag = "v1", remoteTag = "   "))
    }

    @Test
    fun `parses tag_name from latest release json`() {
        val json = """{"tag_name":"202405010000","name":"release","draft":false}"""
        assertEquals("202405010000", parseGithubLatestReleaseTag(json))
    }

    @Test
    fun `returns null for malformed or missing tag`() {
        assertEquals(null, parseGithubLatestReleaseTag("not json"))
        assertEquals(null, parseGithubLatestReleaseTag("""{"name":"x"}"""))
        assertEquals(null, parseGithubLatestReleaseTag("""{"tag_name":"  "}"""))
    }

    @Test
    fun `plausible payload gate rejects empty and html`() {
        assertFalse(isPlausibleGeoAssetPayload(ByteArray(0)))
        assertFalse(isPlausibleGeoAssetPayload(ByteArray(10)))
        val html = "<!DOCTYPE html><html><body>blocked</body></html>".toByteArray()
        assertFalse(isPlausibleGeoAssetPayload(html))
    }

    @Test
    fun `plausible payload gate accepts a binary db blob`() {
        val blob = ByteArray(MinGeoAssetBytes + 8) { (it and 0xff).toByte() }
        blob[0] = 'S'.code.toByte()
        blob[1] = 'R'.code.toByte()
        blob[2] = 'S'.code.toByte()
        assertTrue(isPlausibleGeoAssetPayload(blob))
    }
}
