package com.poyka.ripdpi.diagnostics.export

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

data class PreparedExportTime(
    val wallTimeMs: Long,
    val elapsedTimeMs: Long,
    val bootCount: Int?,
)

fun interface PreparedExportClock {
    fun snapshot(): PreparedExportTime
}

class AndroidPreparedExportClock(
    context: Context,
) : PreparedExportClock {
    private val resolver = context.applicationContext.contentResolver

    override fun snapshot(): PreparedExportTime =
        PreparedExportTime(
            wallTimeMs = System.currentTimeMillis(),
            elapsedTimeMs = SystemClock.elapsedRealtime(),
            bootCount = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 },
        )
}

internal fun DiagnosticsExportLeaseRecord.isEligibleAt(time: PreparedExportTime): Boolean =
    time.bootCount == bootCount && time.elapsedTimeMs >= lastObservedElapsedMs &&
        time.elapsedTimeMs < Math.addExact(createdElapsedMs, DiagnosticsArchiveFormat.maxArchiveAgeMs) &&
        time.wallTimeMs >= lastObservedAt && time.wallTimeMs < expiresAt
