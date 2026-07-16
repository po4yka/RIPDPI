package com.poyka.ripdpi.services

import android.content.Intent
import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppRoutingPolicyPreset
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnAppExclusionPolicyTest {
    @Test
    fun `policy resolves exact and regex packages from enabled presets`() =
        runTest {
            val repository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .clearAppRoutingEnabledPresetIds()
                        .addAppRoutingEnabledPresetIds("routing")
                        .build(),
                )
            val policy =
                DefaultVpnAppExclusionPolicy(
                    appSettingsRepository = repository,
                    appRoutingCatalogProvider =
                        FakeAppRoutingCatalogProvider(
                            AppRoutingPolicyCatalog(
                                presets =
                                    listOf(
                                        AppRoutingPolicyPreset(
                                            id = "routing",
                                            title = "Routing",
                                            exactPackages = listOf("com.vkontakte.android"),
                                            packageRegexes = listOf("^ru\\.yandex\\..+"),
                                        ),
                                    ),
                            ),
                        ),
                    installedPackagesProvider =
                        FakeInstalledPackagesProvider(
                            setOf("com.vkontakte.android", "ru.yandex.music", "com.other"),
                        ),
                )

            assertEquals(listOf("com.vkontakte.android", "ru.yandex.music"), policy.russianAppsToExclude())
        }

    @Test
    fun `full tunnel mode suppresses all exclusions`() =
        runTest {
            val repository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setFullTunnelMode(true)
                        .build(),
                )
            val policy =
                DefaultVpnAppExclusionPolicy(
                    appSettingsRepository = repository,
                    appRoutingCatalogProvider = FakeAppRoutingCatalogProvider(AppRoutingPolicyCatalog()),
                    installedPackagesProvider = FakeInstalledPackagesProvider(setOf("com.vkontakte.android")),
                )

            assertEquals(emptyList<String>(), policy.russianAppsToExclude())
        }

    @Test
    fun `policy refreshes installed package regex matches when catalog index is cached`() =
        runTest {
            val repository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .clearAppRoutingEnabledPresetIds()
                        .addAppRoutingEnabledPresetIds("routing")
                        .build(),
                )
            val installedPackagesProvider = MutableVpnInstalledPackagesProvider(setOf("ru.yandex.music", "com.other"))
            val policy =
                DefaultVpnAppExclusionPolicy(
                    appSettingsRepository = repository,
                    appRoutingCatalogProvider =
                        FakeAppRoutingCatalogProvider(
                            AppRoutingPolicyCatalog(
                                presets =
                                    listOf(
                                        AppRoutingPolicyPreset(
                                            id = "routing",
                                            title = "Routing",
                                            packageRegexes = listOf("^ru\\.yandex\\..+"),
                                        ),
                                    ),
                            ),
                        ),
                    installedPackagesProvider = installedPackagesProvider,
                )

            assertEquals(listOf("ru.yandex.music"), policy.russianAppsToExclude())

            installedPackagesProvider.packages = setOf("ru.yandex.browser", "com.other")

            assertEquals(listOf("ru.yandex.browser"), policy.russianAppsToExclude())
        }

    @Test
    fun `app routing plan uses supplied settings snapshot`() =
        runTest {
            val repository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue)
            val suppliedSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setSplitTunnelMode(SplitTunnelMode.Include)
                    .addSplitTunnelPackages("com.example.allowed")
                    .build()
            val policy =
                DefaultVpnAppExclusionPolicy(
                    appSettingsRepository = repository,
                    appRoutingCatalogProvider = FakeAppRoutingCatalogProvider(AppRoutingPolicyCatalog()),
                    installedPackagesProvider = FakeInstalledPackagesProvider(setOf("com.example.allowed")),
                )

            val plan =
                policy.appRoutingPlan(
                    ownPackage = OWN_PACKAGE,
                    settings = suppliedSettings,
                    packageRoutingRules = emptyList(),
                    installedPackages = setOf("com.example.allowed"),
                )

            assertEquals(VpnAppRoutingPlan.AllowOnly(setOf("com.example.allowed")), plan)
        }

    @Test
    fun `full tunnel mode plan disallows only own package`() {
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = true,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = listOf("com.example.one"),
                presetExclusions = listOf("com.preset.one"),
                installedPackages = setOf("com.example.one", "com.preset.one"),
                ownPackage = OWN_PACKAGE,
            )

        assertEquals(VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE)), plan)
    }

    @Test
    fun `exclude mode plan deduplicates preset and selection overlap`() {
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Exclude,
                splitTunnelPackages = listOf("com.preset.one", "com.user.two"),
                presetExclusions = listOf("com.preset.one"),
                installedPackages = setOf("com.preset.one", "com.user.two"),
                ownPackage = OWN_PACKAGE,
            )

        assertEquals(
            VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE, "com.preset.one", "com.user.two")),
            plan,
        )
    }

    @Test
    fun `include mode with selection allows only installed selection without own package`() {
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = listOf("com.user.one", "com.stale.gone"),
                presetExclusions = listOf("com.preset.one"),
                installedPackages = setOf("com.user.one", "com.preset.one"),
                ownPackage = OWN_PACKAGE,
            )

        assertEquals(VpnAppRoutingPlan.AllowOnly(setOf("com.user.one")), plan)
    }

    @Test
    fun `include mode with empty selection falls back to disallow preset behavior`() {
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = emptyList(),
                presetExclusions = listOf("com.preset.one"),
                installedPackages = setOf("com.preset.one"),
                ownPackage = OWN_PACKAGE,
            )

        assertEquals(VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE, "com.preset.one")), plan)
    }

    @Test
    fun `off mode plan preserves preset exclusion behavior`() {
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Off,
                splitTunnelPackages = listOf("com.ignored.one"),
                presetExclusions = listOf("com.preset.one"),
                installedPackages = setOf("com.preset.one", "com.ignored.one"),
                ownPackage = OWN_PACKAGE,
            )

        assertEquals(VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE, "com.preset.one")), plan)
    }

    /**
     * Criterion 4 (policy half): a VPN-detection-positive app on the blocklist routes DIRECT
     * (present in the `Disallow` set → `addDisallowedApplication` → egress on the underlying
     * network), while an unrelated app stays TUNNELED (absent from the set → routed through VLESS).
     * `ru.vk.store` (RuStore), selected but not installed, is dropped by the installed-set filter.
     *
     * The remaining half of the criterion — asserting the blocklisted app actually exits with a
     * non-tunnel IP while the allowed app exits via VLESS — needs a real device and a second egress
     * path (a JVM/Robolectric host cannot route an `addDisallowedApplication` socket); it is tracked
     * as device-gated.
     */
    @Test
    fun `blocklisted vpn-detection app routes direct while unrelated app stays tunneled`() {
        val sber = "ru.sberbankmobile"
        val ruStore = "ru.vk.store"
        val chrome = "com.android.chrome"

        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Exclude,
                splitTunnelPackages = listOf(sber, ruStore),
                presetExclusions = emptyList(),
                installedPackages = setOf(sber, chrome),
                ownPackage = OWN_PACKAGE,
            )

        // Disallow holds exactly {own, Sber}: Sber routes direct, Chrome stays tunneled (absent),
        // and not-installed RuStore is dropped by the installed-set filter.
        assertEquals(VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE, sber)), plan)
    }

    @Test
    fun `subscription rules refine disallow plan and forced tunnel wins conflicts`() {
        val forced = "com.example.forced"
        val bypass = "com.example.bypass"
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Exclude,
                splitTunnelPackages = listOf(forced),
                presetExclusions = listOf(forced),
                installedPackages = setOf(forced, bypass, OWN_PACKAGE),
                ownPackage = OWN_PACKAGE,
                packageRoutingRules =
                    listOf(
                        packageRule(forced, PackageRoutingAction.BYPASS),
                        packageRule(bypass, PackageRoutingAction.BYPASS),
                        packageRule(forced, PackageRoutingAction.VIA_TUN),
                    ),
            )

        assertEquals(VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE, bypass)), plan)
    }

    @Test
    fun `subscription rules refine allowlist and ignore own and uninstalled packages`() {
        val selected = "com.example.selected"
        val forced = "com.example.forced"
        val bypass = "com.example.bypass"
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = listOf(selected, bypass),
                presetExclusions = emptyList(),
                installedPackages = setOf(selected, forced, bypass, OWN_PACKAGE),
                ownPackage = OWN_PACKAGE,
                packageRoutingRules =
                    listOf(
                        packageRule(bypass, PackageRoutingAction.BYPASS),
                        packageRule(forced, PackageRoutingAction.VIA_OUTBOUND),
                        packageRule(OWN_PACKAGE, PackageRoutingAction.VIA_TUN),
                        packageRule("com.example.stale", PackageRoutingAction.VIA_TUN),
                    ),
            )

        assertEquals(VpnAppRoutingPlan.AllowOnly(setOf(selected, forced)), plan)
    }

    @Test
    fun `bypassing the last allowlisted package does not fall back to tunneling every app`() {
        val selected = "com.example.selected"
        val other = "com.example.other"
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = false,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = listOf(selected),
                presetExclusions = emptyList(),
                installedPackages = setOf(selected, other, OWN_PACKAGE),
                ownPackage = OWN_PACKAGE,
                packageRoutingRules = listOf(packageRule(selected, PackageRoutingAction.BYPASS)),
            )

        assertEquals(VpnAppRoutingPlan.Disallow(setOf(selected, other, OWN_PACKAGE)), plan)
    }

    @Test
    fun `full tunnel ignores subscription package rules`() {
        val plan =
            computeAppRoutingPlan(
                fullTunnelMode = true,
                splitTunnelMode = SplitTunnelMode.Include,
                splitTunnelPackages = emptyList(),
                presetExclusions = emptyList(),
                installedPackages = setOf("com.example.app", OWN_PACKAGE),
                ownPackage = OWN_PACKAGE,
                packageRoutingRules = listOf(packageRule("com.example.app", PackageRoutingAction.BYPASS)),
            )

        assertEquals(VpnAppRoutingPlan.Disallow(setOf(OWN_PACKAGE)), plan)
    }

    @Test
    fun `package install and removal events advance the cached installed set`() {
        val installed =
            applyInstalledPackageEvent(
                current = setOf("com.example.old"),
                action = Intent.ACTION_PACKAGE_ADDED,
                packageName = "com.example.new",
                replacing = false,
                installed = true,
            )
        val removed =
            applyInstalledPackageEvent(
                current = installed,
                action = Intent.ACTION_PACKAGE_REMOVED,
                packageName = "com.example.old",
                replacing = false,
                installed = false,
            )

        assertEquals(setOf("com.example.new"), removed)
    }

    @Test
    fun `package replacement removal preserves the cached package`() {
        val packages = setOf("com.example.app")

        assertEquals(
            packages,
            applyInstalledPackageEvent(
                current = packages,
                action = Intent.ACTION_PACKAGE_REMOVED,
                packageName = "com.example.app",
                replacing = true,
                installed = false,
            ),
        )
    }

    @Test
    fun `package cache resnapshots after receiver registration`() {
        val packages = MutableStateFlow(emptySet<String>())
        var installed = setOf("com.example.before")

        initializeInstalledPackageSnapshot(
            packages = packages,
            loadInstalledPackages = { installed },
            registerReceiver = {
                installed = setOf("com.example.after")
            },
        )

        assertEquals(setOf("com.example.after"), packages.value)
    }

    private fun packageRule(
        packageName: String,
        action: PackageRoutingAction,
    ): PackageRoutingRule =
        PackageRoutingRule(
            packageName = packageName,
            action = action,
            origin = PackageRoutingRuleOrigin.Subscription("fixture-group"),
        )

    private companion object {
        const val OWN_PACKAGE = "com.poyka.ripdpi"
    }
}

private class FakeAppRoutingCatalogProvider(
    private val catalog: AppRoutingPolicyCatalog,
) : AppRoutingCatalogProvider {
    override fun load(): AppRoutingPolicyCatalog = catalog
}

private class FakeInstalledPackagesProvider(
    private val packages: Set<String>,
) : InstalledPackagesProvider {
    override fun installedPackages(): Set<String> = packages
}

private class MutableVpnInstalledPackagesProvider(
    var packages: Set<String>,
) : InstalledPackagesProvider {
    override fun installedPackages(): Set<String> = packages
}
