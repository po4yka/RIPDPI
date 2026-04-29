package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.EnvironmentKind
import javax.inject.Inject
import javax.inject.Singleton

// Hilt-injectable singleton that classifies the host device once at
// first read (P4.4.5, ADR-011). The result is cached for the process
// lifetime because the detection inputs (Build.* properties,
// ro.kernel.qemu) cannot change without a reboot.
//
// Build-property heuristic mirrors the test-side check at
// app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/TestSupport.kt:348-361
// so production and test classify the same hardware identically.
@Singleton
class EnvironmentDetector
    @Inject
    constructor() {
        val kind: EnvironmentKind by lazy { detect() }

        private fun detect(): EnvironmentKind =
            if (isLikelyEmulator()) {
                EnvironmentKind.Emulator
            } else {
                EnvironmentKind.Field
            }

        private fun isLikelyEmulator(): Boolean {
            if (readKernelQemuFlag()) return true
            val fingerprint = Build.FINGERPRINT.lowercase()
            val hardware = Build.HARDWARE.lowercase()
            val model = Build.MODEL.lowercase()
            val product = Build.PRODUCT.lowercase()
            return "generic" in fingerprint ||
                "emulator" in fingerprint ||
                "ranchu" in hardware ||
                "goldfish" in hardware ||
                "sdk" in product ||
                "sdk_gphone" in product ||
                "emulator" in model ||
                "android sdk built for" in model
        }

        // android.os.SystemProperties is hidden API; reach it via
        // reflection. Returns false if the property is absent or the
        // reflection lookup fails -- a missing property is the common
        // case on physical devices and must not be interpreted as
        // "emulator".
        private fun readKernelQemuFlag(): Boolean =
            runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val getter = clazz.getMethod("get", String::class.java, String::class.java)
                val value = getter.invoke(null, "ro.kernel.qemu", "") as? String
                value == "1"
            }.getOrDefault(false)
    }
