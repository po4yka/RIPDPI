package com.poyka.ripdpi.services

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class RemoteAcceptanceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ActionBeginRun) return

        val pendingResult = goAsync()
        thread(name = "remote-acceptance-debug", isDaemon = true) {
            val extras = Bundle()
            extras.putInt(ExtraProcessPid, Process.myPid())
            extras.putInt(ExtraProcessUid, Process.myUid())
            val resultCode =
                runCatching {
                    val runGeneration =
                        requireNotNull(intent.getStringExtra(ExtraRunGeneration)) {
                            "Missing $ExtraRunGeneration extra"
                        }
                    val observedAtMillis = intent.getLongExtra(ExtraObservedAtMillis, System.currentTimeMillis())
                    val evidenceWriter =
                        EntryPointAccessors
                            .fromApplication(
                                context.applicationContext,
                                RemoteAcceptanceDebugEntryPoint::class.java,
                            ).remoteDeviceAcceptanceEvidenceWriter()
                    runBlocking {
                        evidenceWriter.beginRun(runGeneration, observedAtMillis)
                    }
                    extras.putBoolean(ExtraOk, true)
                    extras.putString(ExtraPendingGenerationKey, RemoteAcceptancePendingGenerationKey)
                    Activity.RESULT_OK
                }.getOrElse { error ->
                    extras.putBoolean(ExtraOk, false)
                    extras.putString(ExtraErrorClass, error::class.java.name)
                    extras.putString(ExtraErrorMessage, error.message)
                    Activity.RESULT_CANCELED
                }

            pendingResult.resultCode = resultCode
            pendingResult.setResultExtras(extras)
            pendingResult.finish()
        }
    }

    companion object {
        const val ActionBeginRun = "com.poyka.ripdpi.debug.REMOTE_ACCEPTANCE_BEGIN_RUN"
        const val ExtraRunGeneration = "run_generation"
        const val ExtraObservedAtMillis = "observed_at_millis"
        const val ExtraPendingGenerationKey = "pending_generation_key"
        const val ExtraOk = "ok"
        const val ExtraProcessPid = "process_pid"
        const val ExtraProcessUid = "process_uid"
        const val ExtraErrorClass = "error_class"
        const val ExtraErrorMessage = "error_message"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RemoteAcceptanceDebugEntryPoint {
    fun remoteDeviceAcceptanceEvidenceWriter(): RemoteDeviceAcceptanceEvidenceWriter
}
