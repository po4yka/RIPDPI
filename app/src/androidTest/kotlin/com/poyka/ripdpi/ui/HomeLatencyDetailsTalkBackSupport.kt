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
import android.view.accessibility.AccessibilityWindowInfo
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
    private var armedTransition: JSONObject? = null
    private var transitionCompleted = false
    private var origin: NamedTarget? = null
    private var currentTarget: NamedTarget? = null
    private var focusStage: String? = null
    private var expectedSpeech: String? = null
    private var focusInitialization: JSONObject? = null
    private var focusSettled: JSONObject? = null
    private var focusRequestUptimeMillis: Long? = null

    init {
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        waitUntil(BindingTimeoutMillis) { isTalkBackBound() }
        assertBound()
    }

    fun focusOrigin(stage: String) {
        val target = namedTarget(context.getString(R.string.vpn_quality_metric_rtt_p50), requiresAction = true)
        origin?.let {
            assertEquals("The origin name must retain its identity", it.namedIdentity, target.namedIdentity)
            assertEquals("The origin action must retain its identity", it.actionIdentity, target.actionIdentity)
        }
        check(stage == "origin-focused") { "Only the initial origin uses physical touch exploration" }
        origin = target
        currentTarget = target
        focusStage = stage
        expectedSpeech = context.getString(R.string.vpn_quality_metric_rtt_p50)
        focusInitialization = stableFocus(target, requireTarget = false)
        focusSettled = null
        // The external driver must establish TalkBack actor history with real touch exploration.
        // Start speech attribution before that physical input, then observe its result only.
        focusRequestUptimeMillis = SystemClock.uptimeMillis()
        Log.i(LogTag, "Focus: $stage")
        checkpoint("explore-origin")
        focusSettled = stableFocus(target, requireTarget = true)
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
        focusSettled = stableFocus(previous, requireTarget = true)
        val target = namedTarget(context.getString(R.string.vpn_quality_metric_rtt_p50), requiresAction = true)
        assertEquals(
            "The origin name must retain its identity after dismissal",
            previous.namedIdentity,
            target.namedIdentity,
        )
        assertEquals(
            "The origin action must retain its identity after dismissal",
            previous.actionIdentity,
            target.actionIdentity,
        )
        assertEquals(
            "The origin focus must retain its identity after dismissal",
            previous.focusIdentity,
            target.focusIdentity,
        )
        assertEquals(
            target.focusIdentity,
            automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let(::identityKey),
        )
        currentTarget = target
        focusStage = null
        expectedSpeech = null
    }

    /** The external driver records speech, screenshots and genuine input before acknowledging the stage. */
    fun checkpoint(stage: String) {
        assertBound()
        val acknowledgement = File(directory, "$stage.continue")
        acknowledgement.delete()
        val previousArm = File(directory, "$stage.arm.json")
        check(!previousArm.exists() || previousArm.delete()) { "Cannot remove the previous transition arm" }
        armedTransition = null
        transitionCompleted = false
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
            if (acknowledgement.exists() || SystemClock.uptimeMillis() - snapshotAt >= SnapshotIntervalMillis) {
                writeWindowEvidence(stage, observeTransition = true)
                snapshotAt = SystemClock.uptimeMillis()
            }
            acknowledgement.exists() && (physicalTransition(stage) == null || transitionCompleted)
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
        focusInitialization = stableFocus(target, requireTarget = false)
        // The driver must start its speech attribution window before the focus request,
        // rather than after Ready, which can follow an immediate real TTS response.
        focusRequestUptimeMillis = SystemClock.uptimeMillis()
        Log.i(LogTag, "Focus: $stage")
        assertTrue(target.focus.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        focusSettled = stableFocus(target, requireTarget = true)
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
            val target = NamedTarget(node, action, label)
            if (!requiresAction || validAction(target)) matches += target
        }
        repeat(node.childCount) { collectTargets(node.getChild(it), label, action, requiresAction, matches) }
    }

    private fun validAction(target: NamedTarget): Boolean {
        val action = target.action ?: return false
        val bounds = Rect().also { action.getBoundsInScreen(it) }
        val labelBounds = Rect().also { target.named.getBoundsInScreen(it) }
        return action.windowId == target.named.windowId && action.isVisibleToUser && action.isClickable &&
            !labelBounds.isEmpty && bounds.contains(labelBounds) &&
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

    private fun writeWindowEvidence(
        stage: String,
        observeTransition: Boolean = false,
    ) {
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
        // Persist the same raw snapshot before any target, marker or destination check can fail.
        val rawSnapshot =
            JSONObject()
                .put("acceptanceRunId", runId)
                .put("stage", stage)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("windows", windows)
        val raw = File(directory, "$stage-native-windows.json.tmp").apply { writeText(rawSnapshot.toString(2)) }
        check(raw.renameTo(File(directory, "$stage-native-windows.json")))
        val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val target = currentTarget
        val armed = observeTransition && target != null && readTransitionArm(stage, target)
        val transition =
            if (armed) {
                destinationEvidence(
                    stage,
                    windows,
                    JSONObject(checkNotNull(target).namedIdentity).getInt("windowId"),
                    context.packageName,
                    SystemClock.uptimeMillis(),
                )
            } else {
                null
            }
        val pending = armed && transition == null
        transitionCompleted = transition != null
        // A physical action can replace the old target before the external acknowledgement.
        // Only the expected destination in this same native snapshot can retire that target.
        if (!armed) target?.let(::validateTarget)
        val targetBounds =
            target?.let {
                if (!armed) {
                    nativeBounds(it.action ?: it.named)
                        .let { rectangle -> listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom) }
                        .also { value -> it.lastValidatedBounds = value }
                } else {
                    checkNotNull(it.lastValidatedBounds)
                }
            }
        val json =
            JSONObject()
                .put("acceptanceRunId", runId)
                .put("stage", stage)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("expectedSpeech", if (focusStage == stage) expectedSpeech else JSONObject.NULL)
                .put("focusRequestedAtStage", focusStage == stage)
                .put("focusAcquisition", if (focusStage == "origin-focused") "touch-exploration" else JSONObject.NULL)
                .put("targetNamedIdentity", target?.namedIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("targetActionIdentity", target?.actionIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("targetLabel", target?.label ?: JSONObject.NULL)
                .put("targetFocusIdentity", target?.focusIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put(
                    "targetActionChain",
                    target?.actionChain?.let { JSONArray(it.map(::JSONObject)) } ?: JSONObject.NULL,
                ).put("focusInitialization", focusInitialization ?: JSONObject.NULL)
                .put("focusSettled", focusSettled ?: JSONObject.NULL)
                .put("focusRequestUptimeMillis", focusRequestUptimeMillis ?: JSONObject.NULL)
                .put("targetBounds", targetBounds?.let(::JSONArray) ?: JSONObject.NULL)
                .put("targetTransitionPending", pending)
                .put("transitionArmed", armedTransition ?: JSONObject.NULL)
                .put("targetRetired", transition != null)
                .put("nativeTransition", transition ?: JSONObject.NULL)
                .put(
                    "targetFocused",
                    !armed && focused != null && focused?.let(::identityKey) == target?.focusIdentity,
                ).put("originNamedIdentity", origin?.namedIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("originActionIdentity", origin?.actionIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("originFocusIdentity", origin?.focusIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("originReturned", focused != null && focused?.let(::identityKey) == origin?.focusIdentity)
                .put("actualFocusedIdentity", focused?.let(::identity) ?: JSONObject.NULL)
                .put("windows", windows)
        val temporary = File(directory, "$stage.json.tmp").apply { writeText(json.toString(2)) }
        check(temporary.renameTo(File(directory, "$stage.json")))
        assertBound()
    }

    private fun physicalTransition(stage: String): Pair<String, String>? =
        when (stage) {
            "open-latency", "reopen-latency" -> "OpenDetails" to RipDpiTestTags.LatencyDetailDialog
            "dismiss-latency", "back-latency" -> "ReturnHome" to RipDpiTestTags.HomeLatencyMetric
            else -> null
        }

    private fun protocolValue(value: Any?): Any? =
        when (value) {
            JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { protocolValue(value.get(it)) }
            is JSONArray -> (0 until value.length()).map { protocolValue(value.get(it)) }
            else -> value
        }

    private fun validateArm(
        marker: JSONObject,
        expected: JSONObject,
        now: Long,
    ): Long {
        val fields = marker.keys().asSequence().toSet()
        val fixed = expected.keys().asSequence().toSet()
        check(fields == fixed + setOf("snapshotUptimeMillis", "verifiedAtUptimeMillis")) { "Unknown arm marker fields" }
        fixed.forEach {
            check(protocolValue(marker.get(it)) == protocolValue(expected.get(it))) { "Foreign transition arm: $it" }
        }
        val snapshot = marker.get("snapshotUptimeMillis")
        val verified = marker.get("verifiedAtUptimeMillis")
        check((snapshot is Int || snapshot is Long) && (verified is Int || verified is Long))
        val snapshotAt = (snapshot as Number).toLong()
        val verifiedAt = (verified as Number).toLong()
        check(snapshotAt > 0 && snapshotAt <= verifiedAt && verifiedAt <= now)
        check(
            verifiedAt - snapshotAt <= ArmFreshMillis && now - verifiedAt <= ArmFreshMillis,
        ) { "Stale transition arm" }
        return verifiedAt
    }

    private fun readTransitionArm(
        stage: String,
        target: NamedTarget,
    ): Boolean {
        val transition = physicalTransition(stage)
        val file = File(directory, "$stage.arm.json")
        if (!file.exists()) {
            check(armedTransition == null) { "Armed transition marker disappeared" }
            return false
        }
        check(transition != null) { "Arm marker is forbidden for this checkpoint" }
        val marker = JSONObject(file.readText())
        val now = SystemClock.uptimeMillis()
        val previous = armedTransition
        if (previous == null) {
            val expected =
                JSONObject()
                    .put("schema", 1)
                    .put("acceptanceRunId", runId)
                    .put("stage", stage)
                    .put("kind", transition.first)
                    .put("targetNamedIdentity", JSONObject(target.namedIdentity))
                    .put("targetActionIdentity", target.actionIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                    .put("targetFocusIdentity", JSONObject(target.focusIdentity))
                    .put("targetBounds", JSONArray(checkNotNull(target.lastValidatedBounds)))
                    .put("sourceWindowId", JSONObject(target.namedIdentity).getInt("windowId"))
            validateArm(marker, expected, now)
            validateTarget(target)
            armedTransition = marker
        } else {
            check(protocolValue(marker) == protocolValue(previous)) { "Armed transition marker changed" }
        }
        check(now - marker.getLong("verifiedAtUptimeMillis") <= TransitionTimeoutMillis) {
            "Physical transition destination timeout"
        }
        return true
    }

    private fun destinationEvidence(
        stage: String,
        windows: JSONArray,
        sourceWindow: Int,
        packageName: String,
        now: Long,
    ): JSONObject? {
        val transition = checkNotNull(physicalTransition(stage))
        val snapshots = (0 until windows.length()).map(windows::getJSONObject)

        fun visibleTags(
            window: JSONObject,
            tag: String,
        ): List<JSONObject> {
            val nodes = window.getJSONArray("nodes")
            val matching =
                (0 until nodes.length()).map(nodes::getJSONObject).filter { node ->
                    val rectangle = node.getJSONArray("bounds")
                    node.getBoolean("visible") &&
                        node.getJSONObject("identity").getString("viewId").substringAfterLast('/') == tag &&
                        rectangle.getInt(2) > rectangle.getInt(0) && rectangle.getInt(3) > rectangle.getInt(1)
                }
            matching.forEach { node ->
                check(nodes.length() > 0 && nodes.getJSONObject(0).getString("package") == packageName)
                check(
                    node.getString("package") == packageName &&
                        node.getJSONObject("identity").getInt("windowId") == window.getInt("id"),
                )
            }
            return matching
        }
        if (transition.first == "ReturnHome" &&
            snapshots.any { visibleTags(it, RipDpiTestTags.LatencyDetailDialog).isNotEmpty() }
        ) {
            return null
        }
        val candidates =
            snapshots.filter {
                it.getInt("type") == AccessibilityWindowInfo.TYPE_APPLICATION && it.getBoolean("active") &&
                    it.getBoolean("focused") && it.getInt("id") != sourceWindow &&
                    visibleTags(it, transition.second).isNotEmpty()
            }
        check(candidates.size <= 1) { "Ambiguous physical transition destination windows" }
        val destination = candidates.singleOrNull() ?: return null
        val node = visibleTags(destination, transition.second).single()
        return JSONObject()
            .put("kind", transition.first)
            .put("sourceWindowId", sourceWindow)
            .put("destinationWindowId", destination.getInt("id"))
            .put("destinationTag", transition.second)
            .put(
                "destinationIdentity",
                node.getJSONObject("identity"),
            ).put("destinationBounds", node.getJSONArray("bounds"))
            .put("observedAtUptimeMillis", now)
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

    private fun identityKey(node: AccessibilityNodeInfo): String = identity(node).toString()

    private fun actionChain(
        named: AccessibilityNodeInfo,
        action: AccessibilityNodeInfo?,
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        var node: AccessibilityNodeInfo? = named
        repeat(64) {
            val current = checkNotNull(node) { "The action must be an actual ancestor of the named node" }
            check(result.none { it == current }) { "Cyclic native accessibility hierarchy" }
            check(current.windowId == named.windowId) { "Action ancestry changed windows" }
            result += current
            if (current == (action ?: named)) return result
            check(!current.isVisibleToUser || !current.isClickable) { "Action is not the nearest clickable ancestor" }
            node = current.parent
        }
        error("Native action ancestry exceeds the bounded traversal")
    }

    private fun validateTarget(target: NamedTarget) {
        check(target.named.refresh() && target.focus.refresh()) { "The selected native target disappeared" }
        check(identityKey(target.named) == target.namedIdentity && identityKey(target.focus) == target.focusIdentity)
        check(target.named.isVisibleToUser && !nativeBounds(target.named).isEmpty)
        check(target.named.packageName?.toString() == context.packageName)
        check(actionChain(target.named, target.action).map(::identityKey) == target.actionChain)
        target.action?.let { action ->
            check(identityKey(action) == target.actionIdentity)
            check(action.isVisibleToUser && action.isClickable && action.isEnabled)
            check(action.windowId == target.named.windowId && nativeBounds(action).contains(nativeBounds(target.named)))
            check(action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }
    }

    /** Observe stable native state. This function never changes accessibility focus. */
    private fun stableFocus(
        target: NamedTarget,
        requireTarget: Boolean,
    ): JSONObject {
        var previous: String? = null
        var stableAt = 0L
        var evidence: JSONObject? = null
        waitUntil(BindingTimeoutMillis) {
            validateTarget(target)
            val window =
                automation.windows.singleOrNull {
                    it.id == target.named.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        it.isActive && it.isFocused && it.root?.packageName?.toString() == context.packageName
                }
            val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            val key = focused?.let(::identityKey)
            val valid =
                window != null && focused != null && focused.windowId == window.id &&
                    focused.isVisibleToUser && focused.packageName?.toString() == context.packageName &&
                    (!requireTarget || key == target.focusIdentity)
            val now = SystemClock.uptimeMillis()
            if (!valid || key != previous) {
                previous = if (valid) key else null
                stableAt = now
            }
            if (valid && now - stableAt >= FocusStableMillis) {
                evidence =
                    JSONObject()
                        .put("windowId", checkNotNull(window).id)
                        .put("focusIdentity", JSONObject(checkNotNull(key)))
                        .put("stableSinceUptimeMillis", stableAt)
                        .put("observedAtUptimeMillis", now)
                        .put("minimumStableMillis", FocusStableMillis)
            }
            evidence != null
        }
        return checkNotNull(evidence)
    }

    private fun nativeBounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

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

    private inner class NamedTarget(
        val named: AccessibilityNodeInfo,
        val action: AccessibilityNodeInfo?,
        val label: String,
    ) {
        var lastValidatedBounds: List<Int>? = null
        val focus = action ?: named
        val namedIdentity = identityKey(named)
        val actionIdentity = action?.let(::identityKey)
        val focusIdentity = identityKey(focus)
        val actionChain = actionChain(named, action).map(::identityKey)
    }

    companion object {
        private const val FocusStableMillis = 1_000L
        private const val TalkBackPackage = "com.google.android.marvin.talkback"
        private const val LogTag = "LatencyDetailsAcceptance"
        private const val BindingTimeoutMillis = 15_000L
        private const val AcceptanceTimeoutMillis = 120_000L
        private const val SnapshotIntervalMillis = 500L
        private const val ArmFreshMillis = 5_000L
        private const val TransitionTimeoutMillis = 15_000L
    }
}
