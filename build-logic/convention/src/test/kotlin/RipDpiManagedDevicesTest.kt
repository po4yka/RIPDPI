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
        val ciTarget = ciInstrumentedDevices().single { it.api == 37 }
        assertEquals("google_apis_ps16k", ciTarget.systemImage)
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
            mapOf(27 to "aosp", 33 to "google", 35 to "google", 36 to "google", 37 to "google"),
            ciDevices.associate { it.apiLevel to it.systemImageSource },
        )
        assertTrue(ciDevices.all { it.testedAbi == "x86_64" })
        assertEquals(listOf(34), ripDpiManagedDeviceSpecs.filterNot { it.includeInCiGroup }.map { it.apiLevel })
    }

    @Test
    fun `instrumented CI workflow mirrors managed device smoke matrix`() {
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        val job = workflow.substringAfter("  android-instrumented-tests:").substringBefore("\n  rust-loom:")
        val expected =
            ripDpiManagedDeviceSpecs.filter(RipDpiManagedDeviceSpec::includeInCiGroup).map { spec ->
                val image =
                    when {
                        spec.force16KbPages -> "google_apis_ps16k"
                        spec.systemImageSource == "aosp" -> "default"
                        else -> "google_apis"
                    }
                CiDevice(spec.name, spec.apiLevel, image, pullRequest = false)
            }

        assertContains(job, "include: \${{ fromJSON(needs.change-routing.outputs.instrumented_devices) }}")
        assertContains(job, "--api \"\${{ matrix.api }}\"")
        assertEquals(expected, ciInstrumentedDevices().map { it.copy(pullRequest = false) })
        assertEquals(listOf(27, 35, 37), ciInstrumentedDevices().filter(CiDevice::pullRequest).map(CiDevice::api))
    }

    @Test
    fun `hosted API 37 lane does not claim direct LAN evidence through emulator NAT`() {
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        val job = workflow.substringAfter("  android-instrumented-tests:").substringBefore("\n  rust-loom:")

        assertFalse(job.contains("Verify API 37 LAN permission on a real TCP and UDP endpoint"))
        assertFalse(job.contains("run_target37_network_smoke.py"))
    }

    private data class CiDevice(
        val name: String,
        val api: Int,
        val systemImage: String,
        val pullRequest: Boolean,
    )

    // change-routing emits the emulator matrix as a JSON list; PRs run the pull_request subset.
    private fun ciInstrumentedDevices(): List<CiDevice> {
        val workflow = repoRoot().resolve(".github/workflows/ci.yml").readText()
        val devices = workflow.substringAfter("      - id: devices").substringBefore("]'")
        return Regex(
            "\\{\"device\": \"(\\w+)\", \"api\": (\\d+), \"system-image\": \"(\\w+)\", \"pull_request\": (true|false)\\}",
        ).findAll(devices)
            .map { match ->
                val (name, api, image, pullRequest) = match.destructured
                CiDevice(name, api.toInt(), image, pullRequest.toBoolean())
            }.toList()
    }

    private fun repoRoot(): Path = Path.of(System.getProperty("user.dir")).resolve("../..").normalize()
}
