package com.poyka.ripdpi.ui.components.cards

import com.poyka.ripdpi.diagnostics.dpich.AliveState
import com.poyka.ripdpi.diagnostics.dpich.DpiState
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkItem
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only logic tests for [linkPreviewStateFrom]. No Robolectric;
 * exercises the payload->state formatter shape so localized strings
 * combined with payload numeric fields produce the rows the spec card
 * documents.
 */
class RipDpiLinkPreviewCardTest {
    private val strings =
        RipDpiLinkPreviewStrings(
            eyebrowLink = "Generated link",
            eyebrowPayload = "Fragment payload",
            copyLabel = "Copy",
            rowVersion = "Schema version - currently %1\$s",
            rowAsnFormat = "Origin ASN - %1\$s",
            rowAsnRedacted = "Origin ASN - redacted",
            rowTimestampFormat = "Timestamp, in minutes since %1\$s",
            rowCommitFormat = "Strategy-bundle hash - %1\$s",
            rowItemsFormat = "Per-endpoint {alive, dpi} tuples - %1\$d",
            privacyTitle = "Stays on device",
            privacyMessage = "The fragment never leaves the device.",
            privacyEmphasis = "never leaves the device",
        )

    private fun payload(
        commitHash: Int = 0x0a,
        timestampMinutes: Int = 1_217_280,
        asn: Int = 13335,
        itemCount: Int = 4,
    ): ShareLinkPayload =
        ShareLinkPayload(
            commitHash = commitHash,
            timestampMinutes = timestampMinutes,
            asn = asn,
            items =
                List(itemCount) {
                    ShareLinkItem(alive = AliveState.YES, dpi = DpiState.NOT_DETECTED)
                },
        )

    private fun row(
        state: RipDpiLinkPreviewState,
        key: String,
    ): RipDpiLinkPreviewRow = state.rows.single { it.key == key }

    @Test
    fun `commit hash 0x0a renders as two-character zero-padded lowercase hex`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(commitHash = 0x0a),
                fragment = "abc",
                strings = strings,
            )
        val commitRow = row(state, "commit")
        assertTrue(
            "commit row should contain '0a' but was: ${commitRow.value.text}",
            commitRow.value.text.contains("0a"),
        )
    }

    @Test
    fun `commit hash 0xff renders as ff`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(commitHash = 0xff),
                fragment = "abc",
                strings = strings,
            )
        val commitRow = row(state, "commit")
        assertTrue(
            "commit row should contain 'ff' but was: ${commitRow.value.text}",
            commitRow.value.text.contains("ff"),
        )
    }

    @Test
    fun `asn zero uses the redacted label format`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(asn = 0),
                fragment = "abc",
                strings = strings,
            )
        val asnRow = row(state, "asn")
        assertEquals(strings.rowAsnRedacted, asnRow.value.text)
    }

    @Test
    fun `asn 13335 renders contained in the ASN row`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(asn = 13335),
                fragment = "abc",
                strings = strings,
            )
        val asnRow = row(state, "asn")
        assertTrue(
            "asn row should contain '13335' but was: ${asnRow.value.text}",
            asnRow.value.text.contains("13335"),
        )
    }

    @Test
    fun `items count of four renders as 4 in the items row`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(itemCount = 4),
                fragment = "abc",
                strings = strings,
            )
        val itemsRow = row(state, "items[]")
        assertTrue(
            "items row should contain '4' but was: ${itemsRow.value.text}",
            itemsRow.value.text.contains("4"),
        )
    }

    @Test
    fun `fragment string passes through to state unchanged`() {
        val fragment = "eJxkkE2OwjAMha9SeY1m_LONG-STRING-WITH-MIXED-cas3"
        val state =
            linkPreviewStateFrom(
                payload = payload(),
                fragment = fragment,
                strings = strings,
            )
        assertEquals(fragment, state.fragmentLabel)
    }

    @Test
    fun `fragment truncated hint is null when not provided`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(),
                fragment = "abc",
                strings = strings,
            )
        assertNull(state.fragmentTruncatedHint)
    }

    @Test
    fun `fragment truncated hint is preserved when provided`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(),
                fragment = "abc",
                strings = strings,
                fragmentTruncatedHint = "...",
            )
        assertEquals("...", state.fragmentTruncatedHint)
    }

    @Test
    fun `state exposes the localized URL pieces verbatim`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(),
                fragment = "abc",
                strings = strings,
            )
        assertEquals("https://", state.schemeLabel)
        assertEquals("po4yka.github.io/RIPDPI/share", state.hostLabel)
        assertEquals("?v=1", state.queryLabel)
    }

    @Test
    fun `privacy note emphasis substring is preserved`() {
        val state =
            linkPreviewStateFrom(
                payload = payload(),
                fragment = "abc",
                strings = strings,
            )
        assertNotNull(state.privacyNoteEmphasis)
        assertEquals("never leaves the device", state.privacyNoteEmphasis)
    }
}
