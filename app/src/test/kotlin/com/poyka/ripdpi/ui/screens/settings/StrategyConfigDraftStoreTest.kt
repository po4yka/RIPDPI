package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class StrategyConfigDraftStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory: File
        get() = File(context.noBackupFilesDir, StrategyConfigDraftDirectoryName)
    private val store by lazy { FileStrategyConfigDraftStore(context) }

    @Before
    fun setUp() {
        directory.deleteRecursively()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `maximum UTF-8 draft round trips baseline and draft atomically`() =
        runTest {
            val sessionId = newStrategyConfigSessionId()
            val maximumUtf8 = "🙂".repeat(StrategyConfigMaxImportBytes / 4)
            val baseline = draft(configText = maximumUtf8, luaPath = "b.lua", luaFunction = "base")
            val draft =
                draft(
                    source = StrategyConfigSource.LuaScript,
                    configText = maximumUtf8,
                    luaPath = "драфт.lua",
                    luaFunction = "маршрут",
                )

            store.persist(sessionId, StrategyConfigEditorSession(baseline = baseline, draft = draft))

            val restored = store.restore(sessionId).requireRestored()
            assertEquals(baseline, restored.baseline)
            assertEquals(draft, restored.draft)
            assertFalse(restored.isSaving)
        }

    @Test
    fun `corrupt wrong-schema and oversized records fail closed and are deleted`() =
        runTest {
            val corruptId = newStrategyConfigSessionId()
            val wrongSchemaId = newStrategyConfigSessionId()
            val oversizedId = newStrategyConfigSessionId()
            val now = System.currentTimeMillis()
            directory.mkdirs()
            fileFor(corruptId).writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            fileFor(wrongSchemaId).writeText(persistedRecordJson(schemaVersion = 999, savedAt = now))
            fileFor(oversizedId).writeBytes(ByteArray(StrategyConfigMaxRecordBytes + 1) { 'x'.code.toByte() })

            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(corruptId))
            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(wrongSchemaId))
            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(oversizedId))
            assertFalse(fileFor(corruptId).exists())
            assertFalse(fileFor(wrongSchemaId).exists())
            assertFalse(fileFor(oversizedId).exists())
        }

    @Test
    fun `oversized text field fails closed even when record is below total size bound`() =
        runTest {
            val sessionId = newStrategyConfigSessionId()
            val now = System.currentTimeMillis()
            val oversizedPath = "p".repeat(StrategyConfigMaxLuaPathBytes + 1)
            directory.mkdirs()
            fileFor(sessionId).writeText(
                """
                {
                  "schemaVersion": 1,
                  "savedAtEpochMillis": $now,
                  "baseline": {
                    "source": "BuiltIn",
                    "configText": "tcp: split",
                    "luaPath": "",
                    "luaFunction": ""
                  },
                  "draft": {
                    "source": "LuaScript",
                    "configText": "",
                    "luaPath": "$oversizedPath",
                    "luaFunction": "route"
                  }
                }
                """.trimIndent(),
            )

            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(sessionId))
            assertFalse(fileFor(sessionId).exists())
        }

    @Test
    fun `restore removes stale sibling draft files`() =
        runTest {
            val staleId = newStrategyConfigSessionId()
            val missingId = newStrategyConfigSessionId()
            directory.mkdirs()
            fileFor(staleId).writeText("stale")
            fileFor(staleId).setLastModified(System.currentTimeMillis() - StrategyConfigDraftTtlMillis - 1L)

            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(missingId))
            assertFalse(fileFor(staleId).exists())
        }

    @Test
    fun `serialized expired and future records fail closed even with fresh file timestamps`() =
        runTest {
            val now = System.currentTimeMillis()
            val expiredId = newStrategyConfigSessionId()
            val futureId = newStrategyConfigSessionId()
            directory.mkdirs()
            fileFor(expiredId).writeText(
                persistedRecordJson(savedAt = now - StrategyConfigDraftTtlMillis - 1L),
            )
            fileFor(futureId).writeText(persistedRecordJson(savedAt = now + 10L * 60L * 1000L))
            fileFor(expiredId).setLastModified(now)
            fileFor(futureId).setLastModified(now)

            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(expiredId))
            assertEquals(StrategyConfigDraftRestoreResult.MissingOrCorrupt, store.restore(futureId))
            assertFalse(fileFor(expiredId).exists())
            assertFalse(fileFor(futureId).exists())
        }

    @Test
    fun `interrupted atomic write restores the last complete draft`() =
        runTest {
            val sessionId = newStrategyConfigSessionId()
            val baseline = draft(configText = "tcp: split", luaPath = "", luaFunction = "")
            val complete =
                StrategyConfigEditorSession(
                    baseline = baseline,
                    draft = baseline.copy(configText = "tcp: complete"),
                )
            store.persist(sessionId, complete)
            val baseFile = fileFor(sessionId)
            val backupFile = File(baseFile.path + ".bak")
            check(baseFile.renameTo(backupFile))
            baseFile.writeText("partial")

            val restored = store.restore(sessionId).requireRestored()

            assertEquals(complete.baseline, restored.baseline)
            assertEquals(complete.draft, restored.draft)
        }

    @Test
    fun `directory path occupied by a file fails persist without swallowing the error`() =
        runTest {
            directory.parentFile?.mkdirs()
            directory.writeText("not a directory")
            val session = dirtySession("tcp: new")

            val failure = runCatching { store.persist(newStrategyConfigSessionId(), session) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("not a directory", directory.readText())
        }

    @Test
    fun `atomic start write and finish failures preserve the previous complete record`() =
        runTest {
            AtomicPersistFailure.entries.forEach { failurePoint ->
                directory.deleteRecursively()
                val sessionId = newStrategyConfigSessionId()
                val complete = dirtySession("tcp: complete")
                val replacement = dirtySession("tcp: replacement")
                store.persist(sessionId, complete)
                val failingStore =
                    FileStrategyConfigDraftStore(directory) { file ->
                        FailingStrategyConfigAtomicFile(file, failurePoint)
                    }

                val failure = runCatching { failingStore.persist(sessionId, replacement) }.exceptionOrNull()

                assertTrue("$failurePoint must be reported", failure is IOException)
                val restored = store.restore(sessionId).requireRestored()
                assertEquals("$failurePoint must keep the old record", complete.draft, restored.draft)
            }
        }

    @Test
    fun `transient open failure preserves the previous complete record`() =
        runTest {
            val sessionId = newStrategyConfigSessionId()
            val complete = dirtySession("tcp: complete")
            store.persist(sessionId, complete)
            val failingStore =
                FileStrategyConfigDraftStore(directory) { file ->
                    OpenReadFailingStrategyConfigAtomicFile(file)
                }

            val result = failingStore.restore(sessionId)

            assertTrue(result is StrategyConfigDraftRestoreResult.RestoreFailed)
            assertTrue(fileFor(sessionId).exists())
            assertEquals(complete.draft, store.restore(sessionId).requireRestored().draft)
        }

    private fun fileFor(sessionId: String): File = File(directory, sessionId + StrategyConfigDraftFileSuffix)

    private fun persistedRecordJson(
        schemaVersion: Int = 1,
        savedAt: Long,
        luaPath: String = "",
    ): String =
        """
        {
          "schemaVersion": $schemaVersion,
          "savedAtEpochMillis": $savedAt,
          "baseline": {
            "source": "BuiltIn",
            "configText": "tcp: split",
            "luaPath": "",
            "luaFunction": ""
          },
          "draft": {
            "source": "LuaScript",
            "configText": "version: 1",
            "luaPath": "$luaPath",
            "luaFunction": "route"
          }
        }
        """.trimIndent()

    private fun draft(
        source: StrategyConfigSource = StrategyConfigSource.BuiltIn,
        configText: String,
        luaPath: String,
        luaFunction: String,
    ): StrategyConfigDraft =
        StrategyConfigDraft(
            source = source,
            configText = configText,
            luaPath = luaPath,
            luaFunction = luaFunction,
        )

    private fun dirtySession(configText: String): StrategyConfigEditorSession {
        val baseline = draft(configText = "tcp: split", luaPath = "", luaFunction = "")
        return StrategyConfigEditorSession(
            baseline = baseline,
            draft = baseline.copy(configText = configText),
        )
    }
}

