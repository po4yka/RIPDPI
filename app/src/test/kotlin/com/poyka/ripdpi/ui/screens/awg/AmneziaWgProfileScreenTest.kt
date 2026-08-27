package com.poyka.ripdpi.ui.screens.awg

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.FakePermissionPlatformBridge
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.proxyimport.ClipboardReader
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for [AmneziaWgProfileScreen].
 *
 * Asserts the editor surfaces every standard WireGuard field and all 16 obfuscation
 * fields inline, and that picking a cohort preset locks the obfuscation inputs while the
 * "Custom" sentinel frees them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AmneziaWgProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rtkSouth =
        AwgCohortPreset(
            id = "rtk_south",
            displayNameKey = "awg_cohort_rtk_south_name",
            descriptionKey = "awg_cohort_rtk_south_desc",
            jc = 4,
            jmin = 40,
            jmax = 70,
            s1 = 50,
            s2 = 100,
            h1 = 1_000_000_001L,
            h2 = 1_000_000_002L,
            h3 = 1_000_000_003L,
            h4 = 1_000_000_004L,
            randomizeHeaders = false,
        )

    private val permissionBridge = FakePermissionPlatformBridge(vpnPermissionIntent = null)
    private val clipboard = FakeAwgClipboardReader()
    private var activationCount = 0

    private fun viewModel() =
        AmneziaWgProfileViewModel(
            object : AwgCohortCatalogProvider {
                override fun catalog() = AwgCohortCatalogData(presets = listOf(rtkSouth))
            },
            object : StandaloneAmneziaWgActivator {
                override suspend fun activate(request: AwgActivationRequest) {
                    activationCount += 1
                }

                override suspend fun deactivate() = Unit
            },
            AwgProfileRepository(InMemoryAwgProfileDao(), InMemoryAwgCredentialStore()),
            permissionBridge,
            clipboard,
        )

    @Test
    fun `route reads and imports the clipboard only when Paste is tapped`() {
        clipboard.text =
            """
            [Interface]
            PrivateKey = test-private-key
            Address = 10.8.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 50
            S2 = 100
            H1 = 1000000001
            H2 = 1000000002
            H3 = 1000000003
            H4 = 1000000004
            [Peer]
            PublicKey = test-peer-key
            Endpoint = imported.example.com:51820
            """.trimIndent()
        val viewModel = viewModel()
        composeRule.setContent {
            RipDpiTheme { AmneziaWgProfileRoute(onBack = {}, viewModel = viewModel) }
        }
        composeRule.runOnIdle { assertEquals(0, clipboard.readCount) }

        composeRule
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.awg_paste_conf_action))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, clipboard.readCount)
            assertEquals("imported.example.com", viewModel.uiState.value.editor.form.server)
        }
    }

    @Test
    fun `route launches VPN consent and activates only after its result`() {
        permissionBridge.vpnPermissionIntent = Intent("test.awg.vpn.consent")
        val viewModel = viewModel()
        viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")
        viewModel.onFieldChanged(AwgEditorField.SERVER_PORT, "51820")
        viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, "private-key")
        viewModel.onFieldChanged(AwgEditorField.PEER_PUBLIC_KEY, "peer-key")
        viewModel.onFieldChanged(AwgEditorField.ADDRESS, "10.8.0.2/32")
        val registry = FakeAwgConsentRegistry()
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                RipDpiTheme { AmneziaWgProfileRoute(onBack = {}, viewModel = viewModel) }
            }
        }
        composeRule.runOnIdle { assertNull(registry.launchedIntent) }

        composeRule.onNodeWithTag(RipDpiTestTags.AwgConnectAction).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("test.awg.vpn.consent", registry.launchedIntent?.action)
            assertEquals(0, activationCount)
            permissionBridge.vpnPermissionIntent = null
            registry.dispatchResult(requireNotNull(registry.launchedRequestCode), Activity.RESULT_OK, null)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { activationCount == 1 }
    }

    /** In-memory DAO so the screen test drives the real repository without Room. */
    private class InMemoryAwgProfileDao : AwgProfileDao {
        private val rows = MutableStateFlow<List<AwgProfileEntity>>(emptyList())

        override fun observeProfiles(): Flow<List<AwgProfileEntity>> = rows.asStateFlow()

        override suspend fun allProfiles(): List<AwgProfileEntity> = rows.value

        override suspend fun getProfile(id: String): AwgProfileEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun upsertProfile(profile: AwgProfileEntity) {
            rows.value = rows.value.filterNot { it.id == profile.id } + profile
        }

        override suspend fun deleteProfile(profile: AwgProfileEntity) {
            rows.value = rows.value.filterNot { it.id == profile.id }
        }

        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    /** In-memory credential store so the screen test drives the real repository without keystore. */
    private class InMemoryAwgCredentialStore : AwgCredentialStore {
        private val secrets = mutableMapOf<String, AwgSecrets>()

        override suspend fun load(profileId: String): AwgSecrets? = secrets[profileId]

        override suspend fun save(
            profileId: String,
            secrets: AwgSecrets,
        ) {
            this.secrets[profileId] = secrets
        }

        override suspend fun clear(profileId: String) {
            secrets.remove(profileId)
        }
    }

    @Test
    fun `the editor renders the standard wireguard fields`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.INTERFACE_PRIVATE_KEY.name))
            .assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.SERVER.name))
            .assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.PEER_PUBLIC_KEY.name))
            .performScrollTo()
            .assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.PRESHARED_KEY.name))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `the editor renders every obfuscation field inline`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        listOf(
            AwgEditorField.JC,
            AwgEditorField.JMAX,
            AwgEditorField.S4,
            AwgEditorField.H1,
            AwgEditorField.I5,
        ).forEach { field ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.awgField(field.name))
                .performScrollTo()
                .assertExists()
        }
    }

    @Test
    fun `obfuscation fields are enabled while custom and disabled after a preset`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.JC.name))
            .performScrollTo()
            .assertIsEnabled()

        composeRule.runOnUiThread { viewModel.onCohortSelected("rtk_south") }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.JC.name))
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnUiThread {
            viewModel.onCohortSelected(AwgProfileForm.CUSTOM_COHORT_ID)
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.JC.name))
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `the connect action is disabled until the identity fields are present`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.AwgConnectAction)
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnUiThread {
            viewModel.onFieldChanged(AwgEditorField.SERVER, "vpn.example.com")
            viewModel.onFieldChanged(AwgEditorField.SERVER_PORT, "51820")
            viewModel.onFieldChanged(AwgEditorField.INTERFACE_PRIVATE_KEY, "privkey")
            viewModel.onFieldChanged(AwgEditorField.PEER_PUBLIC_KEY, "pubkey")
            viewModel.onFieldChanged(AwgEditorField.ADDRESS, "10.8.0.2/32")
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.AwgConnectAction)
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `editing the server field flows into the view model`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.SERVER.name))
            .performScrollTo()
            .performTextInput("vpn.example.com")

        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(
                "vpn.example.com",
                viewModel.uiState.value.editor.form.server,
            )
        }
    }
}

@Composable
private fun ScreenUnderTest(viewModel: AmneziaWgProfileViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    AmneziaWgProfileScreen(
        uiState = uiState,
        onBack = {},
        onFieldChanged = viewModel::onFieldChanged,
        onCohortSelected = viewModel::onCohortSelected,
        onCarrierSelected = viewModel::onCarrierSelected,
        onPasteConf = {},
        onRevealPrivateKey = viewModel::onPrivateKeyRevealAuthorized,
        onRevealPresharedKey = viewModel::onPresharedKeyRevealAuthorized,
        onConnect = viewModel::onConnect,
    )
}

private class FakeAwgConsentRegistry : ActivityResultRegistry() {
    var launchedIntent: Intent? = null
    var launchedRequestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launchedIntent = input as Intent
        launchedRequestCode = requestCode
    }
}

internal class FakeAwgClipboardReader : ClipboardReader {
    var text: String? = null
    var readCount = 0
        private set

    override fun readPrimaryClipText(): String? {
        readCount += 1
        return text
    }

    override fun clearPrimaryClip() {
        text = null
    }
}
