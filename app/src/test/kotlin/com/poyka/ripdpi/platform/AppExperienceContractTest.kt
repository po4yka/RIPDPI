package com.poyka.ripdpi.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppExperienceContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every variant carries the standard application branding`() {
        assertEquals("RIPDPI", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("RIPDPI", context.getString(R.string.qs_tile_label_disconnected))
        assertEquals("RIPDPI: Connected", context.getString(R.string.qs_tile_label_connected))
        assertFalse(context.packageName.endsWith(".simple"))
    }

    @Test
    fun `main activity publishes static navigation shortcuts`() {
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                PackageManager.GET_META_DATA,
            )

        assertTrue(activityInfo.metaData?.containsKey("android.app.shortcuts") == true)
    }
}
