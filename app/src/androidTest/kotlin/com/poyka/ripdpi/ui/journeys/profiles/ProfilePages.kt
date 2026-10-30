package com.poyka.ripdpi.ui.journeys.profiles

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.exists
import com.poyka.ripdpi.ui.journeys.reveal
import com.poyka.ripdpi.ui.journeys.tagStartingWith
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.awg.AwgEditorField
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

private const val ProfileRowPrefix = "config-vpn-profile-row-"

/** Config screen entry points to the profile editors and the saved-profile list. */
internal class ProfileListPage(
    rule: JourneyRule,
) : JourneyPage<ProfileListPage>(rule, RipDpiTestTags.screen(Route.Config)) {
    private val profileChange = RipDpiTestTags.ConfigProfileChange
    private val profileChoices = RipDpiTestTags.ConfigProfileChoices
    private val addProfile = RipDpiTestTags.ConfigVpnAddProfile
    private val bottomNavConfig = RipDpiTestTags.bottomNav(Route.Config)

    /** Opens the profile choices on the Config screen, from wherever the app is. */
    fun openProfileChoices() {
        if (!exists(screenTag)) click(bottomNavConfig)
        awaitScreen()
        if (!exists(profileChoices)) click(profileChange)
        waitFor(profileChoices)
    }

    /** Picks [route] from the Add profile menu. */
    fun createProfile(route: Route) {
        click(addProfile)
        click(RipDpiTestTags.configVpnCreateProfile(route))
    }

    /** A saved profile row; rows show the profile kind, for example "Mieru". */
    private fun profileRow(kindLabel: String): SemanticsMatcher =
        tagStartingWith(ProfileRowPrefix) and hasText(kindLabel, substring = true)

    fun awaitProfileListed(
        kindLabel: String,
        timeoutMillis: Long = 15_000,
    ) = rule.waitWithTree("no saved $kindLabel profile row", timeoutMillis) {
        rule.reveal(profileRow(kindLabel))
    }

    fun openProfile(kindLabel: String) {
        awaitProfileListed(kindLabel)
        rule.onAllNodes(profileRow(kindLabel)).onFirst().performClick()
    }
}

/** Shared actions of the AnyTLS, Mieru and SSH editors. */
internal abstract class RelayEditorPage<T : RelayEditorPage<T>>(
    rule: JourneyRule,
    screenTag: String,
    private val kind: String,
) : JourneyPage<T>(rule, screenTag) {
    val save = RipDpiTestTags.relayEditorSave(kind)

    fun field(name: String): String = RipDpiTestTags.relayEditorField(kind, name)

    fun type(
        tag: String,
        value: String,
    ) {
        require(tag)
        node(tag).performTextReplacement(value)
    }

    /** The text the field shows; a hidden secret shows its mask. */
    fun text(tag: String): String {
        require(tag)
        return rule
            .onAllNodes(hasTestTag(tag))
            .onFirst()
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
    }

    fun awaitText(
        tag: String,
        expected: String,
    ) = rule.waitWithTree("field $tag never showed $expected") { text(tag) == expected }

    fun revealSecret(fieldTag: String) = click(RipDpiTestTags.secretReveal(fieldTag))

    fun assertSaveDisabled() {
        require(save)
        node(save).assertIsNotEnabled()
    }

    /** Typing leaves the soft keyboard open, which can move the button under a tap. */
    fun tapSave() = activate(save)
}

internal class AnyTlsEditorPage(
    rule: JourneyRule,
) : RelayEditorPage<AnyTlsEditorPage>(rule, RipDpiTestTags.screen(Route.AnyTlsProfile), RelayKindAnyTls)

internal class MieruEditorPage(
    rule: JourneyRule,
    editing: Boolean = false,
) : RelayEditorPage<MieruEditorPage>(
        rule,
        RipDpiTestTags.screen(if (editing) Route.MieruProfileEdit() else Route.MieruProfile),
        RelayKindMieru,
    )

internal class SshEditorPage(
    rule: JourneyRule,
    editing: Boolean = false,
) : RelayEditorPage<SshEditorPage>(
        rule,
        RipDpiTestTags.screen(if (editing) Route.SshProfileEdit() else Route.SshProfile),
        RelayKindSsh,
    )

internal class AmneziaWgEditorPage(
    rule: JourneyRule,
) : JourneyPage<AmneziaWgEditorPage>(rule, RipDpiTestTags.screen(Route.AmneziaWgProfile())) {
    val connect = RipDpiTestTags.AwgConnectAction

    fun field(field: AwgEditorField): String = RipDpiTestTags.awgField(field.name)

    fun type(
        field: AwgEditorField,
        value: String,
    ) {
        require(field(field))
        node(field(field)).performTextReplacement(value)
    }

    /** Typing leaves the soft keyboard open, which can move the button under a tap. */
    fun tapConnect() = activate(connect)
}

/** The mode editor that opens for a saved AnyTLS profile; its fields carry no tags, so match the shown text. */
internal class RelayModeEditorPage(
    rule: JourneyRule,
) : JourneyPage<RelayModeEditorPage>(rule, RipDpiTestTags.screen(Route.ModeEditor)) {
    fun awaitShownText(value: String) =
        rule.waitWithTree("mode editor never showed $value") { rule.reveal(hasText(value)) }
}
