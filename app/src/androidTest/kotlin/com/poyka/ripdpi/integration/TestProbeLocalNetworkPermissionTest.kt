package com.poyka.ripdpi.integration

import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.LocalNetworkPermissionApi
import com.poyka.ripdpi.e2e.ensureLocalNetworkAccessGranted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestProbeLocalNetworkPermissionTest {
    @Test
    fun distinctProbePackageDeclaresAndReceivesLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < LocalNetworkPermissionApi) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val probe = instrumentation.context
        val target = instrumentation.targetContext
        val info = probe.packageManager.getPackageInfo(probe.packageName, PackageManager.GET_PERMISSIONS)
        assertNotEquals(target.applicationInfo.uid, info.applicationInfo!!.uid)
        assertTrue(info.requestedPermissions.orEmpty().contains(LocalNetworkPermission))

        ensureLocalNetworkAccessGranted(target)
        val revoke =
            instrumentation.uiAutomation.executeShellCommand("pm revoke ${probe.packageName} $LocalNetworkPermission")
        ParcelFileDescriptor.AutoCloseInputStream(revoke).bufferedReader().use { it.readText() }
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            probe.packageManager.checkPermission(LocalNetworkPermission, probe.packageName),
        )

        ensureLocalNetworkAccessGranted(probe)

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            probe.packageManager.checkPermission(LocalNetworkPermission, probe.packageName),
        )
    }
}
