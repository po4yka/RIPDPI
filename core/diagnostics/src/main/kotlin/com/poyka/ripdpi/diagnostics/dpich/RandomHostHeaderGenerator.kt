package com.poyka.ripdpi.diagnostics.dpich

import java.util.concurrent.ThreadLocalRandom

object RandomHostHeaderGenerator {
    private const val HostLabelLength = 15
    private const val LeadingLetters = "abcdefghijklmnopqrstuvwxyz"
    private const val HostChars = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val Tlds = arrayOf("com", "net", "org", "io", "info")

    fun next(): String {
        val random = ThreadLocalRandom.current()
        val label =
            buildString(capacity = HostLabelLength) {
                append(LeadingLetters[random.nextInt(LeadingLetters.length)])
                repeat(HostLabelLength - 1) {
                    append(HostChars[random.nextInt(HostChars.length)])
                }
            }
        return "$label.${Tlds[random.nextInt(Tlds.size)]}"
    }
}
