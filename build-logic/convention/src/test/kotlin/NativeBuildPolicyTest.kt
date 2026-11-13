import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

    @Test
    fun `BoringSSL cache ownership accepts both Cargo layouts`() {
        val root = Files.createTempDirectory("boring-cache-policy").toFile()
        try {
            val target = root.resolve("target").apply { mkdirs() }
            for (runPath in listOf(
                "arm/debug/build/boring-sys-0123456789abcdef",
                "arm/debug/build/boring-sys/0123456789abcdef",
            )) {
                val run = target.resolve(runPath)
                val cache =
                    run.resolve("out/build/CMakeCache.txt").apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("CMAKE_SYSTEM_NAME:STRING=Android\n")
                    }
                assertEquals(run.canonicalFile, boringSslCargoRunDirectory(cache, target))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `BoringSSL cache ownership rejects unrelated layouts and outside aliases`() {
        val root = Files.createTempDirectory("boring-cache-policy").toFile()
        try {
            val target = root.resolve("target").apply { mkdirs() }
            for (cachePath in listOf(
                "arm/debug/build/other-crate-0123456789abcdef/out/build/CMakeCache.txt",
                "arm/debug/build/boring-sys/not-a-hash/out/build/CMakeCache.txt",
                "arm/debug/build/boring-sys/0123456789abcdef/out/subproject/CMakeCache.txt",
                "arm/debug/build/boring-sys/0123456789abcdef/out/build/other.txt",
            )) {
                assertNull(boringSslCargoRunDirectory(target.resolve(cachePath), target))
            }
            val outside = root.resolve("outside/arm/debug/build/boring-sys/0123456789abcdef/out/build/CMakeCache.txt")
            requireNotNull(outside.parentFile).mkdirs()
            outside.writeText("outside")
            assertNull(boringSslCargoRunDirectory(outside, target))
            val alias = target.resolve("alias")
            Files.createSymbolicLink(alias.toPath(), root.resolve("outside").toPath())
            assertNull(
                boringSslCargoRunDirectory(
                    alias.resolve("arm/debug/build/boring-sys/0123456789abcdef/out/build/CMakeCache.txt"),
                    target,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
