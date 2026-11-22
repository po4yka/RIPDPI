package com.poyka.ripdpi.services.fieldreport

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val networkEpoch: DiagnosticsNetworkEpochProvider,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: AppSettingsRepository,
        networkContext: FieldReportNetworkContext,
        networkEpoch: DiagnosticsNetworkEpochProvider,
    ) : this(
        File(context.noBackupFilesDir, "field_report_pending.json"),
        { settings.snapshot().fieldReportEnabled },
        System::currentTimeMillis,
        networkContext::current,
        networkEpoch,
    )

    /** Transient evidence for one probe. Only the existing coarse network label goes into the report. */
    class ProbeContext internal constructor(
        internal val network: FieldReportNetwork,
        internal val generation: DiagnosticsNetworkEpoch,
        internal val consentEpoch: Long,
    ) {
        private val recorded = AtomicBoolean()

        internal fun markRecorded(): Boolean = recorded.compareAndSet(false, true)
    }

    private val log = Logger.withTag("field-report")
    private val mutex = Mutex()

    /** The current state once read from [file]; null until the first access. Guarded by [mutex]. */
    private var cached: FieldReportState? = null
    private var flushedAt: Long? = null

    /** Bumped by every [clear]: a probe counted under an older epoch raced a revocation and is discarded. */
    private val consentEpoch = AtomicLong()

    /** Capture the report label before the probe and fence the lookup with physical network evidence. */
    suspend fun captureProbe(): ProbeContext? {
        val epoch = consentEpoch.get()
        val generation = (if (consent() && epoch == consentEpoch.get()) networkEpoch.capture() else null) ?: return null
        val label = network()
        return if (networkEpoch.capture() == generation && consentEpoch.get() == epoch) {
            ProbeContext(label, generation, epoch)
        } else {
            null
        }
    }

    /** Count a reached probe while the evidence from its start still holds. */
    suspend fun recordProbe(
        origin: ProbeContext,
        profile: ProxyProfile,
        measured: CandidateRelayMeasurement?,
        elapsedMillis: Long,
    ) {
        val attempt = fieldReportAttempt(profile, measured, elapsedMillis) ?: return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (origin.consentEpoch != consentEpoch.get() || !consent()) return@withContext
                val now = clock()
                val next = current().record(attempt, origin.network, now)
                if (networkEpoch.capture() != origin.generation || !origin.markRecorded()) return@withContext
                cached = next
                if (flushedAt.let { it == null || now - it >= FlushIntervalMillis }) persist(next, now)
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
                deletePendingFiles()
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
                persist(transform(current()), clock())
            }
        }

    /** Removes the selected buckets and reserves the request before it can be sent. */
    internal suspend fun <T : Any> claim(transform: (FieldReportState) -> Pair<FieldReportState, T?>): Pair<Long, T>? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val now = clock()
                val before = current().pruned(now)
                val (next, request) = transform(before)
                if (before != next && persist(next, now)) request?.let { consentEpoch.get() to it } else null
            }
        }

    internal fun isClaimCurrent(epoch: Long): Boolean = consentEpoch.get() == epoch

    /** Restores a claim only when its request was never sent and its consent is still current. */
    internal suspend fun restore(
        epoch: Long,
        transform: (FieldReportState) -> FieldReportState,
    ) {
        mutex.withLock {
            if (isClaimCurrent(epoch)) {
                withContext(Dispatchers.IO) { persist(transform(current()), clock()) }
            }
        }
    }

    private fun current(): FieldReportState = cached ?: read().also { cached = it }

    private suspend fun persist(
        next: FieldReportState,
        now: Long,
    ): Boolean =
        try {
            if (consent()) {
                val state = next.pruned(now)
                write(state)
                cached = state
                flushedAt = now
                true
            } else {
                cached = FieldReportState()
                deletePendingFiles()
                false
            }
        } catch (error: IOException) {
            // Losing an aggregate only lowers the counts; it must never fail the probe or the upload run.
            log.w { "field report store write failed: ${error.javaClass.simpleName}" }
            false
        }

    private fun deletePendingFiles() {
        for (pending in listOf(file, temporary)) {
            if (pending.exists() && !pending.delete()) log.w { "field report store delete failed" }
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

    private val temporary = File(file.path + ".tmp")

    private fun write(state: FieldReportState) {
        if (state.hours.isEmpty() && state.uploads.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("delete failed")
            return
        }
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(RipDpiJson.encodeToString(FieldReportState.serializer(), state).toByteArray())
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("rename failed")
        } finally {
            temporary.delete()
        }
    }
}

/** The longest a counted probe waits in memory before it is written to the file. */
internal const val FlushIntervalMillis = 5 * 60_000L
