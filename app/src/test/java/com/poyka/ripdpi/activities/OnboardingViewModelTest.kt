package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel() = OnboardingViewModel(FakeAppSettingsRepository())

    @Test
    fun `initial state has page zero and three total pages`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                val state = awaitItem()
                assertEquals(0, state.currentPage)
                assertEquals(3, state.totalPages)
            }
        }

    @Test
    fun `setCurrentPage updates current page`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.setCurrentPage(2)
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `setCurrentPage clamps to valid range`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.setCurrentPage(99)
                assertEquals(2, awaitItem().currentPage)

                vm.setCurrentPage(-5)
                assertEquals(0, awaitItem().currentPage)
            }
        }

    @Test
    fun `nextPage increments current page`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.nextPage()
                assertEquals(1, awaitItem().currentPage)

                vm.nextPage()
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `nextPage does not exceed last page`() =
        runTest {
            val vm = createViewModel()
            vm.setCurrentPage(2)
            vm.uiState.test {
                assertEquals(2, awaitItem().currentPage)
                vm.nextPage()
                expectNoEvents()
            }
        }

    @Test
    fun `previousPage decrements and clamps at zero`() =
        runTest {
            val vm = createViewModel()
            vm.setCurrentPage(1)
            vm.uiState.test {
                assertEquals(1, awaitItem().currentPage)

                vm.previousPage()
                assertEquals(0, awaitItem().currentPage)

                vm.previousPage()
                expectNoEvents()
            }
        }
}
