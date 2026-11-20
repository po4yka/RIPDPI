package com.poyka.ripdpi.services.fieldreport

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pending hourly aggregates and the upload history, in one app-private file outside backups. Probes count into
 * an in-memory copy that is flushed at most every [FlushIntervalMillis]; [update] and [clear] write through at once.
 * Every write re-checks the consent under the lock, so a write that races a revocation deletes the file instead of
 * keeping it. A crash loses at most one flush interval of counts, which only lowers them.
 */
@Singleton
class FieldReportLedger(
    private val file: File,
    private val consent: suspend () -> Boolean,
    private val clock: () -> Long,
    private val network: () -> FieldReportNetwork,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: AppSettingsRepository,
        networkContext: FieldReportNetworkContext,
    ) : this(
        File(context.noBackupFilesDir, "field_report_pending.json"),
        { settings.snapshot().fieldReportEnabled },
        System::currentTimeMillis,
        networkContext::current,
    )

    private val log = Logger.withTag("field-report")
    private val mutex = Mutex()

    /** The current state once read from [file]; null until the first access. Guarded by [mutex]. */
    private var cached: FieldReportState? = null
    private var flushedAt: Long? = null

    /** Bumped by every [clear]: a probe counted under an older epoch raced a revocation and is discarded. */
    private val consentEpoch = AtomicLong()

    /**
     * Counts one member probe of [profile] while consent is on, in the hour of the current network; a probe that
     * never reached the server is ignored. Without consent, nothing else runs, not even the network lookup.
     */
    suspend fun recordProbe(
        profile: ProxyProfile,
        measured: CandidateRelayMeasurement?,
        elapsedMillis: Long,
    ) {
        val attempt = fieldReportAttempt(profile, measured, elapsedMillis) ?: return
        val epoch = consentEpoch.get()
        if (!consent()) return
        val measuredOn = network()
        mutex.withLock {
            // The cheap check above ran outside the lock; a revocation may have cleared the state since.
            if (epoch != consentEpoch.get() || !consent()) return@withLock
            withContext(Dispatchers.IO) {
                val now = clock()
                cached = current().record(attempt, measuredOn, now)
                if (flushedAt.let { it == null || now - it >= FlushIntervalMillis }) persist(now)
            }
        }
    }

    /** Deletes every pending aggregate and the upload history. */
    suspend fun clear() =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                consentEpoch.incrementAndGet()
                cached = FieldReportState()
                // The first count after a new consent writes through, with its own consent check.
                flushedAt = null
                if (file.exists() && !file.delete()) log.w { "field report store delete failed" }
            }
        }

    internal suspend fun snapshot(): FieldReportState =
        mutex.withLock {
            withContext(Dispatchers.IO) { current().pruned(clock()) }
        }

    /** Applies [transform] and writes the result through at once. */
    internal suspend fun update(transform: (FieldReportState) -> FieldReportState) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                cached = transform(current())
                persist(clock())
            }
        }

    private fun current(): FieldReportState = cached ?: read().also { cached = it }

    private suspend fun persist(now: Long) {
        try {
            if (consent()) {
                val state = current().pruned(now)
                write(state)
                cached = state
            } else {
                cached = FieldReportState()
                file.delete()
            }
            flushedAt = now
        } catch (error: IOException) {
            // Losing an aggregate only lowers the counts; it must never fail the probe or the upload run.
            log.w { "field report store write failed: ${error.javaClass.simpleName}" }
        }
    }

    private fun read(): FieldReportState =
        try {
            if (file.exists()) {
                RipDpiJson.decodeFromString(
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
            output.write(RipDpiJson.encodeToString(FieldReportState.serializer(), state).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("rename failed")
    }
}

/** The longest a counted probe waits in memory before it is written to the file. */
internal const val FlushIntervalMillis = 5 * 60_000L
