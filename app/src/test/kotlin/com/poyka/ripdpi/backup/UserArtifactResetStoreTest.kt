package com.poyka.ripdpi.backup

import android.content.Context
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserArtifactResetStoreTest {
    @Test
    fun `reset deletes user files and preference stores`() {
        val context = RuntimeEnvironment.getApplication()
        val keylog =
            context.filesDir.resolve("diagnostics/tls.keys").apply {
                parentFile?.mkdirs()
                writeText("secret")
            }
        listOf(
            "probe_result_cache.json",
            "crash-reports/crash_latest.json",
            "logs/app_log.txt",
            "native_panics/panic.txt",
        ).forEach { path ->
            context.filesDir.resolve(path).apply {
                parentFile?.mkdirs()
                writeText("user-data")
            }
        }
        val preferences = listOf("pin_lockout", "app_lock", "detection_check_prefs", "backup_share_prefs")
        preferences.forEach { name ->
            context
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .putString("seed", "value")
                .commit()
        }

        DefaultUserArtifactResetStore(context).clearAll(
            AppSettings
                .getDefaultInstance()
                .toBuilder()
                .setDetectionDiagnosticTlsKeylogPath(
                    keylog.absolutePath,
                ).build(),
        )

        assertFalse(keylog.exists())
        listOf("probe_result_cache.json", "crash-reports", "logs", "native_panics").forEach { path ->
            assertFalse(context.filesDir.resolve(path).exists())
        }
        preferences.forEach { name ->
            assertFalse(context.getSharedPreferences(name, Context.MODE_PRIVATE).contains("seed"))
        }
    }
}
