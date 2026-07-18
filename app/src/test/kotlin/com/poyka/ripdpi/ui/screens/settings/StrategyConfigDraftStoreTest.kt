package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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

            val restored = requireNotNull(store.restore(sessionId))
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

            assertNull(store.restore(corruptId))
            assertNull(store.restore(wrongSchemaId))
            assertNull(store.restore(oversizedId))
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

            assertNull(store.restore(sessionId))
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

            assertNull(store.restore(missingId))
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

            assertNull(store.restore(expiredId))
            assertNull(store.restore(futureId))
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

            val restored = requireNotNull(store.restore(sessionId))

            assertEquals(complete.baseline, restored.baseline)
            assertEquals(complete.draft, restored.draft)
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
}
