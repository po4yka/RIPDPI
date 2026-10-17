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
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.poyka.ripdpi.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Extra acceptance support. It requires real TalkBack; it never enables or replaces the service. */
internal class DiagnosticProgressTalkBackSupport(
    instrumentation: Instrumentation,
    private val waitUntil: (Long, () -> Boolean) -> Unit,
) {
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var cancellationFocusTarget: CancellationTarget? = null

    init {
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        waitUntil(ServiceBindingTimeoutMillis) { isTalkBackBound() }
        assertBound()
    }

    fun focusInitialPhase() {
        val phase = findPhaseNode(automation.rootInActiveWindow) ?: error("No accessible current phase")
        assertTrue(
            "TalkBack must read the current phase",
            phase.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
        )
    }

    fun focusCancellation() {
        val target = cancellationTarget()
        cancellationFocusTarget?.let { previous ->
            assertEquals("The named Cancel node must retain its identity", previous.named, target.named)
            assertEquals("The Cancel action must retain its identity", previous.action, target.action)
        }
        assertTrue(
            "TalkBack must be able to focus Cancel",
            target.named.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
        )
        cancellationFocusTarget = target
    }

    fun focusCurrentProgress(expected: Float) {
        val progress = findRangeNode(automation.rootInActiveWindow) ?: error("No actual accessible progress node")
        assertEquals(expected, progress.rangeInfo.current, 0f)
        assertTrue(
            "TalkBack must read progress",
            progress.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
        )
    }

    fun assertCancellationFocus() {
        assertBound()
        val target = cancellationTarget()
        val previous = checkNotNull(cancellationFocusTarget) { "Cancel has not been focused" }
        assertEquals("The named Cancel node must retain its identity", previous.named, target.named)
        assertEquals("The Cancel action must retain its identity", previous.action, target.action)
        val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val label = context.getString(R.string.diagnostics_action_cancel)
        assertTrue(
            "TalkBack focus must stay on the same named Cancel node",
            focused != null && focused == target.named && hasExactVisibleLabel(focused, label),
        )
        assertTrue("Cancel must retain its visible clickable action ancestor", isActionFor(target.action, target.named))
    }

    fun assertBound() {
        assertTrue("Real Google TalkBack must remain enabled and bound", isTalkBackBound())
    }

    /** Compose captureToImage obtains a suppressing UiAutomation; preserve our existing connection. */
    fun captureProgressBitmap(): Bitmap {
        val progress = findRangeNode(automation.rootInActiveWindow) ?: error("No actual accessible progress node")
        val bounds = Rect().also { progress.getBoundsInScreen(it) }
        val screen = checkNotNull(automation.takeScreenshot())
        assertBound()
        return Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
    }

    /** The external leased runner captures speech before it acknowledges each stage. */
    fun checkpoint(stage: String) {
        assertBound()
        val directory = File(context.filesDir, "diagnostic-progress-acceptance").apply { mkdirs() }
        val acknowledgement = File(directory, "$stage.continue")
        acknowledgement.delete()
        writeWindowEvidence(File(directory, "$stage.json"), stage)
        File(directory, "stage").writeText(stage)
        Log.i("DiagnosticProgressAcceptance", "Ready: $stage")
        var snapshotAt = SystemClock.uptimeMillis()
        waitUntil(AcceptanceTimeoutMillis) {
            if (SystemClock.uptimeMillis() - snapshotAt >= WindowSnapshotIntervalMillis) {
                writeWindowEvidence(File(directory, "$stage.json"), stage)
                snapshotAt = SystemClock.uptimeMillis()
            }
            acknowledgement.exists()
        }
        acknowledgement.delete()
        assertBound()
    }

    private fun isTalkBackBound(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val service =
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).singleOrNull {
                it.resolveInfo.serviceInfo.packageName == TalkBackPackage &&
                    it.resolveInfo.serviceInfo.name == "$TalkBackPackage.TalkBackService"
            } ?: return false
        if (!manager.isEnabled) return false
        val label = service.resolveInfo.loadLabel(context.packageManager).toString()
        val dump = shell("dumpsys accessibility")
        val boundServices = dump.substringAfter("Bound services:{", "").substringBefore("Enabled services:")
        if (!boundServices.contains("Service[label=$label,")) return false
        val component = "$TalkBackPackage/.TalkBackService"
        val serviceDump = shell("dumpsys activity service $component")
        val header = Regex("""(?m)^\s*SERVICE ${Regex.escape(component)} [^\n]*\bpid=(\d+)""")
        val pid = header.find(serviceDump)?.groupValues?.get(1) ?: return false
        return shell("pidof $TalkBackPackage").trim().split(Regex("\\s+")).contains(pid)
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .bufferedReader()
                .use { it.readText() }
        }

    private data class CancellationTarget(
        val named: AccessibilityNodeInfo,
        val action: AccessibilityNodeInfo,
    )

    private fun cancellationTarget(): CancellationTarget =
        findCancellationTarget(automation.rootInActiveWindow, context.getString(R.string.diagnostics_action_cancel))
            ?: error("No unique visible named Cancel with a clickable action ancestor")

    private fun findCancellationTarget(
        root: AccessibilityNodeInfo?,
        label: String,
    ): CancellationTarget? {
        val targets = mutableListOf<CancellationTarget>()
        collectCancellationTargets(root, label, null, targets)
        return targets.singleOrNull()
    }

    private fun collectCancellationTargets(
        node: AccessibilityNodeInfo?,
        label: String,
        nearestAction: AccessibilityNodeInfo?,
        targets: MutableList<CancellationTarget>,
    ) {
        if (node == null) return
        val action = if (node.isVisibleToUser && node.isClickable) node else nearestAction
        if (hasExactVisibleLabel(node, label) && action != null && isActionFor(action, node)) {
            targets += CancellationTarget(node, action)
        }
        repeat(node.childCount) { index -> collectCancellationTargets(node.getChild(index), label, action, targets) }
    }

    private fun hasExactVisibleLabel(
        node: AccessibilityNodeInfo,
        label: String,
    ): Boolean =
        node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)

    private fun isActionFor(
        action: AccessibilityNodeInfo,
        named: AccessibilityNodeInfo,
    ): Boolean {
        val actionBounds = Rect().also { action.getBoundsInScreen(it) }
        val namedBounds = Rect().also { named.getBoundsInScreen(it) }
        return action.isVisibleToUser && action.isClickable &&
            action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
            !namedBounds.isEmpty && actionBounds.contains(namedBounds)
    }

    private fun findRangeNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (node.rangeInfo != null) {
            node
        } else {
            (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::findRangeNode) }
        }

    private fun findPhaseNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (node.contentDescription?.toString() == "DNS" && node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE) {
            node
        } else {
            (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::findPhaseNode) }
        }

    /** Reuse this non-suppressing connection; a new UI Automator dump would suspend TalkBack. */
    private fun writeWindowEvidence(
        file: File,
        stage: String,
    ) {
        val windows = JSONArray()
        automation.windows.forEach { window ->
            val nodes = JSONArray()
            window.root?.let { collectWindowNodes(it, nodes) }
            windows.put(
                JSONObject()
                    .put("id", window.id)
                    .put("type", window.type)
                    .put("title", window.title?.toString().orEmpty())
                    .put("active", window.isActive)
                    .put("focused", window.isFocused)
                    .put("nodes", nodes),
            )
        }
        val root = automation.rootInActiveWindow
        val cancel = findCancellationTarget(root, context.getString(R.string.diagnostics_action_cancel))
        val bounds = Rect().also { cancel?.action?.getBoundsInScreen(it) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            JSONObject()
                .put("stage", stage)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("cancelBounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                .put("cancelFound", cancel != null)
                .put("cancelFocused", cancel?.named?.isAccessibilityFocused ?: false)
                .put("cancelNamedIdentity", cancel?.named?.let(::nodeIdentity) ?: JSONObject.NULL)
                .put("cancelActionIdentity", cancel?.action?.let(::nodeIdentity) ?: JSONObject.NULL)
                .put("activeRootPackage", root?.packageName?.toString().orEmpty())
                .put("windows", windows)
                .toString(2),
        )
        check(temporary.renameTo(file))
        checkNotNull(cancel) { "No unique named Cancel with a clickable action ancestor; raw windows saved to $file" }
    }

    private fun collectWindowNodes(
        node: AccessibilityNodeInfo,
        nodes: JSONArray,
        parentIndex: Int? = null,
    ) {
        val index = nodes.length()
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val state =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString().orEmpty()
            } else {
                ""
            }
        nodes.put(
            JSONObject()
                .put("index", index)
                .put("parentIndex", parentIndex ?: JSONObject.NULL)
                .put("identity", nodeIdentity(node))
                .put("actions", JSONArray(node.actionList.map { it.id }))
                .put("importantForAccessibility", node.isImportantForAccessibility)
                .put("focusable", node.isFocusable)
                .put("package", node.packageName?.toString().orEmpty())
                .put("class", node.className?.toString().orEmpty())
                .put("text", node.text?.toString().orEmpty())
                .put("description", node.contentDescription?.toString().orEmpty())
                .put("progress", node.rangeInfo?.current ?: JSONObject.NULL)
                .put("state", state)
                .put("focused", node.isAccessibilityFocused)
                .put("visible", node.isVisibleToUser)
                .put("clickable", node.isClickable)
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))),
        )
        repeat(node.childCount) { child -> node.getChild(child)?.let { collectWindowNodes(it, nodes, index) } }
    }

    private fun nodeIdentity(node: AccessibilityNodeInfo): JSONObject =
        JSONObject()
            .put("windowId", node.windowId)
            .put("hash", node.hashCode())
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

    companion object {
        private const val TalkBackPackage = "com.google.android.marvin.talkback"
        private const val AcceptanceTimeoutMillis = 120_000L
        private const val ServiceBindingTimeoutMillis = 15_000L
        private const val WindowSnapshotIntervalMillis = 500L
    }
}
