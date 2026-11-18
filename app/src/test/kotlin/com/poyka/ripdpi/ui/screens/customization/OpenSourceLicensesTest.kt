package com.poyka.ripdpi.ui.screens.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenSourceLicensesTest {
    private val notices = loadLicenseNotices(RuntimeEnvironment.getApplication())

    @Test
    fun everyNoticeCarriesText() {
        // An offline AboutLibraries export drops SPDX texts and leaves only names.
        val blank = notices.filter { it.text.isBlank() }.map { it.title }
        assertTrue("notices without text: $blank", blank.isEmpty())
        assertTrue(notices.size > 100)
    }

    @Test
    fun nativeComponentsCarryTheirLicenseTexts() {
        mapOf(
            "Go standard library" to "Redistribution and use in source and binary forms",
            "ripdpi-socks5-core" to "Jonathan Dizdarevic",
        ).forEach { (title, expected) ->
            val text = notices.single { it.title == title }.text
            assertTrue("$title lacks '$expected'", text.contains(expected))
        }
    }

    @Test
    fun workspaceCratesAreNotListed() {
        val workspaceCrates = notices.filter { it.subtitle.contains("AGPL-3.0-only") }.map { it.title }
        assertTrue("workspace crates listed: $workspaceCrates", workspaceCrates.isEmpty())
    }

    @Test
    fun packageNoticesResolveSharedLicenseTexts() {
        // Same shape as the generated notices/rust-crates.json and notices/go-modules.json.
        val json =
            """
            {"licenses":[{"text":"Mozilla Public License Version 2.0"},{"text":"Copyright (c) 2013 John Howard Palevich"}],
             "packages":[
               {"name":"github.com/xtls/xray-core","version":"1.2.3","license":"MPL-2.0","note":"Source is available.","licenses":[0]},
               {"name":"github.com/xtls/reality","version":"0.1.0","license":"MPL-2.0","licenses":[0]},
               {"name":"github.com/jackpal/go-nat-pmp","version":"v1.1.0","license":"","licenses":[1]}]}
            """.trimIndent()

        val parsed = parsePackageNotices(json, idPrefix = "go").associateBy { it.id }

        val xrayCore = parsed.getValue("go/github.com/xtls/xray-core@1.2.3")
        assertEquals("github.com/xtls/xray-core", xrayCore.title)
        assertEquals("1.2.3 · MPL-2.0", xrayCore.subtitle)
        assertEquals("Source is available.\n\nMozilla Public License Version 2.0", xrayCore.text)
        assertEquals("Mozilla Public License Version 2.0", parsed.getValue("go/github.com/xtls/reality@0.1.0").text)
        assertEquals("v1.1.0", parsed.getValue("go/github.com/jackpal/go-nat-pmp@v1.1.0").subtitle)
    }
}
