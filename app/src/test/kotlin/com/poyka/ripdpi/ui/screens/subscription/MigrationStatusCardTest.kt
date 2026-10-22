package com.poyka.ripdpi.ui.screens.subscription

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.subscription.MigrationCheckResult
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MigrationStatusCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `received state never probes or exports until explicit actions`() {
        var checks = 0
        var shares = 0
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RipDpiCard {
                        MigrationStatusCard(state(), { tag, url ->
                            assertEquals("opaque-new", tag)
                            assertEquals("https://probe.example/body", url)
                            checks++
                        }, { shares++ })
                    }
                }
            }
        }
        assertEquals(0, checks)
        assertEquals(0, shares)
        composeRule.onNodeWithText(text(R.string.migration_received)).assertIsDisplayed()
        composeRule.onNodeWithTag("migration-check-0").performScrollTo().performClick()
        assertEquals(1, checks)
        composeRule.onNodeWithTag("migration-export").performScrollTo()
        capture("migration-received")
        composeRule.onNodeWithTag("migration-export").performClick()
        assertEquals(1, shares)
    }

    @Test
    fun `busy AWG state disables actions and explains exact profile requirement`() {
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RipDpiCard {
                        MigrationStatusCard(
                            state().copy(
                                busy = true,
                                actionsEnabled = false,
                                result = MigrationCheckResult.ConnectAwgFirst,
                            ),
                            { _, _ -> error("unexpected test") },
                            { error("unexpected export") },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("migration-check-0").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.migration_connect_awg)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("migration-export").performScrollTo().assertIsNotEnabled()
        capture("migration-connect-awg")
    }

    @Test
    fun `tested state is visibly separate from receipt and remains explicit export`() {
        var shares = 0
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RipDpiCard {
                        MigrationStatusCard(state().copy(tested = true), { _, _ -> }, { shares++ })
                    }
                }
            }
        }
        composeRule.onNodeWithText(text(R.string.migration_tested)).assertIsDisplayed()
        assertEquals(0, shares)
        composeRule.onNodeWithTag("migration-export").performScrollTo()
        capture("migration-tested")
        composeRule.onNodeWithTag("migration-export").performClick()
        assertEquals(1, shares)
    }

    @Test
    fun `explicit export shares exact JSON through narrow read-only content grant`() {
        val context = RuntimeEnvironment.getApplication()
        val body = """{"schema_version":1,"state":"received"}"""
        val intent = migrationReceiptShareIntent(context, body)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertTrue(uri.path!!.contains("migration_receipts"))
        assertEquals(
            body,
            context.contentResolver
                .openInputStream(uri)!!
                .bufferedReader()
                .use { it.readText() },
        )
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule.onRoot().captureToImage().asAndroidBitmap().compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    output,
                ),
            )
        }
    }

    private fun state() =
        MigrationStatusUi(
            "delivery",
            false,
            listOf(MigrationTargetUi("opaque-new", true)),
            "https://probe.example/body",
            false,
            true,
            null,
        )

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
