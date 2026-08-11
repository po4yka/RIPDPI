package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DefaultDeveloperAnalyticsSourceTest {
    @Test
    fun `collect reports installed package version in reproduction context`() =
        runTest {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application.packageManager)
                .getInternalMutablePackageInfo(application.packageName)
                .versionName = "0.1.4-simple"

            val source =
                DefaultDeveloperAnalyticsSource(
                    appContext = application,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    probeResultCache = NoOpProbeResultCache,
                    breadcrumbBuffer = DeveloperBreadcrumbBuffer(),
                    gitCommit = "test-commit",
                    nativeLibVersion = "test-native",
                )

            val payload =
                source.collect(
                    DeveloperAnalyticsContext(archiveCreatedAtMs = 1L, archiveFileName = "test.zip"),
                )

            assertEquals(
                "0.1.4-simple" to BuildConfig.FLAVOR,
                payload.reproductionContext?.let { it.appVersionName to it.buildFlavor },
            )
        }

    private object NoOpProbeResultCache : ProbeResultCache {
        override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? = null

        override suspend fun snapshot(): List<CachedProbeOutcome> = emptyList()

        override suspend fun store(outcome: CachedProbeOutcome) = Unit

        override suspend fun evict(fingerprintHash: String) = Unit

        override suspend fun clear() = Unit
    }
}
