import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeBuildPolicyTest {
    @Test
    fun `full ABI build divides the available CPU budget`() {
        assertEquals(
            NativeConcurrency(abiWorkers = 4, cargoJobsPerWorker = 2),
            computeNativeConcurrency(
                abiCount = 4,
                availableCpus = 8,
                abiParallelism = 4,
                cpuBudget = 8,
            ),
        )
    }

    @Test
    fun `budget caps concurrent ABI workers and cargo jobs`() {
        assertEquals(
            NativeConcurrency(abiWorkers = 3, cargoJobsPerWorker = 1),
            computeNativeConcurrency(
                abiCount = 4,
                availableCpus = 8,
                abiParallelism = 4,
                cpuBudget = 3,
            ),
        )
    }

    @Test
    fun `single ABI receives the complete effective budget`() {
        assertEquals(
            NativeConcurrency(abiWorkers = 1, cargoJobsPerWorker = 8),
            computeNativeConcurrency(
                abiCount = 1,
                availableCpus = 12,
                abiParallelism = 4,
                cpuBudget = 8,
            ),
        )
    }

    @Test
    fun `invalid scheduling limits fail before native work starts`() {
        assertFailsWith<IllegalArgumentException> {
            computeNativeConcurrency(4, 8, 4, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            computeNativeConcurrency(4, 8, 0, 8)
        }
    }
}
