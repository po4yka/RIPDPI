package com.poyka.ripdpi.ui.journeys.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.inject.Inject

private const val BackupRuleName = "Journey backup rule"
private const val BackupRuleDomain = "journey-backup.example.com"

/**
 * Export and re-import through the in-app backup flow. The system document picker is outside the
 * app, so an [Instrumentation.ActivityMonitor] answers the create and open document requests with
 * a file in the app cache, the same way a picker returns a chosen document.
 *
 * Confirming a restore restarts the app process, which also ends the instrumentation, so the
 * journey stops at the import preview. The restore itself is covered by the import coordinator's
 * unit tests.
 */
@HiltAndroidTest
class BackupRestoreKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var ruleRepository: RuleRepository

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backupFile = File(instrumentation.targetContext.cacheDir, "journey-backup.json")
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    @After
    fun cleanUp() {
        monitors.forEach(instrumentation::removeMonitor)
        backupFile.delete()
    }

    @Test
    fun exportedBackupHoldsTheRuleAndImportPreviewsIt() =
        run {
            step("Keep one known routing rule") {
                runBlocking {
                    ruleRepository.allRules().first().forEach { ruleRepository.delete(it) }
                    ruleRepository.insert(RuleEntity(name = BackupRuleName, domains = BackupRuleDomain))
                }
                backupFile.delete()
            }
            step("Open backup from settings and export the shareable backup to a file") {
                answerDocumentRequest(Intent.ACTION_CREATE_DOCUMENT)
                with(SettingsRoutingEntryPage(composeRule)) { click(backupRestore) }
                with(BackupRestorePage(composeRule)) {
                    awaitScreen()
                    click(exportButton)
                    waitFor(variantShare)
                    click(variantShare)
                    waitFor(snackbar)
                }
            }
            step("The written file holds the rule") {
                assertTrue("backup file was not written", backupFile.length() > 0)
                val content = backupFile.readText()
                assertTrue("backup lacks the rule name", BackupRuleName in content)
                assertTrue("backup lacks the rule domain", BackupRuleDomain in content)
            }
            step("Import the same file: the preview offers one routing rule") {
                answerDocumentRequest(Intent.ACTION_OPEN_DOCUMENT)
                with(BackupRestorePage(composeRule)) {
                    // The export snackbar sits over the lower actions until it times out.
                    waitGone(snackbar, timeoutMillis = 15_000)
                    click(importButton)
                    waitFor(importPreview)
                    awaitText(importRoutes, "(1)")
                    require(importConfirm)
                }
            }
            step("Dismissing the preview restores nothing") {
                with(BackupRestorePage(composeRule)) {
                    pressBack()
                    waitGone(importPreview)
                }
                val names = runBlocking { ruleRepository.allRules().first() }.map(RuleEntity::name)
                assertEquals(listOf(BackupRuleName), names)
            }
        }

    /** Answers the next document picker request for [action] with [backupFile]. */
    private fun answerDocumentRequest(action: String) {
        val filter =
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            }
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(backupFile)))
        monitors += instrumentation.addMonitor(filter, result, true)
    }
}
