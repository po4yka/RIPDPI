import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RipDpiManagedDevicesTest {
    @Test
    fun `target SDK device explicitly uses 16 KB pages`() {
        val target = ripDpiManagedDeviceSpecs.single { it.apiLevel == 37 }
        assertTrue(target.force16KbPages)
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        assertContains(workflow, "api: 37\n            system-image: google_apis_ps16k")
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

        assertEquals(listOf(minSdk, 33, 35, 36, targetSdk), ciDevices.map(RipDpiManagedDeviceSpec::apiLevel).sorted())
        assertEquals(
            mapOf(27 to "aosp", 33 to "aosp-atd", 35 to "google", 36 to "google", 37 to "google"),
            ciDevices.associate { it.apiLevel to it.systemImageSource },
        )
        assertTrue(ciDevices.all { it.testedAbi == "x86_64" })
        assertEquals(listOf(34), ripDpiManagedDeviceSpecs.filterNot { it.includeInCiGroup }.map { it.apiLevel })
    }

    @Test
    fun `instrumented CI workflow mirrors managed device smoke matrix`() {
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        val job = workflow.substringAfter("  android-instrumented-tests:").substringBefore("\n  rust-loom:")

        assertContains(job, "- device: pixel6Api27Aosp\n            api: 27\n            system-image: default")
        assertContains(job, "- device: pixel6Api33Atd\n            api: 33\n            system-image: aosp_atd")
        assertContains(job, "- device: pixel6Api35Google\n            api: 35\n            system-image: google_apis")
        assertContains(job, "- device: pixel6Api36Google\n            api: 36\n            system-image: google_apis")
        assertContains(
            job,
            "- device: pixel6Api37Google\n            api: 37\n            system-image: google_apis_ps16k",
        )
        assertContains(job, "--api \"\${{ matrix.api }}\"")
        assertEquals(
            listOf(27, 33, 35, 36, 37),
            Regex("api: (\\d+)").findAll(job).map { it.groupValues[1].toInt() }.toList(),
        )
    }

    @Test
    fun `hosted API 37 lane does not claim direct LAN evidence through emulator NAT`() {
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        val job = workflow.substringAfter("  android-instrumented-tests:").substringBefore("\n  rust-loom:")

        assertFalse(job.contains("Verify API 37 LAN permission on a real TCP and UDP endpoint"))
        assertFalse(job.contains("run_target37_network_smoke.py"))
    }

    private fun repoRoot(): Path = Path.of(System.getProperty("user.dir")).resolve("../..").normalize()
}
