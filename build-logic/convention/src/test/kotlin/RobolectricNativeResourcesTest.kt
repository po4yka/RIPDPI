import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobolectricNativeResourcesTest {
    @Test
    fun `font data bytes remain unchanged and native binaries stay in the original jar`() {
        withDirectory { directory ->
            val font = byteArrayOf(0, 12, -1, 37)
            val hyphen = byteArrayOf(54, 0, 19, -128)
            val jar =
                writeJar(
                    directory,
                    mapOf(
                        "fonts/font.ttf" to font,
                        "hyphen-data/hyph-af.hyb" to hyphen,
                        "native/linux/x86_64/librobolectric.so" to byteArrayOf(1, 2, 3),
                        "icu/icudt75l.dat" to byteArrayOf(4, 5, 6),
                    ),
                )
            val output = directory.resolve("output")

            extractRobolectricNativeResources(jar, output)

            assertContentEquals(font, output.resolve("fonts/font.ttf").readBytes())
            assertContentEquals(hyphen, output.resolve("hyphen-data/hyph-af.hyb").readBytes())
            assertFalse(output.resolve("native").exists())
            assertFalse(output.resolve("icu").exists())
            assertTrue(jar.isFile)
        }
    }

    @Test
    fun `missing font or hyphen data fails instead of creating an incomplete runtime`() {
        withDirectory { directory ->
            for (entry in listOf("fonts/font.ttf", "hyphen-data/hyph-af.hyb")) {
                val jar = writeJar(directory, mapOf(entry to byteArrayOf(1)))
                assertFailsWith<IllegalArgumentException> {
                    extractRobolectricNativeResources(jar, directory.resolve("output"))
                }
            }
        }
    }

    @Test
    fun `resource paths cannot escape the selected data directory`() {
        withDirectory { directory ->
            val jar = writeJar(directory, mapOf("fonts/../../escape" to byteArrayOf(1)))
            assertFailsWith<IllegalArgumentException> {
                extractRobolectricNativeResources(jar, directory.resolve("output"))
            }
            assertFalse(directory.resolve("escape").exists())
        }
    }

    private fun writeJar(
        directory: File,
        entries: Map<String, ByteArray>,
    ): File =
        directory.resolve("runtime.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { archive ->
                entries.forEach { (path, bytes) ->
                    archive.putNextEntry(ZipEntry(path))
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("robolectric-native-resources").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
