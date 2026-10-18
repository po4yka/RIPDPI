package com.poyka.ripdpi.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Observes the real service. Speech and hardware activation are verified by the external leased driver. */
internal class HomeLatencyDetailsTalkBackSupport(
    instrumentation: Instrumentation,
    private val runId: String,
    private val waitUntil: (Long, () -> Boolean) -> Unit,
) {
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val directory =
        File(context.filesDir, "latency-details-acceptance/$runId").apply {
            require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))) { "Invalid acceptance run path token" }
            check(!exists()) { "Each acceptance run must use a fresh acceptanceRunId" }
            check(mkdirs())
        }
    private var origin: NamedTarget? = null
    private var currentTarget: NamedTarget? = null
    private var focusStage: String? = null
    private var expectedSpeech: String? = null

    init {
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        waitUntil(BindingTimeoutMillis) { isTalkBackBound() }
        assertBound()
    }

    fun focusOrigin(stage: String) {
        val target = namedTarget(context.getString(R.string.vpn_quality_metric_rtt_p50), requiresAction = true)
        origin?.let {
            assertEquals("The origin name must retain its identity", it.named, target.named)
            assertEquals("The origin action must retain its identity", it.action, target.action)
        }
        origin = target
        focus(stage, context.getString(R.string.vpn_quality_metric_rtt_p50), target)
    }

    fun focusText(
        stage: String,
        text: String,
    ) = focus(stage, text, namedTarget(text, requiresAction = false))

    fun focusDismiss(stage: String) {
        val label = context.getString(R.string.action_dismiss)
        focus(stage, label, namedTarget(label, requiresAction = true))
    }

    /** Observe only. This method must never repair missing or incorrect accessibility focus. */
    fun assertOriginFocus() {
        assertBound()
        val previous = checkNotNull(origin)
        waitUntil(BindingTimeoutMillis) {
            automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) == previous.named
        }
        val target = namedTarget(context.getString(R.string.vpn_quality_metric_rtt_p50), requiresAction = true)
        assertEquals("The origin name must retain its identity after dismissal", previous.named, target.named)
        assertEquals("The origin action must retain its identity after dismissal", previous.action, target.action)
        assertEquals(target.named, automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        currentTarget = target
        focusStage = null
        expectedSpeech = null
    }

    /** The external driver records speech, screenshots and genuine input before acknowledging the stage. */
    fun checkpoint(stage: String) {
        assertBound()
        val acknowledgement = File(directory, "$stage.continue")
        acknowledgement.delete()
        writeWindowEvidence(stage)
        val screenshot = checkNotNull(automation.takeScreenshot())
        File(
            directory,
            "$stage.png",
        ).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertBound()
        File(directory, "stage").writeText(stage)
        Log.i(LogTag, "Ready: $stage")
        var snapshotAt = SystemClock.uptimeMillis()
        waitUntil(AcceptanceTimeoutMillis) {
            if (SystemClock.uptimeMillis() - snapshotAt >= SnapshotIntervalMillis) {
                writeWindowEvidence(stage)
                snapshotAt = SystemClock.uptimeMillis()
            }
            acknowledgement.exists()
        }
        acknowledgement.delete()
        assertBound()
    }

    private fun focus(
        stage: String,
        label: String,
        target: NamedTarget,
    ) {
        assertBound()
        currentTarget = target
        focusStage = stage
        expectedSpeech = label
        // The driver must start its speech attribution window before the focus request,
        // rather than after Ready, which can follow an immediate real TTS response.
        Log.i(LogTag, "Focus: $stage")
        assertTrue(target.named.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        waitUntil(BindingTimeoutMillis) {
            automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) == target.named
        }
    }

    private fun namedTarget(
        label: String,
        requiresAction: Boolean,
    ): NamedTarget {
        val matches = mutableListOf<NamedTarget>()
        collectTargets(automation.rootInActiveWindow, label, null, requiresAction, matches)
        val taggedOrigin = matches.filter { it.named.viewIdResourceName == RipDpiTestTags.HomeLatencyMetric }
        return taggedOrigin.singleOrNull() ?: matches.singleOrNull()
            ?: error("Expected one visible named target with valid action=$requiresAction: $label")
    }

    private fun collectTargets(
        node: AccessibilityNodeInfo?,
        label: String,
        nearestAction: AccessibilityNodeInfo?,
        requiresAction: Boolean,
        matches: MutableList<NamedTarget>,
    ) {
        if (node == null) return
        val action = if (node.isVisibleToUser && node.isClickable) node else nearestAction
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val taggedOrigin = node.viewIdResourceName == RipDpiTestTags.HomeLatencyMetric && text.contains(label)
        if (node.isVisibleToUser && (text == label || description == label || taggedOrigin)) {
            val target = NamedTarget(node, action)
            if (!requiresAction || validAction(target)) matches += target
        }
        repeat(node.childCount) { collectTargets(node.getChild(it), label, action, requiresAction, matches) }
    }

    private fun validAction(target: NamedTarget): Boolean {
        val action = target.action ?: return false
        val bounds = Rect().also { action.getBoundsInScreen(it) }
        val labelBounds = Rect().also { target.named.getBoundsInScreen(it) }
        return action.isVisibleToUser && action.isClickable && !labelBounds.isEmpty && bounds.contains(labelBounds) &&
            action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun assertBound() = assertTrue("Real Google TalkBack must remain enabled and bound", isTalkBackBound())

    private fun isTalkBackBound(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val service =
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).singleOrNull {
                it.resolveInfo.serviceInfo.packageName == TalkBackPackage &&
                    it.resolveInfo.serviceInfo.name == "$TalkBackPackage.TalkBackService"
            } ?: return false
        if (!manager.isEnabled) return false
        val label = service.resolveInfo.loadLabel(context.packageManager).toString()
        val bound =
            shell(
                "dumpsys accessibility",
            ).substringAfter("Bound services:{", "").substringBefore("Enabled services:")
        if (!bound.contains("Service[label=$label,")) return false
        val component = "$TalkBackPackage/.TalkBackService"
        val serviceDump = shell("dumpsys activity service $component")
        val pid =
            Regex("""(?m)^\s*SERVICE ${Regex.escape(component)} [^\n]*\bpid=(\d+)""")
                .find(serviceDump)
                ?.groupValues
                ?.get(1) ?: return false
        return shell("pidof $TalkBackPackage").trim().split(Regex("\\s+")).contains(pid)
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .bufferedReader()
                .use { it.readText() }
        }

    private fun writeWindowEvidence(stage: String) {
        val windows = JSONArray()
        automation.windows.forEach { window ->
            val nodes = JSONArray()
            collectNodes(window.root, nodes)
            windows.put(
                JSONObject()
                    .put("id", window.id)
                    .put("type", window.type)
                    .put("active", window.isActive)
                    .put("focused", window.isFocused)
                    .put("title", window.title?.toString().orEmpty())
                    .put("nodes", nodes),
            )
        }
        val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val target = currentTarget
        val json =
            JSONObject()
                .put("acceptanceRunId", runId)
                .put("stage", stage)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("expectedSpeech", if (focusStage == stage) expectedSpeech else JSONObject.NULL)
                .put("focusRequestedAtStage", focusStage == stage)
                .put("targetNamedIdentity", target?.named?.let(::identity) ?: JSONObject.NULL)
                .put("targetActionIdentity", target?.action?.let(::identity) ?: JSONObject.NULL)
                .put("targetBounds", target?.let { bounds(it.action ?: it.named) } ?: JSONObject.NULL)
                .put("targetFocused", focused != null && focused == target?.named)
                .put("originNamedIdentity", origin?.named?.let(::identity) ?: JSONObject.NULL)
                .put("originActionIdentity", origin?.action?.let(::identity) ?: JSONObject.NULL)
                .put("originReturned", focused != null && focused == origin?.named)
                .put("actualFocusedIdentity", focused?.let(::identity) ?: JSONObject.NULL)
                .put("windows", windows)
        val temporary = File(directory, "$stage.json.tmp").apply { writeText(json.toString(2)) }
        check(temporary.renameTo(File(directory, "$stage.json")))
        assertBound()
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        nodes: JSONArray,
        parent: Int? = null,
    ) {
        if (node == null) return
        val index = nodes.length()
        nodes.put(
            JSONObject()
                .put("index", index)
                .put("parentIndex", parent ?: JSONObject.NULL)
                .put("identity", identity(node))
                .put("text", node.text?.toString().orEmpty())
                .put("description", node.contentDescription?.toString().orEmpty())
                .put(
                    "class",
                    node.className?.toString().orEmpty(),
                ).put("package", node.packageName?.toString().orEmpty())
                .put("visible", node.isVisibleToUser)
                .put("focused", node.isAccessibilityFocused)
                .put("clickable", node.isClickable)
                .put("bounds", bounds(node))
                .put("actions", JSONArray(node.actionList.map { it.id })),
        )
        repeat(node.childCount) { collectNodes(node.getChild(it), nodes, index) }
    }

    private fun bounds(node: AccessibilityNodeInfo): JSONArray =
        Rect().also { node.getBoundsInScreen(it) }.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }

    private fun identity(node: AccessibilityNodeInfo): JSONObject =
        JSONObject()
            .put(
                "windowId",
                node.windowId,
            ).put("hash", node.hashCode())
            .put("viewId", node.viewIdResourceName.orEmpty())
            .put(
                "uniqueId",
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    node.uniqueId ?: JSONObject.NULL
                } else {
                    JSONObject.NULL
                },
            )

    private data class NamedTarget(
        val named: AccessibilityNodeInfo,
        val action: AccessibilityNodeInfo?,
    )

    companion object {
        private const val TalkBackPackage = "com.google.android.marvin.talkback"
        private const val LogTag = "LatencyDetailsAcceptance"
        private const val BindingTimeoutMillis = 15_000L
        private const val AcceptanceTimeoutMillis = 120_000L
        private const val SnapshotIntervalMillis = 500L
    }
}
