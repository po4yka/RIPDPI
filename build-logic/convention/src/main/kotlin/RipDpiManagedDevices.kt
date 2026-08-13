import com.android.build.api.dsl.CommonExtension

internal data class RipDpiManagedDeviceSpec(
    val name: String,
    val apiLevel: Int,
    val systemImageSource: String,
    val includeInCiGroup: Boolean,
)

internal fun ripDpiManagedDeviceTestedAbi(hostArchitecture: String): String =
    when (hostArchitecture.lowercase()) {
        "aarch64", "arm64" -> "arm64-v8a"
        else -> "x86_64"
    }

internal val ripDpiManagedDeviceSpecs =
    listOf(
        RipDpiManagedDeviceSpec(
            name = "pixel6Api27Aosp",
            apiLevel = 27,
            systemImageSource = "aosp",
            includeInCiGroup = true,
        ),
        RipDpiManagedDeviceSpec(
            name = "pixel6Api33Atd",
            apiLevel = 33,
            systemImageSource = "aosp-atd",
            includeInCiGroup = true,
        ),
        RipDpiManagedDeviceSpec(
            name = "pixel6Api34Atd",
            apiLevel = 34,
            systemImageSource = "aosp-atd",
            includeInCiGroup = false,
        ),
        RipDpiManagedDeviceSpec(
            name = "pixel6Api35Google",
            apiLevel = 35,
            systemImageSource = "google",
            includeInCiGroup = true,
        ),
    )

/**
 * Shared Gradle Managed Device (GMD) registry.
 *
 * Applied to `:app` (via `ripdpi.android.application`) and `:baselineprofile` (via
 * `ripdpi.android.test`) so instrumented tests and macrobenchmarks run on the same
 * declarative, version-pinned emulators instead of shell-provisioned AVDs.
 *
 *  - pixel6Api27Aosp    Pixel 6, API 27, AOSP          minSdk compatibility smoke
 *  - pixel6Api33Atd     Pixel 6, API 33, aosp-atd      modern runtime behavior smoke
 *  - pixel6Api34Atd     Pixel 6, API 34, aosp-atd      pinned macrobenchmark device
 *  - pixel6Api35Google  Pixel 6, API 35, Google APIs   targetSdk compatibility smoke
 *  - ciDevices          API 27/33/35 smoke matrix      local "run on all" convenience
 *
 * GMD generates one task per device, e.g. `:app:pixel6Api27AospGithubFullDebugAndroidTest`,
 * plus `:app:ciDevicesGroup<Variant>AndroidTest` for the group.
 */
internal fun CommonExtension.configureRipDpiManagedDevices(includeCiDevices: Boolean = true) {
    val devices = testOptions.managedDevices.localDevices
    val testedAbi = ripDpiManagedDeviceTestedAbi(System.getProperty("os.arch"))
    val selectedSpecs =
        ripDpiManagedDeviceSpecs.filter { spec -> includeCiDevices || !spec.includeInCiGroup }
    selectedSpecs.forEach { spec ->
        devices.create(spec.name) {
            device = "Pixel 6"
            apiLevel = spec.apiLevel
            systemImageSource = spec.systemImageSource
            this.testedAbi = testedAbi
        }
    }
    if (includeCiDevices) {
        testOptions.managedDevices.groups.create("ciDevices") {
            selectedSpecs
                .filter(RipDpiManagedDeviceSpec::includeInCiGroup)
                .forEach { spec -> targetDevices.add(devices.getByName(spec.name)) }
        }
    }
}
