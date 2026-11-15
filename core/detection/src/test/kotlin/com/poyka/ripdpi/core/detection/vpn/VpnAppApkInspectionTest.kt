package com.poyka.ripdpi.core.detection.vpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnAppApkInspectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `documentation and directory names do not identify a library`() {
        val base = apk("assets/xray-readme.txt", "lib/arm64-v8a/libclash.so/", "assets/build/go1.99")
        val split = apk("lib/arm64-v8a/libexample.so")
        val metadata = scan(base, split)

        assertEquals("1.2.3", metadata.versionName)
        assertNull(metadata.apkLibraryNameHint)
    }

    @Test
    fun `split library filename stays a hint with archive provenance`() {
        val base = apk("assets/v2ray-readme.txt")
        val split = apk("lib/arm64-v8a/libv2ray.so")

        assertEquals("split APK: lib/arm64-v8a/libv2ray.so", scan(base, split).apkLibraryNameHint)
    }

    @Test
    fun `an unreadable split leaves the valid base hint in an incomplete result`() {
        val base = apk("lib/arm64-v8a/libxray.so")
        val badSplit = temporaryFolder.newFile().apply { writeText("not a ZIP file") }
        val budget = VpnInventoryBudget(EmptyCoroutineContext)
        val metadata = VpnAppMetadataScanner.scan(packageInfo(base, badSplit), budget)

        assertEquals("base APK: lib/arm64-v8a/libxray.so", metadata.apkLibraryNameHint)
        assertEquals(true, budget.incomplete)
    }

    @Test
    fun `inspection stops before clues beyond the archive entry budget`() {
        val unrelated = Array(4096) { index -> "assets/resource-$index.txt" }
        val base = apk(*unrelated, "lib/arm64-v8a/libxray.so")
        val budget = VpnInventoryBudget(EmptyCoroutineContext)
        val metadata = VpnAppMetadataScanner.scan(packageInfo(base, apk()), budget)

        assertNull(metadata.apkLibraryNameHint)
        assertEquals(true, budget.incomplete)
    }

    private fun scan(
        base: File,
        split: File,
    ) = VpnAppMetadataScanner.scan(packageInfo(base, split), VpnInventoryBudget(EmptyCoroutineContext))

    private fun packageInfo(
        base: File,
        split: File,
    ): PackageInfo =
        PackageInfo().apply {
            packageName = "com.example.apkinspection"
            versionName = "1.2.3"
            applicationInfo =
                ApplicationInfo().apply {
                    publicSourceDir = base.absolutePath
                    splitPublicSourceDirs = arrayOf(split.absolutePath)
                }
        }

    private fun apk(vararg entries: String): File =
        temporaryFolder.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for (name in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.closeEntry()
                }
            }
        }
}
