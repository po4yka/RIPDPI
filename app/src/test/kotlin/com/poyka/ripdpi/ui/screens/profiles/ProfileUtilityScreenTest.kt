package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileUtilityReference
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
    fun `check and check select dispatch distinct explicit requests`() {
        val checked = mutableListOf<ProfileUtilityReference>()
        val selected = mutableListOf<ProfileUtilityReference>()
        render(state(), actions(check = checked::add, select = selected::add))
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
    fun `cleanup pending disables both measurement choices but offers explicit retry`() {
        var retries = 0
        render(state().copy(cleanupPending = true), actions(retry = { retries++ }))
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

    @Test fun `fastest action is distinct from choosing a particular row`() {
        var calls = 0
        render(state(), actions(fastest = { calls += 1 }))
        composeRule.onNodeWithTag(ProfileUtilityTestTags.Fastest).performClick()
        assertEquals(1, calls)
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
    ) = ProfileUtilityActions(
        favorite,
        check,
        select,
        { error("Unexpected cancel") },
        retry,
        { error("Unexpected probe URL edit") },
        fastest,
        retryCatalog = reload,
    )

    private fun render(
        state: ProfileUtilityUiState,
        actions: ProfileUtilityActions,
    ) {
        composeRule.setContent { RipDpiTheme { ProfileUtilityScreen(state, { error("Unexpected back") }, actions) } }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
