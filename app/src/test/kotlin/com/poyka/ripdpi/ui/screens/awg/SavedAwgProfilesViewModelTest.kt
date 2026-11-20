package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.ui.screens.profiles.SavedAwgProfilesViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedAwgProfilesViewModelTest {
    private val fixture = AwgProfileViewModelFixture()

    @get:Rule
    val dispatcherRule: TestRule = fixture

    @Test
    fun `a failed profile read leaves the picker without AWG rows instead of crashing`() =
        runTest {
            fixture.dao.failReads = true
            val viewModel = SavedAwgProfilesViewModel(fixture.repository)

            backgroundScope.launch { viewModel.profiles.collect {} }
            advanceUntilIdle()

            assertTrue(viewModel.profiles.value.isEmpty())
        }
}
