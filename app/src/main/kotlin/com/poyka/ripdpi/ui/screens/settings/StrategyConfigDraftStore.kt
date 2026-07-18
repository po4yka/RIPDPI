package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import android.util.AtomicFile
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val StrategyConfigDraftDirectoryName = "strategy-config-drafts"
internal const val StrategyConfigDraftFileSuffix = ".json"
internal const val StrategyConfigMaxLuaPathBytes = 4 * 1024
internal const val StrategyConfigMaxLuaFunctionBytes = 256
internal const val StrategyConfigMaxRecordBytes = 1024 * 1024
internal const val StrategyConfigDraftTtlMillis = 7L * 24L * 60L * 60L * 1000L

internal fun newStrategyConfigSessionId(): String = UUID.randomUUID().toString()

internal fun isValidStrategyConfigSessionId(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal interface StrategyConfigDraftStore {
    suspend fun restore(sessionId: String): StrategyConfigEditorSession?

    suspend fun persist(
        sessionId: String,
        session: StrategyConfigEditorSession,
    )

    suspend fun delete(sessionId: String)
}

@Singleton
internal class FileStrategyConfigDraftStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : StrategyConfigDraftStore {
        private val directory = File(context.noBackupFilesDir, StrategyConfigDraftDirectoryName)

        override suspend fun restore(sessionId: String): StrategyConfigEditorSession? =
            withContext(Dispatchers.IO) {
                if (!isValidStrategyConfigSessionId(sessionId)) return@withContext null
                val now = System.currentTimeMillis()
                cleanupStaleFiles(now)
                val file = fileFor(sessionId)
                val restored = runCatching { readRecord(AtomicFile(file), now) }.getOrNull()
                if (restored == null) AtomicFile(file).delete()
                restored?.toSession()
            }

        override suspend fun persist(
            sessionId: String,
            session: StrategyConfigEditorSession,
        ) = withContext(Dispatchers.IO) {
            if (!isValidStrategyConfigSessionId(sessionId) || !session.isValidForPersistence()) {
                if (isValidStrategyConfigSessionId(sessionId)) AtomicFile(fileFor(sessionId)).delete()
                return@withContext
            }
            directory.mkdirs()
            val record = StrategyConfigDraftRecord.from(session, System.currentTimeMillis())
            val bytes = StrategyConfigDraftJson.encodeToString(record).toByteArray(Charsets.UTF_8)
            if (bytes.size > StrategyConfigMaxRecordBytes) {
                AtomicFile(fileFor(sessionId)).delete()
                return@withContext
            }
            val atomicFile = AtomicFile(fileFor(sessionId))
            val output = runCatching { atomicFile.startWrite() }.getOrNull() ?: return@withContext
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
            } catch (_: Exception) {
                runCatching { atomicFile.failWrite(output) }
            }
        }

        override suspend fun delete(sessionId: String) =
            withContext(Dispatchers.IO) {
                if (isValidStrategyConfigSessionId(sessionId)) {
                    val file = fileFor(sessionId)
                    AtomicFile(file).delete()
                    check(!file.exists()) { "Strategy draft file could not be deleted" }
                }
            }

        private fun readRecord(
            file: AtomicFile,
            now: Long,
        ): StrategyConfigDraftRecord? =
            file
                .openRead()
                .use { it.readBounded(StrategyConfigMaxRecordBytes) }
                ?.takeIf { it.isNotEmpty() }
                ?.decodeStrictUtf8()
                ?.let { StrategyConfigDraftJson.decodeFromString<StrategyConfigDraftRecord>(it) }
                ?.takeIf { it.isValid(now) }

        private fun cleanupStaleFiles(now: Long) {
            directory.listFiles()?.forEach { file ->
                val age = now - file.lastModified()
                if (age > StrategyConfigDraftTtlMillis) AtomicFile(file).delete()
            }
        }

        private fun fileFor(sessionId: String): File = File(directory, sessionId + StrategyConfigDraftFileSuffix)
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StrategyConfigDraftStoreModule {
    @Binds
    @Singleton
    abstract fun bindStrategyConfigDraftStore(store: FileStrategyConfigDraftStore): StrategyConfigDraftStore
}

@Serializable
private data class StrategyConfigDraftRecord(
    val schemaVersion: Int,
    val savedAtEpochMillis: Long,
    val baseline: PersistedStrategyConfigDraft,
    val draft: PersistedStrategyConfigDraft,
) {
    fun isValid(now: Long): Boolean =
        schemaVersion == StrategyConfigDraftSchemaVersion &&
            savedAtEpochMillis > 0L &&
            savedAtEpochMillis <= now + StrategyConfigClockSkewToleranceMillis &&
            now - savedAtEpochMillis <= StrategyConfigDraftTtlMillis &&
            baseline.isValid() &&
            draft.isValid()

    fun toSession(): StrategyConfigEditorSession =
        StrategyConfigEditorSession(
            baseline = baseline.toDraft(),
            draft = draft.toDraft(),
        )

    companion object {
        fun from(
            session: StrategyConfigEditorSession,
            savedAtEpochMillis: Long,
        ): StrategyConfigDraftRecord =
            StrategyConfigDraftRecord(
                schemaVersion = StrategyConfigDraftSchemaVersion,
                savedAtEpochMillis = savedAtEpochMillis,
                baseline = PersistedStrategyConfigDraft.from(session.baseline),
                draft = PersistedStrategyConfigDraft.from(session.draft),
            )
    }
}

@Serializable
private data class PersistedStrategyConfigDraft(
    val source: String,
    val configText: String,
    val luaPath: String,
    val luaFunction: String,
) {
    fun isValid(): Boolean =
        source in StrategyConfigSource.entries.map { it.name } &&
            configText.utf8Size() <= StrategyConfigMaxImportBytes &&
            luaPath.utf8Size() <= StrategyConfigMaxLuaPathBytes &&
            luaFunction.utf8Size() <= StrategyConfigMaxLuaFunctionBytes

    fun toDraft(): StrategyConfigDraft =
        StrategyConfigDraft(
            source = StrategyConfigSource.valueOf(source),
            configText = configText,
            luaPath = luaPath,
            luaFunction = luaFunction,
        )

    companion object {
        fun from(draft: StrategyConfigDraft): PersistedStrategyConfigDraft =
            PersistedStrategyConfigDraft(
                source = draft.source.name,
                configText = draft.configText,
                luaPath = draft.luaPath,
                luaFunction = draft.luaFunction,
            )
    }
}

private fun StrategyConfigEditorSession.isValidForPersistence(): Boolean =
    baseline.isValidForPersistence() && draft.isValidForPersistence()

private fun StrategyConfigDraft.isValidForPersistence(): Boolean =
    configText.utf8Size() <= StrategyConfigMaxImportBytes &&
        luaPath.utf8Size() <= StrategyConfigMaxLuaPathBytes &&
        luaFunction.utf8Size() <= StrategyConfigMaxLuaFunctionBytes

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) return output.toByteArray()
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
}

private fun ByteArray.decodeStrictUtf8(): String? =
    runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

private const val StrategyConfigDraftSchemaVersion = 1
private const val StrategyConfigClockSkewToleranceMillis = 5L * 60L * 1000L
private val StrategyConfigDraftJson = RipDpiJson
