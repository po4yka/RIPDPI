package com.poyka.ripdpi.ui.screens.detection

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetectionCheckPlatform
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        val packageName: String
            get() = context.packageName

        fun isPermissionGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        suspend fun runDetectionCheck(
            runner: DetectionCheckRunner,
            config: DetectionRunnerConfig,
            onProgress: (DetectionProgress) -> Unit,
        ): DetectionCheckResult =
            runner.run(
                context = context,
                config = config,
                onProgress = onProgress,
            )
    }
