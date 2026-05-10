package com.poyka.ripdpi.diagnostics.dpi

import kotlin.random.Random

class RandomPaddingPool(
    private val poolSizeBytes: Int = DefaultPoolSizeBytes,
    private val random: Random = Random.Default,
) {
    val sizeBytes: Int = poolSizeBytes

    private val pool: String =
        buildString(poolSizeBytes) {
            repeat(poolSizeBytes) {
                append(random.nextInt(PrintableAsciiStart, PrintableAsciiEndExclusive).toChar())
            }
        }

    fun slice(size: Int): String {
        require(size in 0..pool.length) {
            "Padding slice size must be between 0 and ${pool.length} bytes."
        }
        if (size == 0) return ""
        val start = random.nextInt(pool.length - size + 1)
        return pool.substring(start, start + size)
    }

    companion object {
        const val DefaultPoolSizeBytes: Int = 100 * 1024

        fun deterministicRandom(seed: Int): Random = Random(seed)

        private const val PrintableAsciiStart = 0x20
        private const val PrintableAsciiEndExclusive = 0x7f
    }
}
