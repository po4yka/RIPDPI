package com.poyka.ripdpi.jni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.core.NativeGeoDatabaseValidator
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GeoDatabaseValidationInstrumentedTest {
    @Test
    fun validatesPackagedGeoParserAndClearsFailuresBeforeTheNextCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "geo-admission-${System.nanoTime()}")
        check(directory.mkdirs())
        try {
            val validator = NativeGeoDatabaseValidator()
            val country = File(directory, "country.mmdb")
            instrumentation.context.assets.open("geo/GeoIP2-Country-Test.mmdb").use { input ->
                country.outputStream().use { output -> input.copyTo(output) }
            }
            val geosite = File(directory, "geosite.dat").apply { writeBytes(tinyGeosite) }
            val extended =
                File(directory, "extended-geosite.dat").apply {
                    writeBytes(byteArrayOf(0x18, 1) + tinyGeosite)
                }

            validator.validate(country.absolutePath, isGeosite = false)
            validator.validate(geosite.absolutePath, isGeosite = true)
            validator.validate(extended.absolutePath, isGeosite = true)
            assertThrows(IllegalArgumentException::class.java) {
                validator.validate(geosite.absolutePath, isGeosite = false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                validator.validate(country.absolutePath, isGeosite = true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                validator.validate(File(directory, "missing.db").absolutePath, isGeosite = false)
            }
            val malformed = File(directory, "malformed.db").apply { writeBytes(byteArrayOf(0, 1)) }
            assertThrows(IllegalArgumentException::class.java) {
                validator.validate(malformed.absolutePath, isGeosite = true)
            }
            validator.validate(geosite.absolutePath, isGeosite = true)
            validator.validate(country.absolutePath, isGeosite = false)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        // GeoSiteList: US category with one Full domain "x"; smaller than the old 64-byte gate.
        val tinyGeosite = byteArrayOf(10, 11, 10, 2, 85, 83, 18, 5, 8, 3, 18, 1, 120)
    }
}
