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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

        assertEquals("1.2.3", metadata?.versionName)
        assertNull(metadata?.apkLibraryNameHint)
    }

    @Test
    fun `split library filename stays a hint with archive provenance`() {
        val base = apk("assets/v2ray-readme.txt")
        val split = apk("lib/arm64-v8a/libv2ray.so")

        assertEquals("split APK: lib/arm64-v8a/libv2ray.so", scan(base, split)?.apkLibraryNameHint)
    }

    private fun scan(
        base: File,
        split: File,
    ) = RuntimeEnvironment.getApplication().let { context ->
        val name = "com.example.apkinspection"
        val appInfo =
            ApplicationInfo().apply {
                packageName = name
                publicSourceDir = base.absolutePath
                splitPublicSourceDirs = arrayOf(split.absolutePath)
            }
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = name
                versionName = "1.2.3"
                applicationInfo = appInfo
            },
        )
        VpnAppMetadataScanner.scan(context, name)
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
