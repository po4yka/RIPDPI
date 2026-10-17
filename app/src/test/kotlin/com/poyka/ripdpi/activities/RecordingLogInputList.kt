package com.poyka.ripdpi.activities

internal class RecordingLogInputList<T>(
    private val values: List<T>,
) : AbstractList<T>() {
    @Volatile
    var readCount = 0

    var contentComparisonCount = 0
        private set

    @Volatile
    var lastReadThread: Thread? = null
        private set

    override val size: Int get() = values.size

    // Compare backing values so mapper traversal and content equality have separate counters.
    override fun equals(other: Any?): Boolean =
        when {
            this === other -> {
                true
            }

            other is RecordingLogInputList<*> -> {
                contentComparisonCount += 1
                values == other.values
            }

            else -> {
                super.equals(other)
            }
        }

    override fun hashCode(): Int = values.hashCode()

    override fun get(index: Int): T {
        readCount += 1
        lastReadThread = Thread.currentThread()
        return values[index]
    }
}
