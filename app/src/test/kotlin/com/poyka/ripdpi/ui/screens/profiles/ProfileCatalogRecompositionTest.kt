package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileCatalogRecompositionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `probe settings updates do not rescan five thousand unchanged profiles`() {
        val reads = AtomicInteger()
        val profiles = CountedProfiles((0 until 5_000).map(::profile).toImmutableList(), reads)
        val state =
            mutableStateOf(
                ProfileUtilityUiState(
                    catalogGeneration = 1,
                    profiles = profiles,
                    probeUrl = "https://probe.example/initial",
                    catalogState = ProfileCatalogState.Ready,
                    canMeasure = true,
                    cleanupPending = false,
                    failure = null,
                ),
            )
        val actions =
            ProfileUtilityActions(
                favorite = { _, _, _ -> error("Unexpected favorite") },
                check = { error("Unexpected check") },
                checkAndSelect = { error("Unexpected selection") },
                cancel = { error("Unexpected cancel") },
                retryCleanup = { error("Unexpected cleanup") },
                probeUrl = { error("Unexpected edit") },
                checkFastest = { error("Unexpected bulk check") },
            )
        composeRule.setContent {
            RipDpiTheme {
                ProfileUtilityScreen(state.value, { error("Unexpected back") }, actions)
            }
        }
        composeRule.runOnIdle {
            assertTrue("The initial screen must project the full catalog", reads.get() >= profiles.size)
            reads.set(0)
        }
        repeat(5) { index ->
            composeRule.runOnIdle {
                state.value = state.value.copy(probeUrl = "https://probe.example/$index", canMeasure = index % 2 == 0)
            }
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals("Unrelated controls must reuse the catalog projection", 0, reads.get())
        }
    }

    private fun profile(index: Int) =
        ProfileUtilityItem(
            reference = ProfileUtilityReference.NativeRelay("profile-$index"),
            label = "Profile $index",
            groupLabel = null,
            kind = "Shadowsocks",
            favorite = index % 2 == 0,
            applied = index == 0,
            lastUsedAtMillis = null,
            recentSequence = null,
            measurement = ProfileMeasurementUiState.NotChecked,
        )
}

private class CountedProfiles(
    private val values: ImmutableList<ProfileUtilityItem>,
    private val reads: AtomicInteger,
) : ImmutableList<ProfileUtilityItem> by values {
    override fun iterator(): Iterator<ProfileUtilityItem> {
        val iterator = values.iterator()
        return object : Iterator<ProfileUtilityItem> {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): ProfileUtilityItem {
                if (!iterator.hasNext()) throw NoSuchElementException()
                reads.incrementAndGet()
                return iterator.next()
            }
        }
    }
}
