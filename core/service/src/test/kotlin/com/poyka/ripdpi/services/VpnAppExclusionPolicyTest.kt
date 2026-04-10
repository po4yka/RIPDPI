package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppRoutingPolicyPreset
import com.poyka.ripdpi.data.AppSettingsSerializer
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
