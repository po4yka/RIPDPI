package com.poyka.ripdpi.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppExperienceContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `merged system branding distinguishes full and quick connect experiences`() {
        val isSimple = BuildConfig.APP_EXPERIENCE == "simple"
        val expectedName = if (isSimple) "RIPDPI Quick Connect" else "RIPDPI"
        val expectedConnected =
            if (isSimple) {
                "RIPDPI Quick Connect: Connected"
            } else {
                "RIPDPI: Connected"
            }
        val notificationTitleId =
            context.resources.getIdentifier("notification_title", "string", context.packageName)

        assertEquals(expectedName, context.applicationInfo.loadLabel(context.packageManager).toString())
        assertTrue("Merged notification title resource must exist", notificationTitleId != 0)
        assertEquals(expectedName, context.getString(notificationTitleId))
        assertEquals(expectedName, context.getString(R.string.qs_tile_label_disconnected))
        assertEquals(expectedConnected, context.getString(R.string.qs_tile_label_connected))
    }

    @Test
    fun `only full experience publishes static navigation shortcuts`() {
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                PackageManager.GET_META_DATA,
            )
        val publishesShortcuts = activityInfo.metaData?.containsKey("android.app.shortcuts") == true

        assertEquals(BuildConfig.APP_EXPERIENCE == "full", publishesShortcuts)
    }
}
