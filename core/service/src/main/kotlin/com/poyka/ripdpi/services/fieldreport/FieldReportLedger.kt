package com.poyka.ripdpi.services.fieldreport

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pending hourly aggregates and the upload history, in one app-private file outside backups. Every write
 * re-checks the consent under the lock, so a write that races a revocation deletes the file instead of keeping it.
 */
@Singleton
class FieldReportLedger(
    private val file: File,
    private val consent: suspend () -> Boolean,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: AppSettingsRepository,
    ) : this(
        File(context.noBackupFilesDir, "field_report_pending.json"),
        { settings.snapshot().fieldReportEnabled },
        System::currentTimeMillis,
    )

    private val log = Logger.withTag("field-report")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Counts one member probe of [profile] while consent is on; a probe that never reached the server is ignored. */
    suspend fun recordProbe(
        profile: ProxyProfile,
        measured: CandidateRelayMeasurement?,
        elapsedMillis: Long,
    ) {
        val attempt = fieldReportAttempt(profile, measured, elapsedMillis) ?: return
        update { state -> state.record(attempt, clock()) }
    }

    /** Deletes every pending aggregate and the upload history. */
    suspend fun clear() =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (file.exists() && !file.delete()) log.w { "field report store delete failed" }
            }
        }

    internal suspend fun snapshot(): FieldReportState =
        mutex.withLock {
            withContext(Dispatchers.IO) { read().pruned(clock()) }
        }

    internal suspend fun update(transform: (FieldReportState) -> FieldReportState) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (consent()) write(transform(read()).pruned(clock())) else file.delete()
                } catch (error: IOException) {
                    // Losing an aggregate only lowers the counts; it must never fail the probe or the upload run.
                    log.w { "field report store write failed: ${error.javaClass.simpleName}" }
                }
            }
        }

    private fun read(): FieldReportState =
        try {
            if (file.exists()) {
                json.decodeFromString(
                    FieldReportState.serializer(),
                    file.readText(),
                )
            } else {
                FieldReportState()
            }
        } catch (_: IllegalArgumentException) {
            FieldReportState()
        } catch (_: IOException) {
            FieldReportState()
        }

    private fun write(state: FieldReportState) {
        if (state.hours.isEmpty() && state.uploads.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(FieldReportState.serializer(), state).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("rename failed")
    }
}
