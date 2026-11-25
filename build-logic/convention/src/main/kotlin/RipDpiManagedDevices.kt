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
            name = "pixel6Api33Google",
            apiLevel = 33,
            systemImageSource = "google",
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
