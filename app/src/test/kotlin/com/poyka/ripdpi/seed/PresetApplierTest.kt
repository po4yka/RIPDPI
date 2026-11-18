package com.poyka.ripdpi.seed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.backup.BackupRoomTest
import com.poyka.ripdpi.backup.DefaultUserArtifactResetStore
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.data.testing.backupRestoreUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetApplierTest : BackupRoomTest() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val presetGroup =
        ProxyGroup(id = "preset-group", name = "Preset", type = ProxyGroupType.BASIC, order = 0, isSelector = false)
    private val presetSettings =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setProxyPort(4242)
            .setOnboardingComplete(true)
            .build()
    private val preset =
        BackupSerializer.encodeToString(
            BackupExporter.export(
                BackupVariant.FULL,
                emptyList(),
                listOf(presetGroup),
                listOf(
                    RuleEntity(
                        name = "preset-rule",
                        userOrder = 0,
                        enabled = true,
                        domains = "example.com",
                        ports = "443",
                        network = RuleNetwork.TCP,
                        packages = emptySet(),
                        outboundTag = OutboundTag.Profile(1),
                    ),
                ),
                BackupSettingsConverter.toMap(presetSettings, BackupVariant.FULL),
                0L,
                "1.0.0",
                null,
            ),
        )

    @Before
    fun clearPresetState() {
        context
            .getSharedPreferences(PresetPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private suspend fun freshFixture(): RoomProfileFixture =
        roomFixture().also {
            it.database.ruleDao().deleteAll()
            it.coordinator.restoreBackup(
                it.coordinator.captureMutation(ProfileMutationOrigin.RestoreProfiles),
                emptyList(),
                emptyList(),
                AppSettingsSerializer.defaultValue,
                null,
            )
        }

    private fun applier(
        fixture: RoomProfileFixture,
        asset: String?,
    ) = object : PresetApplier(context, fixture.settings, fixture.backupRestoreUseCase()) {
        override fun readPreset(): String? = asset
    }

    private suspend fun RoomProfileFixture.deletePresetGroup() =
        groups.delete(coordinator.captureMutation(ProfileMutationOrigin.RestoreProfiles), "preset-group")

    private suspend fun RoomProfileFixture.ruleNames() =
        database
            .ruleDao()
            .allRules()
            .first()
            .map { it.name }

    @Test
    fun `fresh install restores groups rules and settings readable without a restart`() =
        runTest {
            val fixture = freshFixture()

            assertEquals(PresetOutcome.Applied, applier(fixture, preset).applyIfFresh())

            assertEquals(listOf("preset-group"), fixture.groups.list().map { it.id })
            assertEquals(listOf("preset-rule"), fixture.ruleNames())
            val settings = fixture.settings.snapshot()
            assertEquals(4242, settings.proxyPort)
            assertTrue(settings.onboardingComplete)
        }

    @Test
    fun `applied preset is not applied again and user edits survive`() =
        runTest {
            val fixture = freshFixture()
            applier(fixture, preset).applyIfFresh()
            fixture.deletePresetGroup()
            // A preset whose settings leave onboarding open must still apply only once.
            fixture.settings.update { setOnboardingComplete(false) }

            assertEquals(PresetOutcome.Skipped, applier(fixture, preset).applyIfFresh())

            assertTrue(fixture.groups.list().isEmpty())
        }

    @Test
    fun `install that completed onboarding is left untouched`() =
        runTest {
            val fixture = freshFixture()
            fixture.settings.update { setOnboardingComplete(true).setProxyPort(1111) }

            assertEquals(PresetOutcome.Skipped, applier(fixture, preset).applyIfFresh())

            assertTrue(fixture.groups.list().isEmpty())
            assertEquals(1111, fixture.settings.snapshot().proxyPort)
        }

    @Test
    fun `build without a preset is a no-op`() =
        runTest {
            val fixture = freshFixture()

            assertEquals(PresetOutcome.Skipped, applier(fixture, null).applyIfFresh())

            assertTrue(fixture.groups.list().isEmpty())
            assertFalse(fixture.settings.snapshot().onboardingComplete)
        }

    @Test
    fun `unsupported or unreadable preset writes nothing and a later valid preset applies`() =
        runTest {
            val fixture = freshFixture()
            val unsupported = preset.replace(Regex("\"schemaVersion\"\\s*:\\s*\\d+"), "\"schemaVersion\":99")
            check(unsupported != preset)

            assertEquals(
                PresetOutcome.Rejected("unsupported backup version 99"),
                applier(fixture, unsupported).applyIfFresh(),
            )
            // An encrypted archive read as text is not a backup document.
            assertEquals(
                PresetOutcome.Rejected("not a readable backup document"),
                applier(fixture, "RIPDPIBK\u0000\u0001binary").applyIfFresh(),
            )
            assertTrue(fixture.groups.list().isEmpty())
            assertFalse(fixture.settings.snapshot().onboardingComplete)

            assertEquals(PresetOutcome.Applied, applier(fixture, preset).applyIfFresh())
        }

    @Test
    fun `full reset makes the install fresh for the preset again`() =
        runTest {
            val fixture = freshFixture()
            applier(fixture, preset).applyIfFresh()
            fixture.deletePresetGroup()
            fixture.settings.replace(AppSettingsSerializer.defaultValue)

            DefaultUserArtifactResetStore(context).clearAll(AppSettingsSerializer.defaultValue)

            assertEquals(PresetOutcome.Applied, applier(fixture, preset).applyIfFresh())
            assertEquals(listOf("preset-group"), fixture.groups.list().map { it.id })
        }
}