private fun StrategyConfigDraftRestoreResult.requireRestored(): StrategyConfigEditorSession =
    (this as StrategyConfigDraftRestoreResult.Restored).session

private enum class AtomicPersistFailure {
    StartWrite,
    Write,
    FinishWrite,
}

private class FailingStrategyConfigAtomicFile(
    file: File,
    private val failurePoint: AtomicPersistFailure,
) : StrategyConfigAtomicFile {
    private val delegate = AndroidStrategyConfigAtomicFile(file)
    private var delegateOutput: OutputStream? = null

    override fun openRead(): InputStream = delegate.openRead()

    override fun startWrite(): OutputStream {
        if (failurePoint == AtomicPersistFailure.StartWrite) throw IOException("startWrite failed")
        val output = delegate.startWrite().also { delegateOutput = it }
        return if (failurePoint == AtomicPersistFailure.Write) {
            object : FilterOutputStream(output) {
                override fun write(value: Int) = throw IOException("write failed")

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = throw IOException("write failed")
            }
        } else {
            output
        }
    }

    override fun finishWrite(output: OutputStream) {
        if (failurePoint == AtomicPersistFailure.FinishWrite) throw IOException("finishWrite failed")
        delegate.finishWrite(requireNotNull(delegateOutput))
    }

    override fun failWrite(output: OutputStream) {
        delegate.failWrite(requireNotNull(delegateOutput))
    }

    override fun delete() = delegate.delete()
}

private class OpenReadFailingStrategyConfigAtomicFile(
    file: File,
) : StrategyConfigAtomicFile {
    private val delegate = AndroidStrategyConfigAtomicFile(file)

    override fun openRead(): InputStream = throw IOException("openRead failed")

    override fun startWrite(): OutputStream = delegate.startWrite()

    override fun finishWrite(output: OutputStream) = delegate.finishWrite(output)

    override fun failWrite(output: OutputStream) = delegate.failWrite(output)

    override fun delete() = delegate.delete()
}
