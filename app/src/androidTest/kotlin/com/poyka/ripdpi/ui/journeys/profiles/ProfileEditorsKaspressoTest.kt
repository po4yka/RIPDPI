package com.poyka.ripdpi.ui.journeys.profiles

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsEditorField
import com.poyka.ripdpi.ui.screens.awg.AwgEditorField
import com.poyka.ripdpi.ui.screens.mieru.MieruEditorField
import com.poyka.ripdpi.ui.screens.ssh.SshEditorField
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

// Documentation-range hosts and obviously fake credentials: no journey reaches a server.
private const val AnyTlsName = "Journey AnyTLS"
private const val AnyTlsHost = "203.0.113.11"
private const val AnyTlsPort = "8443"
private const val AnyTlsPasswordFixture = "anytls-journey-pass"
private const val AnyTlsSni = "anytls.example.com"

private const val MieruName = "Journey Mieru"
private const val MieruHost = "203.0.113.12"
private const val MieruPort = "2999"
private const val MieruUser = "mieru-journey-user"
private const val MieruPasswordFixture = "mieru-journey-pass"
private const val MieruMtu = "1400"

private const val SshName = "Journey SSH"
private const val SshHost = "203.0.113.13"
private const val SshPort = "2222"
private const val SshUser = "ssh-journey-user"
private const val SshPasswordFixture = "ssh-journey-pass"

private const val AwgHost = "203.0.113.14"
private const val AwgPort = "51821"
private const val AwgAddress = "10.66.0.2/32"

// Well-formed WireGuard keys (32 bytes in base64) that belong to nobody.
private val AwgInterfaceSecret = "C".repeat(42) + "A="
private val AwgPeerPublic = "D".repeat(42) + "A="

/**
 * The relay profile editors once reported a successful save without storing anything. These journeys
 * save each editor with real taps and then read the stored profile back: through the editor that the
 * profile row reopens where the app has one, otherwise through the AmneziaWG profile store.
 */
