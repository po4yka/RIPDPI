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
            // A running engine flushes its in-memory hosts at shutdown, or a SIGKILLed process
            // leaves a debounced rewrite behind.
            store.writeText("""{"hosts":["visited.example"]}""")

            assertTrue(applyPendingHostAutolearnErase(store))
            assertFalse(store.exists())

            // The erase is applied once: hosts the next engine learns are kept.
            store.writeText("""{"hosts":["new.example"]}""")
            assertTrue(applyPendingHostAutolearnErase(store))
            assertTrue(store.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed delete keeps the erase pending`() {
        val dir = createTempDirectory("autolearn").toFile()
        try {
            // A non-empty directory in place of the store cannot be deleted.
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
