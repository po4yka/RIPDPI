package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.decodeAwgCohortCatalog
import com.poyka.ripdpi.data.awg.matchCohortForConf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An imported `.conf` whose obfuscation params byte-match a known preset is
 * tagged with that preset's id; a `.conf` one value off is tagged `Custom`.
 */
class AwgCohortMatchOnImportTest {
    private fun catalog() =
        decodeAwgCohortCatalog(
            javaClass.classLoader!!
                .getResourceAsStream("awg-cohorts.json")!!
                .bufferedReader()
                .use { it.readText() },
        )

    /** A `.conf` whose AWG params exactly match the rtk_south preset. */
    private val rtkSouthConf =
        """
        [Interface]
        PrivateKey = bWF0Y2gtb24taW1wb3J0LXByaXZhdGUta2V5LWZpeA==
        Address = 10.0.0.2/32
        Jc = 4
        Jmin = 10
        Jmax = 50
        S1 = 0
        S2 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4

        [Peer]
        PublicKey = bWF0Y2gtb24taW1wb3J0LXB1YmxpYy1rZXktZml4
        AllowedIPs = 0.0.0.0/0
        Endpoint = rtk.example.com:51820
        """.trimIndent()

    @Test
    fun `a conf whose obfuscation params byte-match rtk_south is tagged rtk_south`() {
        val tag = matchCohortForConf(rtkSouthConf, catalog())

        assertEquals("rtk_south", tag)
    }

    @Test
    fun `a conf one byte off rtk_south is tagged Custom`() {
        // Jmin 11 instead of 10 — one value off.
        val oneOff = rtkSouthConf.replace("Jmin = 10", "Jmin = 11")

        val tag = matchCohortForConf(oneOff, catalog())

        assertEquals("Custom", tag)
    }

    @Test
    fun `a conf with a different H value is tagged Custom`() {
        val differentH = rtkSouthConf.replace("H4 = 4", "H4 = 5")

        val tag = matchCohortForConf(differentH, catalog())

        assertEquals("Custom", tag)
    }

    @Test
    fun `a vanilla WireGuard conf with no AWG keys is tagged Custom`() {
        val vanilla =
            """
            [Interface]
            PrivateKey = dmFuaWxsYS1jb25mLXByaXZhdGUta2V5LWZpeHR1cmU=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = dmFuaWxsYS1jb25mLXB1YmxpYy1rZXktZml4dHVyZQ==
            AllowedIPs = 0.0.0.0/0
            Endpoint = plain.example.com:51820
            """.trimIndent()

        val tag = matchCohortForConf(vanilla, catalog())

        assertEquals("Custom", tag)
    }

    @Test
    fun `a structurally malformed conf is tagged Custom, not a crash`() {
        val tag = matchCohortForConf("[Interface]\nbroken line", catalog())

        assertEquals("Custom", tag)
    }

    @Test
    fun `a conf matching the default cohort is tagged default`() {
        val cat = catalog()
        val default = cat.find("default")!!
        val defaultConf =
            """
            [Interface]
            PrivateKey = ZGVmYXVsdC1jb2hvcnQtcHJpdmF0ZS1rZXktZml4
            Address = 10.0.0.2/32
            Jc = ${default.jc}
            Jmin = ${default.jmin}
            Jmax = ${default.jmax}
            S1 = ${default.s1}
            S2 = ${default.s2}
            H1 = ${default.h1}
            H2 = ${default.h2}
            H3 = ${default.h3}
            H4 = ${default.h4}

            [Peer]
            PublicKey = ZGVmYXVsdC1jb2hvcnQtcHVibGljLWtleS1maXg=
            AllowedIPs = 0.0.0.0/0
            Endpoint = default.example.com:51820
            """.trimIndent()

        // default and home_isp_broad share numeric params; matching returns the
        // first catalog entry with those params, which is "default".
        assertEquals("default", matchCohortForConf(defaultConf, cat))
    }
}