@HiltAndroidTest
class ProfileEditorsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var awgProfiles: AwgProfileRepository

    @Test
    fun savedMieruProfileReopensWithTheEnteredValues() =
        run {
            step("The Add profile menu opens an empty Mieru editor that cannot save yet") {
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    createProfile(Route.MieruProfile)
                }
                with(MieruEditorPage(composeRule)) {
                    awaitScreen()
                    assertSaveDisabled()
                }
            }
            step("Saving a filled Mieru editor returns to Config and lists the profile") {
                with(MieruEditorPage(composeRule)) {
                    type(field(MieruEditorField.DISPLAY_NAME.name), MieruName)
                    type(field(MieruEditorField.SERVER.name), MieruHost)
                    type(field(MieruEditorField.SERVER_PORT.name), MieruPort)
                    type(field(MieruEditorField.USERNAME.name), MieruUser)
                    type(field(MieruEditorField.PASSWORD.name), MieruPasswordFixture)
                    type(field(MieruEditorField.MTU.name), MieruMtu)
                    tapSave()
                    waitGone(screenTag, 15_000)
                }
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    awaitProfileListed("Mieru")
                }
            }
            step("The profile row reopens the editor with every saved value") {
                with(ProfileListPage(composeRule)) { openProfile("Mieru") }
                with(MieruEditorPage(composeRule, editing = true)) {
                    awaitScreen()
                    awaitText(field(MieruEditorField.SERVER.name), MieruHost)
                    assertEquals(MieruPort, text(field(MieruEditorField.SERVER_PORT.name)))
                    assertEquals(MieruUser, text(field(MieruEditorField.USERNAME.name)))
                    assertEquals(MieruMtu, text(field(MieruEditorField.MTU.name)))
                    revealSecret(field(MieruEditorField.PASSWORD.name))
                    awaitText(field(MieruEditorField.PASSWORD.name), MieruPasswordFixture)
                }
            }
        }

    @Test
    fun savedSshProfileReopensWithTheEnteredValues() =
        run {
            step("The Add profile menu opens an empty SSH editor that cannot save yet") {
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    createProfile(Route.SshProfile)
                }
                with(SshEditorPage(composeRule)) {
                    awaitScreen()
                    assertSaveDisabled()
                }
            }
            step("Saving a filled SSH editor returns to Config and lists the profile") {
                with(SshEditorPage(composeRule)) {
                    type(field(SshEditorField.DISPLAY_NAME.name), SshName)
                    type(field(SshEditorField.SERVER.name), SshHost)
                    type(field(SshEditorField.SERVER_PORT.name), SshPort)
                    type(field(SshEditorField.USERNAME.name), SshUser)
                    type(field(SshEditorField.PASSWORD.name), SshPasswordFixture)
                    tapSave()
                    waitGone(screenTag, 15_000)
                }
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    awaitProfileListed("SSH")
                }
            }
            step("The profile row reopens the editor with every saved value") {
                with(ProfileListPage(composeRule)) { openProfile("SSH") }
                with(SshEditorPage(composeRule, editing = true)) {
                    awaitScreen()
                    awaitText(field(SshEditorField.SERVER.name), SshHost)
                    assertEquals(SshPort, text(field(SshEditorField.SERVER_PORT.name)))
                    assertEquals(SshUser, text(field(SshEditorField.USERNAME.name)))
                    revealSecret(field(SshEditorField.PASSWORD.name))
                    awaitText(field(SshEditorField.PASSWORD.name), SshPasswordFixture)
                }
            }
        }

    @Test
    fun savedAnyTlsProfileReopensWithTheEnteredEndpoint() =
        run {
            step("The Add profile menu opens an empty AnyTLS editor that cannot save yet") {
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    createProfile(Route.AnyTlsProfile)
                }
                with(AnyTlsEditorPage(composeRule)) {
                    awaitScreen()
                    assertSaveDisabled()
                }
            }
            step("Saving a filled AnyTLS editor returns to Config and lists the profile") {
                with(AnyTlsEditorPage(composeRule)) {
                    type(field(AnyTlsEditorField.DISPLAY_NAME.name), AnyTlsName)
                    type(field(AnyTlsEditorField.SERVER.name), AnyTlsHost)
                    type(field(AnyTlsEditorField.SERVER_PORT.name), AnyTlsPort)
                    type(field(AnyTlsEditorField.PASSWORD.name), AnyTlsPasswordFixture)
                    type(field(AnyTlsEditorField.SNI.name), AnyTlsSni)
                    tapSave()
                    waitGone(screenTag, 15_000)
                }
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    awaitProfileListed("AnyTLS")
                }
            }
            step("The profile row opens the mode editor with the saved endpoint") {
                with(ProfileListPage(composeRule)) { openProfile("AnyTLS") }
                with(RelayModeEditorPage(composeRule)) {
                    awaitScreen()
                    awaitShownText(AnyTlsHost)
                    awaitShownText(AnyTlsPort)
                    awaitShownText(AnyTlsSni)
                }
            }
        }

    @Test
    fun connectingAnAmneziaWgProfileStoresTheEnteredValues() =
        run {
            step("The Add profile menu opens the AmneziaWG editor") {
                with(ProfileListPage(composeRule)) {
                    openProfileChoices()
                    createProfile(Route.AmneziaWgProfile())
                }
                with(AmneziaWgEditorPage(composeRule)) { awaitScreen() }
            }
            step("Connect stores the profile with the entered values") {
                with(AmneziaWgEditorPage(composeRule)) {
                    type(AwgEditorField.INTERFACE_PRIVATE_KEY, AwgInterfaceSecret)
                    type(AwgEditorField.SERVER, AwgHost)
                    type(AwgEditorField.SERVER_PORT, AwgPort)
                    type(AwgEditorField.ADDRESS, AwgAddress)
                    type(AwgEditorField.PEER_PUBLIC_KEY, AwgPeerPublic)
                    tapConnect()
                }
                composeRule.waitWithTree("no AmneziaWG profile was stored", 15_000) {
                    runBlocking { awgProfiles.observeProfiles().first() }.isNotEmpty()
                }
                val stored = runBlocking { awgProfiles.observeProfiles().first() }.single().request
                assertEquals(AwgHost, stored.endpointHost)
                assertEquals(AwgPort.toInt(), stored.endpointPort)
                assertEquals(AwgAddress, stored.interfaceAddressV4)
                assertEquals(AwgPeerPublic, stored.peerPublicKey)
                assertEquals(AwgInterfaceSecret, stored.privateKey)
            }
        }
}
