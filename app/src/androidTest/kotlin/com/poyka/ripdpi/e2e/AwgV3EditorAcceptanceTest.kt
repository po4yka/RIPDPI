package com.poyka.ripdpi.e2e

import android.app.KeyguardManager
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.automation.AutomationLaunchContract
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.injectBeforeActivityRule
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proxyimport.ImportHandlerActivity
import com.poyka.ripdpi.proxyimport.ImportLaunchRoute
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.security.SecretRevealPinViewModel
import com.poyka.ripdpi.ui.screens.awg.AwgEditorField
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

/** Device rendering of the production import route. PNGs are evidence, never golden fixtures. */
@HiltAndroidTest
class AwgV3EditorAcceptanceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val injection = hilt.injectBeforeActivityRule()

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule(ComposeUiTestConfig())

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var serviceState: ServiceStateStore

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var originalLocales: LocaleListCompat? = null
    private var originalSettings: AppSettings? = null
    private var activity: ActivityScenario<MainActivity>? = null
    private val images = JSONArray()
    private lateinit var directory: File
    private lateinit var locale: String
    private var secretScope = "masked_only"
    private var fontScale = 0f
    private var direction = View.LAYOUT_DIRECTION_LTR

    @Before
    fun prepareOwnedEditor() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Editor acceptance requires an explicit owned invocation",
            listOf("awgUiSourceSha", "awgUiNonce", "awgUiLocale").any(args::containsKey),
        )
        check(isLikelyEmulator()) { "Editor render acceptance requires a leased emulator" }
        assertEquals(
            "Only a stopped disposable app can be owned by this UI test",
            AppStatus.Halted,
            serviceState.status.value.first,
        )
        originalSettings = runBlocking { settings.snapshot() }
        runBlocking { settings.update { onboardingComplete = true } }
    }

    @After
    fun restoreOwnedEditor() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    activity?.close()
                } finally {
                    originalLocales?.let { original ->
                        instrumentation.runOnMainSync {
                            val manager = context.getSystemService(LocaleManager::class.java)
                            val expected = LocaleList.forLanguageTags(original.toLanguageTags())
                            manager.applicationLocales = expected
                            assertEquals("Restore the original OS locale", expected, manager.applicationLocales)
                        }
                    }
                }
            } else {
                try {
                    // AppCompat needs a live delegate to persist locales below API 33.
                    originalLocales?.let { original ->
                        activity?.onActivity {
                            AppCompatDelegate.setApplicationLocales(original)
                            assertEquals(original, AppCompatDelegate.getApplicationLocales())
                        }
                    }
                } finally {
                    activity?.close()
                }
            }
        } finally {
            originalSettings?.let { value -> runBlocking { settings.replace(value) } }
        }
    }

    @Test
    fun productionEditorStates() {
        val args = InstrumentationRegistry.getArguments()
        val source = requireNotNull(args.getString("awgUiSourceSha"))
        val nonce = requireNotNull(args.getString("awgUiNonce"))
        locale = args.getString("awgUiLocale") ?: "en"
        require(source.matches(Regex("[0-9a-f]{40}")) && source.startsWith(BuildConfig.GIT_COMMIT))
        require(nonce.matches(Regex("[a-zA-Z0-9_-]{8,80}")) && locale in setOf("en", "ar", "fa", "de", "ru"))
        directory = File(context.getExternalFilesDir(null), "awg31-editor/$nonce/$locale")
        check(directory.mkdirs()) { "Use a fresh editor evidence nonce" }
        val token = requireNotNull(PendingProxyImportStore.process.stageAmneziaWgConf(publicFixtureConf()))
        activity =
            ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .putExtra(AutomationLaunchContract.Enabled, false)
                    .putExtra(ImportHandlerActivity.EXTRA_IMPORT_ROUTE, ImportLaunchRoute.AMNEZIAWG_EDITOR)
                    .putExtra(ImportHandlerActivity.EXTRA_AMNEZIAWG_IMPORT_TOKEN, token),
            )
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(tag(AwgEditorField.HEADER_PROTECTION_KEY))).fetchSemanticsNodes().isNotEmpty()
        }
        activity!!.onActivity {
            originalLocales = AppCompatDelegate.getApplicationLocales()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale))
        }
        compose.waitUntil(10_000) {
            var applied = false
            activity!!.onActivity {
                applied = it.resources.configuration.locales[0]
                    .language == locale
            }
            applied
        }
        activity!!.onActivity {
            val expected = if (locale in setOf("ar", "fa")) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            fontScale = it.resources.configuration.fontScale
            direction = it.resources.configuration.layoutDirection
            assertEquals(
                "Production editor must apply the locale direction",
                expected,
                it.resources.configuration.layoutDirection,
            )
        }
        verifyFieldsAndSecrets()
        verifyInvalidDraftAndPreset()
        val manifest =
            JSONObject()
                .put(
                    "version",
                    "awg31_editor_render_v2",
                ).put("source_sha", source)
                .put("build_commit", BuildConfig.GIT_COMMIT)
                .put("nonce", nonce)
                .put("locale", locale)
                .put("secret_scope", secretScope)
                .put("font_scale", fontScale.toDouble())
                .put("layout_direction", direction)
                .put("scope", "emulator_production_editor")
                .put("pixel_scope", "application_compose_content_excluding_system_ime")
                .put("keyboard_oracle", "WindowInsetsCompat.Type.ime visibility")
                .put("application_boundary", "Shared Hilt test application; production editor and import callbacks")
                .put("images", images)
        val file = File(directory, "manifest.json")
        manifest.put("manifest_path", file.absolutePath)
        file.writeText(manifest.toString(2))
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("awgEditorEvidence", manifest.toString())
            },
        )
    }

    private fun verifyFieldsAndSecrets() {
        val hp = field(AwgEditorField.HEADER_PROTECTION_KEY)
        hp.performScrollTo().assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        capture("normal-hp")
        var canReveal = false
        activity!!.onActivity { host ->
            assertTrue(
                "Production editor must retain FLAG_SECURE",
                host.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
            val pin = ViewModelProvider(host)[SecretRevealPinViewModel::class.java]
            canReveal =
                !host.getSystemService(KeyguardManager::class.java).isDeviceSecure && !runBlocking { pin.hasPin() }
        }
        if (canReveal) {
            compose
                .onNode(
                    hasContentDescription(text(R.string.ssh_reveal_secret_action)) and
                        hasAnyAncestor(hasTestTag(tag(AwgEditorField.HEADER_PROTECTION_KEY))),
                    useUnmergedTree = true,
                ).performClick()
            compose.waitUntil(5_000) {
                !field(
                    AwgEditorField.HEADER_PROTECTION_KEY,
                ).fetchSemanticsNode().config.contains(SemanticsProperties.Password)
            }
            field(AwgEditorField.HEADER_PROTECTION_KEY).assertTextContains(key(3))
            capture("public-fixture-hp-revealed")
            activity!!.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
            field(
                AwgEditorField.HEADER_PROTECTION_KEY,
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            capture("hp-relocked")
            secretScope = "public_fixture_revealed_and_lifecycle_relocked"
        } else {
            secretScope = "masked_authentication_required"
        }
        listOf(
            AwgEditorField.CONTENT_PADDING_ADDITION,
            AwgEditorField.REKEY_AFTER_TIME,
            AwgEditorField.REKEY_TIMEOUT,
            AwgEditorField.REJECT_AFTER_TIME,
            AwgEditorField.KEEPALIVE_TIMEOUT,
            AwgEditorField.MAX_HANDSHAKE_ATTEMPTS,
            AwgEditorField.RANDOM_TRAILERS,
            AwgEditorField.DISABLE_COOKIES,
        ).forEach {
            field(it).performScrollTo().assertIsDisplayed()
            if (it == AwgEditorField.REJECT_AFTER_TIME) capture("normal-v3-timers")
        }
        field(AwgEditorField.RANDOM_TRAILERS).assertIsOn()
        field(AwgEditorField.DISABLE_COOKIES).assertIsOff()
        capture("normal-v3-bottom")
        mapOf(
            AwgEditorField.CONTENT_PADDING_ADDITION to "16",
            AwgEditorField.REKEY_AFTER_TIME to "100-120",
            AwgEditorField.REKEY_TIMEOUT to "3-7",
            AwgEditorField.REJECT_AFTER_TIME to "150-180",
            AwgEditorField.KEEPALIVE_TIMEOUT to "5-15",
            AwgEditorField.MAX_HANDSHAKE_ATTEMPTS to "15-20",
        ).forEach { (editorField, value) -> field(editorField).assertTextContains(value) }
        compose.onNodeWithTag(RipDpiTestTags.AwgConnectAction).performScrollTo().assertIsEnabled()
    }

    private fun verifyInvalidDraftAndPreset() {
        field(AwgEditorField.HEADER_PROTECTION_KEY).performScrollTo().performClick().performTextReplacement("not-a-key")
        compose.onNodeWithText(text(R.string.awg_field_invalid)).assertExists()
        compose.onNodeWithTag(RipDpiTestTags.AwgConnectAction).performScrollTo().assertIsNotEnabled()
        field(AwgEditorField.HEADER_PROTECTION_KEY).performScrollTo()
        capture("hp-key-error")
        field(AwgEditorField.HEADER_PROTECTION_KEY).performTextReplacement(key(3))
        field(AwgEditorField.S4).performScrollTo().performClick().performTextReplacement("11")
        compose.onNodeWithText(text(R.string.awg_hp_prefix_min, "S4", 12)).assertExists()
        compose.onNodeWithTag(RipDpiTestTags.AwgConnectAction).performScrollTo().assertIsNotEnabled()
        field(AwgEditorField.S4).performScrollTo()
        capture("hp-prefix-error")
        field(AwgEditorField.S4).performTextReplacement("24")
        field(AwgEditorField.REKEY_TIMEOUT).performScrollTo().performClick().performTextReplacement("12-1")
        compose.onNodeWithText(text(R.string.awg_field_invalid)).assertExists()
        compose.waitUntil(5_000) { imeVisible() }
        capture("range-error-keyboard")
        selectPreset()
        compose.onNodeWithText(text(R.string.awg_cohort_replace_title)).assertExists()
        capture("preset-confirmation")
        compose.onNodeWithText(text(R.string.config_cancel)).performClick()
        field(AwgEditorField.REKEY_TIMEOUT).performScrollTo().assertTextContains("12-1")
        capture("preset-cancel-retains-invalid-draft")
        selectPreset()
        compose.onNodeWithText(text(R.string.awg_cohort_replace_action)).performClick()
        compose.onNodeWithTag(RipDpiTestTags.AwgCohortPicker).performScrollTo().assertTextContains("rtk_south")
        capture("preset-replaced-collapsed")
        compose.onNodeWithText(text(R.string.awg_obfuscation_show_values)).performClick()
        field(AwgEditorField.REKEY_TIMEOUT)
            .performScrollTo()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(""),
                ),
            )
        capture("preset-v3-cleared-locked")
    }

    private fun selectPreset() {
        compose.onNodeWithTag(RipDpiTestTags.AwgCohortPicker).performScrollTo().performClick()
        compose.onNodeWithText("rtk_south").performScrollTo().performClick()
    }

    private fun field(field: AwgEditorField) = compose.onNodeWithTag(tag(field))

    private fun tag(field: AwgEditorField) = RipDpiTestTags.awgField(field.name)

    private fun text(
        resource: Int,
        vararg args: Any,
    ): String {
        var value = ""
        activity!!.onActivity { value = it.getString(resource, *args) }
        return value
    }

    private fun imeVisible(): Boolean {
        var visible = false
        activity!!.onActivity {
            visible =
                ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun capture(state: String) {
        compose.waitForIdle()
        assertEquals("Editor rendering must not activate a service", AppStatus.Halted, serviceState.status.value.first)
        activity!!.onActivity {
            assertTrue(
                "Production editor must retain FLAG_SECURE",
                it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
        val dialogs = compose.onAllNodes(isDialog())
        val dialogCount = dialogs.fetchSemanticsNodes().size
        assertEquals(
            "The capture must select the expected window",
            if (state == "preset-confirmation") 1 else 0,
            dialogCount,
        )
        // A dialog's semantics root is above IsDialog; captureToImage needs the dialog node to select its own Window.
        val roots = if (dialogCount == 1) dialogs else compose.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        check(count > 0)
        repeat(count) { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            val first = bitmap.getPixel(0, 0)
            val samples = (0 until bitmap.width * bitmap.height step maxOf(1, bitmap.width * bitmap.height / 4096))
            check(
                samples.any {
                    bitmap.getPixel(it % bitmap.width, it / bitmap.width) != first
                },
            ) { "Blank editor capture: $state/$index" }
            val file = File(directory, "$state-$index.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            images.put(
                JSONObject()
                    .put("state", state)
                    .put("surface", if (dialogCount == 1) "dialog" else "activity")
                    .put("ime_visible", imeVisible())
                    .put("secure_window", true)
                    .put("root", index)
                    .put("path", file.absolutePath)
                    .put(
                        "sha256",
                        MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(file.readBytes())
                            .joinToString("") { "%02x".format(it) },
                    ).put("width", bitmap.width)
                    .put("height", bitmap.height),
            )
        }
    }

    companion object {
        // Fixed public fixture credentials, never a user profile or an activation target.
        private fun key(value: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { value.toByte() })

        private fun publicFixtureConf() =
            """
            [Interface]
            PrivateKey = ${key(7)}
            Address = 10.77.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 64
            Jmax = 96
            S1 = 12
            S2 = 20
            S3 = 16
            S4 = 24
            H1 = 100-199
            H2 = 200-299
            H3 = 300-399
            H4 = 400-499
            HeaderProtectionKey = ${key(3)}
            ContentPaddingAddition = 16
            RekeyAfterTime = 100-120
            RekeyTimeout = 3-7
            RejectAfterTime = 150-180
            KeepaliveTimeout = 5-15
            MaxHandshakeAttempts = 15-20
            RandomTrailers = on
            DisableCookies = off
            [Peer]
            PublicKey = ${key(9)}
            Endpoint = 127.0.0.1:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            """.trimIndent()
    }
}
