import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperExecutableJniLibsTaskTest {
    @Test
    fun `helpers and verified transport upstreams are staged as lib so per ABI`() {
        withProject { projectDir, task ->
            val ptRoot = writePtAssets(projectDir, upstreamBytes = "lyrebird-elf")
            configure(task, projectDir, ptRoot)

            task.stage()

            val abiDir = projectDir.resolve("jniLibs/arm64-v8a")
            assertEquals(
                setOf(
                    "libripdpi-naiveproxy.so",
                    "libripdpi-cloudflare-origin.so",
                    "libripdpi-naive-chromium.so",
                    "liblyrebird.upstream.so",
                ),
                abiDir.list().orEmpty().toSet(),
            )
            assertEquals("naive-elf", abiDir.resolve("libripdpi-naiveproxy.so").readText())
            assertEquals("lyrebird-elf", abiDir.resolve("liblyrebird.upstream.so").readText())
            assertEquals("chromium-elf", abiDir.resolve("libripdpi-naive-chromium.so").readText())
            assertTrue(abiDir.resolve("libripdpi-naive-chromium.so").canExecute())
            assertTrue(abiDir.resolve("liblyrebird.upstream.so").canExecute())
            assertEquals(
                ptRoot.resolve(PluggableTransportManifestPath).readText(),
                projectDir.resolve("metadata/$PluggableTransportManifestPath").readText(),
            )
            assertFalse(projectDir.resolve("metadata/bin").exists(), "launchers and upstreams are not assets")
        }
    }

    @Test
    fun `tampered transport upstream fails the digest check`() {
        withProject { projectDir, task ->
            val ptRoot = writePtAssets(projectDir, upstreamBytes = "lyrebird-elf")
            ptRoot.resolve("bin/arm64-v8a/lyrebird.upstream").writeText("tampered")
            configure(task, projectDir, ptRoot)

            val error = assertFailsWith<GradleException> { task.stage() }
            assertTrue(error.message.orEmpty().contains("digest mismatch"))
        }
    }

    @Test
    fun `stub transports stage no upstream`() {
        withProject { projectDir, task ->
            val ptRoot = writePtAssets(projectDir, upstreamBytes = null)
            configure(task, projectDir, ptRoot)

            task.stage()

            assertFalse(projectDir.resolve("jniLibs/arm64-v8a/liblyrebird.upstream.so").exists())
            assertTrue(projectDir.resolve("jniLibs/arm64-v8a/libripdpi-naiveproxy.so").isFile)
        }
    }

    @Test
    fun `missing direct helper fails`() {
        withProject { projectDir, task ->
            val ptRoot = writePtAssets(projectDir, upstreamBytes = "lyrebird-elf")
            configure(task, projectDir, ptRoot)
            projectDir.resolve("naive/arm64-v8a/ripdpi-naiveproxy").delete()

            assertFailsWith<GradleException> { task.stage() }
        }
    }

    @Test
    fun `missing upstream naive client fails and names the fetch command`() {
        withProject { projectDir, task ->
            configure(task, projectDir, writePtAssets(projectDir, upstreamBytes = null))
            projectDir.resolve("naive-upstream/arm64-v8a/libnaive.so").delete()

            val message = assertFailsWith<GradleException> { task.stage() }.message.orEmpty()
            assertTrue(message.contains("arm64-v8a"), message)
            assertTrue(message.contains(sha256("chromium-elf")), message)
            assertTrue(message.contains(NaiveUpstreamFetchCommand), message)
        }
    }

    @Test
    fun `tampered upstream naive client fails the digest check`() {
        withProject { projectDir, task ->
            configure(task, projectDir, writePtAssets(projectDir, upstreamBytes = null))
            projectDir.resolve("naive-upstream/arm64-v8a/libnaive.so").writeText("tampered")

            val message = assertFailsWith<GradleException> { task.stage() }.message.orEmpty()
            assertTrue(message.contains("arm64-v8a"), message)
            assertTrue(message.contains(sha256("tampered")), message)
            assertTrue(message.contains(sha256("chromium-elf")), message)
            assertTrue(message.contains(NaiveUpstreamFetchCommand), message)
        }
    }

    @Test
    fun `library name rejects path segments`() {
        assertEquals("libripdpi-naiveproxy.so", helperExecutableJniLibName("ripdpi-naiveproxy"))
        assertFailsWith<IllegalArgumentException> { helperExecutableJniLibName("../escape") }
    }

    private fun withProject(block: (File, HelperExecutableJniLibsTask) -> Unit) {
        val projectDir = Files.createTempDirectory("helper-jnilibs").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            block(projectDir, project.tasks.register("stageHelpers", HelperExecutableJniLibsTask::class.java).get())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun configure(
        task: HelperExecutableJniLibsTask,
        projectDir: File,
        ptRoot: File,
    ) {
        projectDir.resolve("naive/arm64-v8a/ripdpi-naiveproxy").writeWithParents("naive-elf")
        projectDir.resolve("origin/arm64-v8a/ripdpi-cloudflare-origin").writeWithParents("origin-elf")
        task.directHelperRoots.from(projectDir.resolve("naive"), projectDir.resolve("origin"))
        task.directHelperNames.set(listOf("ripdpi-naiveproxy", "ripdpi-cloudflare-origin"))
        projectDir.resolve("naive-upstream/arm64-v8a/libnaive.so").writeWithParents("chromium-elf")
        projectDir.resolve("naive-sources.json").writeText(
            """{"schemaVersion":1,"abis":{"arm64-v8a":{"libnaiveSha256":"${sha256("chromium-elf")}"}}}""",
        )
        task.naiveUpstreamRoot.from(projectDir.resolve("naive-upstream"))
        task.naiveUpstreamManifest.set(projectDir.resolve("naive-sources.json"))
        task.pluggableTransportAssetsDir.set(ptRoot)
        task.abis.set(listOf("arm64-v8a"))
        task.jniLibsDir.set(projectDir.resolve("jniLibs"))
        task.pluggableTransportMetadataDir.set(projectDir.resolve("metadata"))
    }

    private fun writePtAssets(
        projectDir: File,
        upstreamBytes: String?,
    ): File {
        val ptRoot = projectDir.resolve("pt")
        val binDir = ptRoot.resolve("bin/arm64-v8a")
        binDir.resolve("ripdpi-obfs4").writeWithParents("#!/system/bin/sh\n")
        binDir.resolve("ripdpi-snowflake").writeWithParents("#!/system/bin/sh\n")
        val upstreamFields =
            if (upstreamBytes == null) {
                """"upstreamBinary":null,"upstreamSha256":null"""
            } else {
                binDir.resolve("lyrebird.upstream").writeWithParents(upstreamBytes)
                """"upstreamBinary":"lyrebird.upstream","upstreamSha256":"${sha256(upstreamBytes)}""""
            }
        ptRoot.resolve(PluggableTransportManifestPath).writeWithParents(
            """
            {"schemaVersion":1,"artifacts":[
              {"abi":"arm64-v8a","outputName":"ripdpi-snowflake",$upstreamFields},
              {"abi":"arm64-v8a","outputName":"ripdpi-obfs4",$upstreamFields}
            ]}
            """.trimIndent(),
        )
        return ptRoot
    }

    private fun File.writeWithParents(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
