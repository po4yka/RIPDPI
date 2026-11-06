package com.poyka.ripdpi.data.backup

import android.util.Log
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.persistence.ProfileDatabase
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

private const val LogTag = "BackupExport"

/**
 * Outcome of a backup export.
 *
 * The byte count is the size of the archive written to the destination; it is the
 * only payload-derived value that may be logged or shown to the user.
 */
sealed interface BackupExportResult {
    /** The export succeeded and [byteCount] archive bytes were streamed to the destination. */
    data class Success(
        val variant: BackupVariant,
        val byteCount: Long,
    ) : BackupExportResult

    /** The destination stream could not be written (typed I/O failure). */
    data class WriteFailed(
        val variant: BackupVariant,
        val cause: Throwable,
    ) : BackupExportResult
}

/**
 * Gathers all backup-eligible data (profiles, groups, rules, settings) and streams
 * a [BackupV1] document to a caller-provided [OutputStream] (the SAF destination).
 *
 * The use case never materializes the encoded archive in memory: serialization is
 * streamed straight to [output] via [BackupSerializer.encodeToStream]. It also
 * never logs the payload — only the variant and the byte count on success.
 *
 * Redaction (SHARE vs FULL) is delegated to [BackupExporter] / [BackupAllowlist];
 * this class only assembles inputs and drives the writer.
 */
class BackupExportUseCase
    @Inject
    internal constructor(
        private val database: ProfileDatabase,
        private val dispatchers: AppCoroutineDispatchers,
    ) {
        /**
         * Builds and writes a backup of [variant] to [output].
         *
         * @param output destination stream (owned by the caller — NOT closed here).
         * @param appVersion the running app version, recorded in the document header.
         * @param createdAtEpochMillis timestamp recorded in the document header.
         * @return [BackupExportResult.Success] with the byte count, or
         *   [BackupExportResult.WriteFailed] if the destination write failed.
         */
        suspend fun export(
            variant: BackupVariant,
            output: OutputStream,
            appVersion: String,
            passphrase: CharArray? = null,
            createdAtEpochMillis: Long = System.currentTimeMillis(),
        ): BackupExportResult =
            // PBKDF2, Keystore reads and the SAF stream write all block; callers launch from Main.
            withContext(dispatchers.io) {
                exportBlocking(variant, output, appVersion, passphrase, createdAtEpochMillis)
            }

        private suspend fun exportBlocking(
            variant: BackupVariant,
            output: OutputStream,
            appVersion: String,
            passphrase: CharArray?,
            createdAtEpochMillis: Long,
        ): BackupExportResult {
            val document = gather(variant, appVersion, createdAtEpochMillis)
            return try {
                val byteCount =
                    when (variant) {
                        BackupVariant.SHARE -> {
                            BackupSerializer.encodeToStream(document, output)
                        }

                        BackupVariant.FULL -> {
                            BackupEncryption.encryptToStream(
                                document = document,
                                passphrase = requireNotNull(passphrase) { "FULL backup passphrase is required" },
                                output = output,
                            )
                        }
                    }
                Log.i(LogTag, "Backup export complete: variant=$variant bytes=$byteCount")
                BackupExportResult.Success(variant = variant, byteCount = byteCount)
            } catch (e: IOException) {
                // Never log the payload; the variant is the only contextual detail.
                Log.w(LogTag, "Backup export write failed: variant=$variant", e)
                BackupExportResult.WriteFailed(variant = variant, cause = e)
            }
        }

        /**
         * Assembles the [BackupV1] document for [variant] without writing it.
         * Exposed for unit-testing the gather step independently of the I/O path.
         */
        suspend fun gather(
            variant: BackupVariant,
            appVersion: String,
            createdAtEpochMillis: Long = System.currentTimeMillis(),
        ): BackupV1 {
            val snapshot =
                database.read { rows ->
                    BackupSourceSnapshot(
                        groups = rows.groups.list(),
                        rules = rows.rules.all(),
                        settings = BackupSettingsConverter.toMap(rows.settings.snapshot(), variant),
                        privateData = if (variant == BackupVariant.FULL) rows.privateBackupSnapshot() else null,
                    )
                }
            return BackupExporter.export(
                variant = variant,
                profiles = snapshot.groups.flatMap { it.members },
                groups = snapshot.groups,
                rules = snapshot.rules,
                settings = snapshot.settings,
                createdAtEpochMillis = createdAtEpochMillis,
                appVersion = appVersion,
                privateData = snapshot.privateData,
            )
        }
    }

private data class BackupSourceSnapshot(
    val groups: List<com.poyka.ripdpi.data.ProxyGroup>,
    val rules: List<com.poyka.ripdpi.data.rules.RuleEntity>,
    val settings: Map<String, String>,
    val privateData: BackupPrivateDataV1?,
)
