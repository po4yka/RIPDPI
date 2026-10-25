package com.poyka.ripdpi.activities

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityImePolicyTest {
    @Test
    fun `main activity resizes for the keyboard instead of panning its toolbar`() {
        val context = RuntimeEnvironment.getApplication()
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                0,
            )

        assertEquals(
            "The merged manifest must keep the toolbar in place when a lower field opens the keyboard",
            SoftInputAdjustResize,
            activityInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }

    @Test
    fun `launcher aliases inherit the keyboard resize policy`() {
        val context = RuntimeEnvironment.getApplication()
        val launcherActivities =
            context.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(context.packageName),
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                ).filter { it.activityInfo.name.startsWith(MainActivity::class.java.name) }

        assertTrue("The merged manifest must expose launcher aliases", launcherActivities.isNotEmpty())
        launcherActivities.forEach { resolved ->
            val alias = resolved.activityInfo
            assertEquals(
                "Launcher ${alias.name} must target MainActivity",
                MainActivity::class.java.name,
                alias.targetActivity,
            )
            assertEquals(
                "Launcher ${alias.name} must inherit the keyboard resize policy",
                SoftInputAdjustResize,
                alias.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
            )
        }
    }
}

// The manifest still declares adjustResize; the platform constant is deprecated since API 30.
private const val SoftInputAdjustResize = 0x10
