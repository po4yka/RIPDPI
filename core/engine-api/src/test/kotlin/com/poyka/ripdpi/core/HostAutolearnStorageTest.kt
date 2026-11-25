package com.poyka.ripdpi.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class HostAutolearnStorageTest {
    @Test
    fun `pending erase deletes the store an engine rewrote after the erase`() {
        val dir = createTempDirectory("autolearn").toFile()
        try {
            val store = File(dir, "host-autolearn-v2.json").apply { writeText("{}") }

            assertTrue(markHostAutolearnErasePending(store))
            store.delete()
            store.writeText("""{"hosts":["visited.example"]}""")

            assertTrue(applyPendingHostAutolearnErase(store))
            assertFalse(store.exists())

            store.writeText("""{"hosts":["new.example"]}""")
            assertTrue(applyPendingHostAutolearnErase(store))
            assertTrue(store.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `marker that cannot be created reports failure instead of throwing`() {
        val dir = createTempDirectory("autolearn").toFile()
        try {
            val store = File(dir, "missing/host-autolearn-v2.json")

            assertFalse(markHostAutolearnErasePending(store))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed delete keeps the erase pending`() {
        val dir = createTempDirectory("autolearn").toFile()
        try {
            val store = File(dir, "host-autolearn-v2.json").apply { mkdirs() }
            File(store, "child").writeText("x")
            assertTrue(markHostAutolearnErasePending(store))

            assertFalse(applyPendingHostAutolearnErase(store))
            File(store, "child").delete()

            assertTrue(applyPendingHostAutolearnErase(store))
            assertFalse(store.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
