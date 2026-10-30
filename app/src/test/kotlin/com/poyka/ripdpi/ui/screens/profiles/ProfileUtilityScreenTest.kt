package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp")
class ProfileUtilityScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val reference = ProfileUtilityReference.NativeRelay("native-one")

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `compact catalog shows a profile before optional probe setup`() {
        render(state(), actions())
        composeRule.onNodeWithText("Native one").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.profile_utility_disclosure)).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeUrl).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.Fastest).assertDoesNotExist()
    }

    @Test
    fun `probe options disclose setup without starting a check`() {
        var calls = 0
        render(state(), actions(fastest = { calls++ }))
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeOptions).performClick()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeUrl).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.Fastest).performScrollTo().performClick()
        assertEquals(1, calls)
    }

    @Test
    fun `secondary check is disclosed separately from check and select`() {
        val checked = mutableListOf<ProfileUtilityReference>()
        val selected = mutableListOf<ProfileUtilityReference>()
        render(state(), actions(check = checked::add, select = selected::add))
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "check")).assertDoesNotExist()
        composeRule
            .onNodeWithTag(ProfileUtilityTestTags.action(reference, "check_select"))
            .performScrollTo()
            .performClick()
        assertEquals(emptyList<ProfileUtilityReference>(), checked)
        assertEquals(listOf(reference), selected)
        composeRule
            .onNodeWithTag(ProfileUtilityTestTags.action(reference, "details"))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(ProfileUtilityTestTags.action(reference, "check"))
            .performScrollTo()
            .performClick()
        assertEquals(listOf(reference), checked)
        assertEquals(listOf(reference), selected)
    }

    @Test
    fun `applied state remains exposed when details are collapsed`() {
        val initial = state()
        render(initial.copy(profiles = persistentListOf(initial.profiles.single().copy(applied = true))), actions())
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "row")).assertIsSelected()
        composeRule.onNodeWithText(text(R.string.profile_utility_applied)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.profile_utility_not_checked)).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "check")).assertDoesNotExist()
    }

    @Test
    fun `checking exposes cancel without opening details or probe options`() {
        var cancellations = 0
        val initial = state()
        render(
            initial.copy(
                profiles =
                    persistentListOf(
                        initial.profiles.single().copy(measurement = ProfileMeasurementUiState.Checking),
                    ),
            ),
            actions(cancel = { cancellations++ }),
        )
        composeRule.onNodeWithText(text(R.string.profile_utility_checking)).assertIsDisplayed()
        composeRule
            .onNodeWithTag(ProfileUtilityTestTags.action(reference, "cancel"))
            .performScrollTo()
            .performClick()
        assertEquals(1, cancellations)
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeUrl).assertDoesNotExist()
    }

    @Test
    fun `row failure remains visible while details are collapsed`() {
        val initial = state()
        render(
            initial.copy(
                profiles =
                    persistentListOf(
                        initial.profiles.single().copy(
                            measurement = ProfileMeasurementUiState.Failed(ProfileUtilityFailure.HttpFailed),
                        ),
                    ),
            ),
            actions(),
        )
        composeRule.onNodeWithText(text(R.string.profile_utility_failed)).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "check")).assertDoesNotExist()
    }

    @Test
    fun `query does not reintroduce an applied profile that does not match`() {
        val initial = state()
        val other = ProfileUtilityReference.NativeRelay("other")
        render(
            initial.copy(
                profiles =
                    persistentListOf(
                        initial.profiles.single().copy(applied = true),
                        initial.profiles.single().copy(reference = other, label = "Travel"),
                    ),
            ),
            actions(),
        )
        composeRule
            .onNodeWithTag(RipDpiTestTags.profileSearchQuery(ProfileUtilityTestTags.Search))
            .performTextInput("Travel")
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "row")).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(other, "row")).assertExists()
    }

    @Test
    fun `check and check select dispatch distinct explicit requests`() {
        val checked = mutableListOf<ProfileUtilityReference>()
        val selected = mutableListOf<ProfileUtilityReference>()
        render(state(), actions(check = checked::add, select = selected::add))
        openDetails()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "check")).performScrollTo().performClick()
        assertEquals(listOf(reference), checked)
        assertEquals(emptyList<ProfileUtilityReference>(), selected)
        composeRule
            .onNodeWithTag(
                ProfileUtilityTestTags.action(reference, "check_select"),
            ).performScrollTo()
            .performClick()
        assertEquals(listOf(reference), selected)
    }

    @Test
    fun `favorite callback retains exact reference catalog generation and requested value`() {
        val requests = mutableListOf<Triple<ProfileUtilityReference, Long, Boolean>>()
        render(state(), actions(favorite = { ref, generation, value -> requests += Triple(ref, generation, value) }))
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "favorite")).performScrollTo().performClick()
        assertEquals(listOf(Triple(reference, 7L, true)), requests)
    }

    @Test
    fun `favorite button is not selected for a non favorite profile`() {
        render(state(), actions())
        composeRule
            .onNodeWithTag(ProfileUtilityTestTags.action(reference, "favorite"))
            .performScrollTo()
            .assertIsNotSelected()
    }

    @Test
    fun `favorite button is selected for a favorite profile`() {
        val favorite = state().let { it.copy(profiles = persistentListOf(it.profiles.single().copy(favorite = true))) }
        render(favorite, actions())
        composeRule
            .onNodeWithTag(ProfileUtilityTestTags.action(reference, "favorite"))
            .performScrollTo()
            .assertIsSelected()
    }

    @Test
    fun `cleanup pending disables both measurement choices but offers explicit retry`() {
        var retries = 0
        render(state().copy(cleanupPending = true), actions(retry = { retries++ }))
        composeRule.onNodeWithTag(ProfileUtilityTestTags.Cleanup).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeUrl).assertDoesNotExist()
        openDetails()
        composeRule
            .onNodeWithTag(
                ProfileUtilityTestTags.action(reference, "check"),
            ).performScrollTo()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(
                ProfileUtilityTestTags.action(reference, "check_select"),
            ).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.Cleanup).performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `loading catalog never claims that no saved profiles exist`() {
        render(state().copy(profiles = persistentListOf(), catalogState = ProfileCatalogState.Loading), actions())
        composeRule.onNodeWithText(text(R.string.profile_utility_loading)).assertExists()
        composeRule.onNodeWithText(text(R.string.profile_utility_empty)).assertDoesNotExist()
    }

    @Test
    fun `favorite filter only shows actually committed favorite rows`() {
        render(state(), actions())
        composeRule.onNodeWithText(text(R.string.profile_utility_favorites)).performClick()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "row")).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.profile_search_no_results)).assertExists()
    }

    @Test fun `catalog failure offers an explicit reload`() {
        var retries = 0
        render(
            state().copy(
                profiles = persistentListOf(),
                catalogState = ProfileCatalogState.Failed,
                failure = ProfileUtilityFailure.Persistence,
            ),
            actions(reload = { retries++ }),
        )
        composeRule.onNodeWithText(text(R.string.ui_profile_catalog_retry)).performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun `catalog read failure does not claim there are no saved profiles`() {
        render(
            state().copy(
                profiles = persistentListOf(),
                catalogState = ProfileCatalogState.Failed,
                failure = ProfileUtilityFailure.Persistence,
            ),
            actions(),
        )
        composeRule.onNodeWithText(text(R.string.profile_utility_empty)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.profile_utility_command_failed)).assertExists()
    }

    @Test fun `committed empty catalog remains distinguishable from a command failure`() {
        render(
            state().copy(
                profiles = persistentListOf(),
                catalogState = ProfileCatalogState.Ready,
                failure = ProfileUtilityFailure.Persistence,
            ),
            actions(),
        )
        composeRule.onNodeWithText(text(R.string.profile_utility_empty)).assertExists()
        composeRule.onNodeWithText(text(R.string.profile_utility_command_failed)).assertExists()
    }

    @Test fun `empty catalog offers profile import and hides check help`() {
        var imports = 0
        render(
            state().copy(profiles = persistentListOf(), catalogState = ProfileCatalogState.Ready),
            actions(importProfile = { imports++ }),
        )
        composeRule.onNodeWithText(text(R.string.profile_utility_disclosure)).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeOptions).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.EmptyImport).performScrollTo().performClick()
        assertEquals(1, imports)
    }

    @Test fun `fastest action is distinct from choosing a particular row`() {
        var calls = 0
        render(state(), actions(fastest = { calls += 1 }))
        composeRule.onNodeWithTag(ProfileUtilityTestTags.ProbeOptions).performClick()
        composeRule.onNodeWithTag(ProfileUtilityTestTags.Fastest).performClick()
        assertEquals(1, calls)
    }

    private fun openDetails() {
        composeRule.onNodeWithTag(ProfileUtilityTestTags.action(reference, "details")).performScrollTo().performClick()
    }

    private fun state() =
        ProfileUtilityUiState(
            7,
            persistentListOf(
                ProfileUtilityItem(
                    reference,
                    "Native one",
                    null,
                    "Shadowsocks",
                    false,
                    false,
                    null,
                    null,
                    ProfileMeasurementUiState.NotChecked,
                ),
            ),
            "https://probe.example/check",
            ProfileCatalogState.Ready,
            true,
            false,
            null,
        )

    private fun actions(
        check: (ProfileUtilityReference) -> Unit = { error("Unexpected check") },
        select: (ProfileUtilityReference) -> Unit = { error("Unexpected selection") },
        favorite: (ProfileUtilityReference, Long, Boolean) -> Unit = { _, _, _ -> error("Unexpected favorite") },
        retry: () -> Unit = { error("Unexpected cleanup") },
        fastest: () -> Unit = { error("Unexpected fastest selection") },
        reload: () -> Unit = { error("Unexpected catalog reload") },
        cancel: () -> Unit = { error("Unexpected cancel") },
        importProfile: () -> Unit = { error("Unexpected import") },
    ) = ProfileUtilityActions(
        favorite,
        check,
        select,
        cancel,
        retry,
        { error("Unexpected probe URL edit") },
        fastest,
        retryCatalog = reload,
        importProfile = importProfile,
    )

    private fun render(
        state: ProfileUtilityUiState,
        actions: ProfileUtilityActions,
    ) {
        composeRule.setContent { RipDpiTheme { ProfileUtilityScreen(state, { error("Unexpected back") }, actions) } }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
