import com.android.build.api.dsl.CommonExtension

/**
 * Shared Gradle Managed Device (GMD) registry.
 *
 * Applied to `:app` (via `ripdpi.android.application`) and `:baselineprofile` (via
 * `ripdpi.android.test`) so instrumented tests and macrobenchmarks run on the same
 * declarative, version-pinned emulators instead of shell-provisioned AVDs.
 *
 *  - pixel6Api34Atd     Pixel 6, API 34, aosp-atd      fast headless image; primary CI device
 *  - pixel6Api34Google  Pixel 6, API 34, google_apis   Google APIs coverage
 *  - ciDevices          device group spanning both     local "run on all" convenience
 *
 * GMD generates one task per device, e.g. `:app:pixel6Api34AtdGithubDebugAndroidTest`,
 * plus `:app:ciDevicesGroup<Variant>AndroidTest` for the group.
 */
internal fun CommonExtension.configureRipDpiManagedDevices() {
    val devices = testOptions.managedDevices.localDevices
    devices.create("pixel6Api34Atd") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "aosp-atd"
    }
    devices.create("pixel6Api34Google") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "google_apis"
    }
    testOptions.managedDevices.groups.create("ciDevices") {
        targetDevices.add(devices.getByName("pixel6Api34Atd"))
        targetDevices.add(devices.getByName("pixel6Api34Google"))
    }
}
