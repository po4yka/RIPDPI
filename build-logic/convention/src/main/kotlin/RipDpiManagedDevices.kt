import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ManagedVirtualDevice

internal data class RipDpiManagedDeviceSpec(
    val name: String,
    val apiLevel: Int,
    val systemImageSource: String,
    val testedAbi: String = "x86_64",
    val includeInCiGroup: Boolean,
    val force16KbPages: Boolean = false,
)

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
        RipDpiManagedDeviceSpec(
            name = "pixel6Api36Google",
            apiLevel = 36,
            systemImageSource = "google",
            includeInCiGroup = true,
        ),
        RipDpiManagedDeviceSpec(
            name = "pixel6Api37Google",
            apiLevel = 37,
            systemImageSource = "google",
            includeInCiGroup = true,
            force16KbPages = true,
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
 *  - pixel6Api35Google  Pixel 6, API 35, Google APIs   previous-target compatibility smoke
 *  - pixel6Api36Google  Pixel 6, API 36, Google APIs   Android 16 runtime smoke
 *  - pixel6Api37Google  Pixel 6, API 37, Google APIs   targetSdk compatibility smoke
 *  - ciDevices          API 27/33/35/36/37 smoke matrix
 *
 * GMD generates one task per device, e.g. `:app:pixel6Api27AospGithubFullDebugAndroidTest`,
 * plus `:app:ciDevicesGroup<Variant>AndroidTest` for the group.
 */
internal fun CommonExtension.configureRipDpiManagedDevices(includeCiDevices: Boolean = true) {
    val devices = testOptions.managedDevices.localDevices
    val selectedSpecs =
        ripDpiManagedDeviceSpecs.filter { spec -> includeCiDevices || !spec.includeInCiGroup }
    selectedSpecs.forEach { spec ->
        devices.create(spec.name) {
            device = "Pixel 6"
            apiLevel = spec.apiLevel
            systemImageSource = spec.systemImageSource
            testedAbi = spec.testedAbi
            if (spec.force16KbPages) pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
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
