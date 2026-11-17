package com.poyka.ripdpi.ui.screens.customization

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
            "libXray" to "Apache License",
            "Xray-core" to "Mozilla Public License",
            "gomobile" to "Redistribution and use in source and binary forms",
            "Go standard library" to "Redistribution and use in source and binary forms",
            "ripdpi-socks5-core" to "Jonathan Dizdarevic",
        ).forEach { (title, expected) ->
            val text = notices.single { it.title == title }.text
            assertTrue("$title lacks '$expected'", text.contains(expected))
        }
    }

    @Test
    fun rustCratesCarryTheirLicenseTextsWithoutWorkspaceCrates() {
        val tokio = notices.first { it.title == "tokio" && it.id.startsWith("crates.io/") }
        assertTrue(tokio.text.contains("Permission is hereby granted"))
        val workspaceCrates = notices.filter { it.subtitle.contains("AGPL-3.0-only") }.map { it.title }
        assertTrue("workspace crates listed: $workspaceCrates", workspaceCrates.isEmpty())
    }
}
