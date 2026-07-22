package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorDraftBoundsTest {
    @Test
    fun `utf8 bounds preserve code points without exceeding bytes`() {
        val bounded = ("a".repeat(63) + "😀tail").boundedUtf8(67)

        assertEquals("a".repeat(63) + "😀", bounded)
        assertEquals(67, bounded.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `domain draft bounds bytes and lines`() {
        val tooManyLines = (1..3_000).joinToString("\n") { "rule-$it" }
        val tooManyBytes = "😀".repeat(20_000)

        assertEquals(2_048, boundedDomainBypassDraft(tooManyLines).lineSequence().count())
        assertTrue(boundedDomainBypassDraft(tooManyBytes).toByteArray(Charsets.UTF_8).size <= 64 * 1024)
    }

    @Test
    fun `large drafts are not saved and domain compile is deferred`() {
        val strategy = source("StrategyConfigRoute.kt").readText()
        val domains = source("DomainBypassListScreen.kt").readText()

        assertFalse(strategy.contains("configText by rememberSaveable"))
        assertFalse(domains.contains("text by rememberSaveable"))
        assertFalse(domains.contains("remember(text) { DomainBypassList.compile"))
        assertTrue(domains.contains("withContext(Dispatchers.Default) { DomainBypassList.compile(uiState.text) }"))
    }

    @Test
    fun `transient strategy banner is not stored in saveable state`() {
        val strategy = source("StrategyConfigRoute.kt").readText()

        assertFalse(strategy.contains("banner by rememberSaveable"))
        assertTrue(strategy.contains("banner by remember {"))
    }

    private fun source(name: String): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/$name"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/$name"),
        ).first(File::isFile)
}
