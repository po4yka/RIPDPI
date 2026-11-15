package com.poyka.ripdpi.core.detection.vpn

import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.VpnService
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.checker.DirectSignsChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstalledVpnAppDetectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `catalog and service produce one observation even with an unreadable split`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val base = temporaryFolder.newFile()
            ZipOutputStream(base.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libxray.so"))
                zip.closeEntry()
            }
            val split = temporaryFolder.newFile().apply { writeText("unreadable split") }
            val name = "com.wireguard.android"
            val appInfo =
                ApplicationInfo().apply {
                    packageName = name
                    publicSourceDir = base.absolutePath
                    splitPublicSourceDirs = arrayOf(split.absolutePath)
                }
            val service =
                ServiceInfo().apply {
                    this.name = "$name.ExampleService"
                    packageName = name
                    applicationInfo = appInfo
                    permission = Manifest.permission.BIND_VPN_SERVICE
                    enabled = true
                    exported = true
                }
            shadowOf(context.packageManager).apply {
                installPackage(
                    PackageInfo().apply {
                        packageName = name
                        applicationInfo = appInfo
                        services = arrayOf(service)
                    },
                )
                addResolveInfoForIntent(
                    Intent(VpnService.SERVICE_INTERFACE),
                    ResolveInfo().apply { serviceInfo = service },
                )
            }

            val result = InstalledVpnAppDetector.detect(context, context.packageName)

            assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
            val match = result.matchedApps.single()
            assertEquals(name, match.packageName)
            assertFalse(match.active)
            assertEquals(EvidenceSource.VPN_SERVICE_DECLARATION, result.evidence.single().source)
            assertEquals(listOf(service.name), match.technicalMetadata?.serviceNames)
            assertEquals("base APK: lib/arm64-v8a/libxray.so", match.technicalMetadata?.apkLibraryNameHint)
        }

    @Test
    fun `no visible VPN match is a completed scoped result`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val result = InstalledVpnAppDetector.detect(context, context.packageName)

            assertEquals(CheckExecutionStatus.COMPLETED, result.executionStatus)
            assertTrue(result.matchedApps.isEmpty())
            assertEquals(
                "No VPN app matches among packages visible to this app",
                result.findings.single().description,
            )
        }

    @Test
    fun `package manager failure preserves observed VPN transport`() =
        runTest {
            val application = RuntimeEnvironment.getApplication()
            val manager = requireNotNull(application.getSystemService(ConnectivityManager::class.java))
            val connectivity = shadowOf(manager)
            connectivity.setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_VPN,
                    0,
                    true,
                    true,
                ),
            )
            val vpn = requireNotNull(manager.activeNetwork)
            val caps = NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN) }
            connectivity.setNetworkCapabilities(vpn, caps)
            val context =
                object : ContextWrapper(application) {
                    override fun getPackageManager(): PackageManager = throw SecurityException("Inventory unavailable")
                }

            val result = DirectSignsChecker.check(context)

            assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
            assertTrue(result.detected)
            assertEquals(1, result.evidence.count { it.source == EvidenceSource.NETWORK_CAPABILITIES && it.detected })
            assertTrue(result.findings.any { "inventory is unavailable" in it.description })
        }

    @Test
    fun `cancellation during package manager access propagates`() =
        runTest {
            val cancelled = CancellationException("Detection stopped")
            val context =
                object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getPackageManager(): PackageManager = throw cancelled
                }

            val failure = runCatching { InstalledVpnAppDetector.detect(context) }.exceptionOrNull()

            assertSame(cancelled, failure)
        }
}
