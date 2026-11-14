import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyrebirdObfs4PatchTest {
    private val patchDirectory = File(System.getProperty("user.dir"), "../../native/pluggable-transports/patches")
    private val upstreamCommit = "59ac7e400c384773f5584dbe366516ad04a33d9f"

    @Test
    fun `reviewed patch applies to upstream bytes and verifies all resulting files`() =
        withDirectory { directory ->
            val source = source()
            val patch = assertNotNull(reviewedPluggableTransportSourcePatch(source, patchDirectory))
            copyUpstreamFiles(directory)
            patch.verifyInput(directory, upstreamCommit)
            gitApply(directory, "--check", patch.file.absolutePath)
            gitApply(directory, patch.file.absolutePath)
            patch.verifyOutput(directory)
            directory.resolve("cmd/lyrebird/lyrebird.go").appendText("\n")
            assertFailsWith<IllegalArgumentException> { patch.verifyOutput(directory) }
        }

    @Test
    fun `wrong revision and modified upstream bytes fail closed`() =
        withDirectory { directory ->
            val patch = assertNotNull(reviewedPluggableTransportSourcePatch(source(), patchDirectory))
            copyUpstreamFiles(directory)
            assertFailsWith<IllegalArgumentException> { patch.verifyInput(directory, "0".repeat(40)) }
            directory.resolve("transports/transports.go").appendText("\n")
            assertFailsWith<IllegalArgumentException> { patch.verifyInput(directory, upstreamCommit) }
            assertFailsWith<IllegalArgumentException> {
                reviewedPluggableTransportSourcePatch(source().copy(commit = "0".repeat(40)), patchDirectory)
            }
        }

    @Test
    fun `changed manifest patch and unsupported output fail closed`() =
        withDirectory { directory ->
            patchDirectory.copyRecursively(directory, overwrite = true)
            val source = source()
            directory.resolve("lyrebird-obfs4-only.patch").appendText("\n")
            assertFailsWith<IllegalArgumentException> { reviewedPluggableTransportSourcePatch(source, directory) }
            assertFailsWith<IllegalArgumentException> {
                reviewedPluggableTransportSourcePatch(source.copy(sourcePatchSha256 = null), patchDirectory)
            }
            assertFailsWith<IllegalArgumentException> {
                reviewedPluggableTransportSourcePatch(source.copy(outputNames = listOf("lyrebird")), patchDirectory)
            }
            assertFailsWith<IllegalArgumentException> {
                reviewedPluggableTransportSourcePatch(source.copy(id = "snowflake"), patchDirectory)
            }
            assertNull(
                reviewedPluggableTransportSourcePatch(
                    source.copy(id = "snowflake", sourcePatchSha256 = null),
                    patchDirectory,
                ),
            )
        }

    @Test
    fun `prebuilt source and artifact receipts require the reviewed patch identity`() {
        val source = source()
        verifyPluggableTransportSourcePatchReceipt(mapOf("sourcePatchSha256" to source.sourcePatchSha256), source)
        listOf(emptyMap(), mapOf("sourcePatchSha256" to "0".repeat(64))).forEach { stale ->
            assertFailsWith<IllegalArgumentException> { verifyPluggableTransportSourcePatchReceipt(stale, source) }
        }
        verifyPluggableTransportSourcePatchReceipt(emptyMap<String, Any>(), source.copy(sourcePatchSha256 = null))
    }

    private fun source() =
        PluggableTransportSource(
            id = "lyrebird",
            sourceType = "go",
            repoUrl = "https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git",
            commit = upstreamCommit,
            goToolchain = "go1.27.4",
            goLinkerFlags = emptyList(),
            cgoEnabled = true,
            packagePath = "./cmd/lyrebird",
            packageName = null,
            sourceBinaryName = "lyrebird",
            outputNames = listOf("ripdpi-obfs4"),
            sourcePatchSha256 = sourcePatchSha256(patchDirectory.resolve("lyrebird-obfs4-only.json")),
        )

    private fun copyUpstreamFiles(directory: File) {
        for (path in listOf("transports/transports.go", "cmd/lyrebird/lyrebird.go", "cmd/lyrebird/pt_extras.go")) {
            val target = directory.resolve(path)
            target.parentFile.mkdirs()
            target.writeBytes(
                requireNotNull(javaClass.getResourceAsStream("/lyrebird-obfs4/$path")).use { it.readBytes() },
            )
        }
    }

    private fun gitApply(
        directory: File,
        vararg arguments: String,
    ) {
        val process =
            ProcessBuilder(
                listOf("git", "apply", "--unidiff-zero") + arguments,
            ).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("lyrebird-obfs4-patch").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
