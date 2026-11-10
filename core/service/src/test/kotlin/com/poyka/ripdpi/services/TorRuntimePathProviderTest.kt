package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorRuntimePathProviderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `profile directories stay distinct bounded and inside their root`() {
        val root = temporaryFolder.root
        val provider = LocalTorRuntimePathProvider(root)
        val ids = listOf("work/home", "work_home", ".", "..", "", "тест", "\uD800", "\uD801", "a".repeat(400))
        val paths = ids.map { provider.pathsFor(it) }
        assertEquals(ids.size, paths.map { File(it.stateDir).canonicalPath }.toSet().size)
        paths.forEach { path ->
            assertTrue(File(path.stateDir).canonicalPath.startsWith(root.canonicalPath + File.separator))
            assertTrue(File(path.stateDir).parentFile.name.length <= 200)
            assertNotEquals(File(path.stateDir).canonicalPath, File(path.cacheDir).canonicalPath)
        }
        ids.zip(paths).forEach { (id, path) -> assertEquals(path, provider.pathsFor(id)) }
    }

    @Test
    fun `safe legacy guards remain available without copying ambiguous state`() {
        val root = temporaryFolder.root
        val marker = File(root, "work_home/state/guards.json")
        marker.parentFile.mkdirs()
        marker.writeText("existing guards")
        val provider = LocalTorRuntimePathProvider(root)
        assertEquals(marker.parentFile.canonicalPath, File(provider.pathsFor("work_home").stateDir).canonicalPath)
        assertEquals("existing guards", marker.readText())
        assertFalse(File(provider.pathsFor("work/home").stateDir, "guards.json").exists())
        assertEquals("existing guards", marker.readText())
    }
}
