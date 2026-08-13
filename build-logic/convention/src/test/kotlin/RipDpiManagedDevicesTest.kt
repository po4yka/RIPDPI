import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RipDpiManagedDevicesTest {
    @Test
    fun `managed device ABI follows the host architecture`() {
        assertEquals(
            listOf("arm64-v8a", "arm64-v8a", "x86_64", "x86_64"),
            listOf("arm64", "aarch64", "x86_64", "amd64").map(::ripDpiManagedDeviceTestedAbi),
        )
    }

    @Test
    fun `CI device group covers supported Android eras`() {
        val ciDevices = ripDpiManagedDeviceSpecs.filter(RipDpiManagedDeviceSpec::includeInCiGroup)
        val projectProperties =
            Properties().apply {
                repoRoot()
                    .resolve("gradle.properties")
                    .toFile()
                    .inputStream()
                    .use(::load)
            }
        val minSdk = projectProperties.getProperty("ripdpi.minSdk").toInt()
        val targetSdk = projectProperties.getProperty("ripdpi.targetSdk").toInt()

        assertEquals(listOf(minSdk, 33, targetSdk), ciDevices.map(RipDpiManagedDeviceSpec::apiLevel).sorted())
        assertEquals(
            mapOf(27 to "aosp", 33 to "aosp-atd", 35 to "google"),
            ciDevices.associate { it.apiLevel to it.systemImageSource },
        )
        assertEquals(listOf(34), ripDpiManagedDeviceSpecs.filterNot { it.includeInCiGroup }.map { it.apiLevel })
    }

    @Test
    fun `instrumented CI workflow mirrors managed device smoke matrix`() {
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        val job = workflow.substringAfter("  android-instrumented-tests:").substringBefore("\n  rust-loom:")

        assertContains(job, "- device: pixel6Api27Aosp\n            api: 27\n            system-image: default")
        assertContains(job, "- device: pixel6Api33Atd\n            api: 33\n            system-image: aosp_atd")
        assertContains(job, "- device: pixel6Api35Google\n            api: 35\n            system-image: google_apis")
        assertContains(job, "--api \"\${{ matrix.api }}\"")
        assertEquals(listOf(27, 33, 35), Regex("api: (\\d+)").findAll(job).map { it.groupValues[1].toInt() }.toList())
    }

    private fun repoRoot(): Path = Path.of(System.getProperty("user.dir")).resolve("../..").normalize()
}
