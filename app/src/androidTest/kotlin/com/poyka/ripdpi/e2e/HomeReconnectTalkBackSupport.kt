package com.poyka.ripdpi.e2e

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File

/** One non-suppressing connection. The leased external driver supplies all touchscreen gestures. */
internal class HomeReconnectTalkBackSupport(
    instrumentation: Instrumentation,
    private val directory: File,
    private val talkBack: Boolean,
) {
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var focusInitialization: JSONObject? = null
    private var focusSettled: JSONObject? = null
    private var focusRequestUptimeMillis: Long? = null
    private var navigation: JSONObject? = null

    init {
        check(directory.exists() || directory.mkdirs())
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags =
                    flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        if (talkBack) {
            HomeReconnectNetworkAcceptanceSupport.await(
                "Real Google TalkBack did not bind",
            ) { isTalkBackBound() }
        }
        assertBound()
    }

    fun assertBound() {
        if (talkBack) assertTrue("Real Google TalkBack must remain enabled and bound", isTalkBackBound())
    }

    fun hasTag(tag: String): Boolean =
        appRoots().any {
            allNodes(it).any { node ->
                hasTag(node, tag) &&
                    node.isVisibleToUser
            }
        }

    fun awaitTag(
        tag: String,
        present: Boolean,
    ) {
        HomeReconnectNetworkAcceptanceSupport.await("Expected tag $tag present=$present") { hasTag(tag) == present }
        assertBound()
    }

    fun action(
        stage: String,
        tag: String,
        label: String,
    ) {
        rawEvidence(stage, null, "none")
        var target = findAction(tag, label)
        if (stage in setOf("open-cancel-review", "open-stale-review", "open-fresh-review")) {
            repeat(6) { attempt ->
                if (target == null) {
                    val container =
                        appRoots().flatMap(::allNodes).single {
                            it.isVisibleToUser && it.isScrollable && !bounds(it).isEmpty &&
                                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                        }
                    navigation =
                        JSONObject()
                            .put("identity", identity(container))
                            .put("bounds", rectangle(container))
                            .put("direction", "forward")
                            .put("attempt", attempt + 1)
                    checkpoint("$stage-scroll-${attempt + 1}", null, "scroll-forward")
                    navigation = null
                    target = findAction(tag, label)
                }
            }
        }
        checkNotNull(target) { "No unique visible action $tag / $label after bounded physical navigation" }
        checkpoint(stage, target, if (talkBack) "double-tap" else "tap")
    }

    fun readSection(
        stage: String,
        tag: String,
        text: String,
    ) {
        rawEvidence(stage, null, "none")
        val nodes =
            appRoots()
                .flatMap(::allNodes)
                .filter { hasTag(it, tag) }
                .flatMap(::allNodes)
                .filter { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }
        val node = checkNotNull(nodes.singleOrNull()) { "No unique visible $text in $tag" }
        checkpoint(stage, Target(node, null, tag), "none")
    }

    fun observe(stage: String) = checkpoint(stage, null, "none")

    fun shell(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

    private fun checkpoint(
        stage: String,
        target: Target?,
        gesture: String,
    ) {
        assertBound()
        if (talkBack && target != null) {
            focusInitialization = stableFocus(target, requireTarget = false)
            focusRequestUptimeMillis = SystemClock.uptimeMillis()
            Log.i("HomeReconnectAcceptance", "Focus: $stage")
            assertTrue(
                "Cannot focus the actual action or standalone text node",
                target.focus.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
            )
            focusSettled = stableFocus(target, requireTarget = true)
        }
        val acknowledgement = File(directory, "$stage.continue")
        val armFile = File(directory, "$stage.arm.json")
        acknowledgement.delete()
        armFile.delete()
        var arm: JSONObject? = null
        var transitionComplete = false
        rawEvidence(stage, target, gesture)
        File(directory, "stage").writeText(stage)
        Log.i("HomeReconnectAcceptance", "Ready: $stage")
        var snapshotAt = SystemClock.uptimeMillis()
        HomeReconnectNetworkAcceptanceSupport.await("No external acknowledgement: $stage", 120_000) {
            check(arm == null || armFile.exists()) { "Armed physical transition marker disappeared" }
            val incoming = if (armFile.exists()) JSONObject(armFile.readText()) else arm
            if (SystemClock.uptimeMillis() - snapshotAt >= 500 || acknowledgement.exists() ||
                incoming?.toString() != arm?.toString()
            ) {
                transitionComplete = rawEvidence(stage, target, gesture, incoming, arm)
                arm = incoming
                assertBound()
                snapshotAt = SystemClock.uptimeMillis()
            }
            if (acknowledgement.exists() && gesture in setOf("tap", "double-tap")) {
                check(arm != null) { "Physical transition was not armed before input" }
                check(transitionComplete) { "Physical transition has no positive native destination" }
            }
            acknowledgement.exists()
        }
        acknowledgement.delete()
        assertBound()
    }

    private inner class Target(
        val named: AccessibilityNodeInfo,
        val action: AccessibilityNodeInfo?,
        val sectionTag: String? = null,
    ) {
        val label = named.text?.toString()?.takeIf { it.isNotBlank() } ?: named.contentDescription?.toString().orEmpty()
        var lastValidatedBounds: List<Int>? = null
        val focus = action ?: named
        val namedIdentity = identityKey(named)
        val actionIdentity = action?.let(::identityKey)
        val focusIdentity = identityKey(focus)
        val actionChain = actionChain(named, action).map(::identityKey)
    }

    private fun findAction(
        tag: String,
        label: String,
    ): Target? {
        val candidates = mutableListOf<Target>()
        appRoots().flatMap(::allNodes).filter { hasTag(it, tag) }.forEach { scope ->
            fun visit(
                node: AccessibilityNodeInfo,
                nearest: AccessibilityNodeInfo?,
            ) {
                val action = if (node.isVisibleToUser && node.isClickable) node else nearest
                if (node.isVisibleToUser &&
                    (node.text?.toString() == label || node.contentDescription?.toString() == label)
                ) {
                    val namedBounds = bounds(node)
                    if (action != null && action.windowId == node.windowId && action.isVisibleToUser &&
                        action.isClickable &&
                        action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                        !namedBounds.isEmpty && bounds(action).contains(namedBounds)
                    ) {
                        candidates += Target(node, action)
                    }
                }
                repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child, action) } }
            }
            visit(scope, null)
        }
        return candidates.singleOrNull()
    }

    private fun appRoots(): List<AccessibilityNodeInfo> =
        automation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }

    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        buildList {
            add(root)
            repeat(root.childCount) { root.getChild(it)?.let { child -> addAll(allNodes(child)) } }
        }

    private fun hasTag(
        node: AccessibilityNodeInfo,
        tag: String,
    ): Boolean = node.viewIdResourceName?.substringAfterLast('/') == tag

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
        val dump = shell("dumpsys activity service $component")
        val pid =
            Regex("""(?m)^\s*SERVICE ${Regex.escape(component)} [^\n]*\bpid=(\d+)""").find(dump)?.groupValues?.get(1)
                ?: return false
        return shell("pidof $TalkBackPackage").trim().split(Regex("\\s+")).contains(pid)
    }

    /** Save all windows before a strict target assertion; do not use UI Automator while TalkBack is bound. */
    private fun rawEvidence(
        stage: String,
        target: Target?,
        gesture: String,
        arm: JSONObject? = null,
        previousArm: JSONObject? = null,
    ): Boolean {
        val windows = JSONArray()
        automation.windows.forEach { window ->
            val nodes = JSONArray()
            window.root?.let { collect(it, nodes) }
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
        File(directory, "$stage-native-windows.json").writeText(
            JSONObject()
                .put("acceptanceRunId", directory.parentFile.name)
                .put("stage", stage)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("windows", windows)
                .toString(2),
        )
        if (arm != null) {
            val armedTarget = checkNotNull(target)
            validateArm(stage, armedTarget, arm, previousArm)
            // Input waits for this completed receipt, so the final strict pass cannot race it.
            if (previousArm == null) {
                validateTarget(armedTarget)
                val actualBounds = bounds(checkNotNull(armedTarget.action))
                check(
                    listOf(actualBounds.left, actualBounds.top, actualBounds.right, actualBounds.bottom) ==
                        armedTarget.lastValidatedBounds,
                )
                check(
                    automation.windows
                        .singleOrNull {
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused
                        }?.let {
                            it.id == arm.getInt("sourceWindowId") &&
                                it.root?.packageName?.toString() == context.packageName
                        } ==
                        true,
                )
            }
        }
        val transition = if (arm != null) transitionEvidence(stage, windows, checkNotNull(target)) else null
        // Only an authenticated pre-input arm permits bounded observation without the old handle.
        // Acknowledgement still requires the actual destination in this same native window tree.
        if (arm == null) target?.let(::validateTarget)
        val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val actionBounds =
            target?.action?.let {
                if (arm == null) {
                    bounds(it)
                        .let { rectangle -> listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom) }
                        .also { value -> target.lastValidatedBounds = value }
                } else {
                    checkNotNull(target.lastValidatedBounds)
                }
            }
        val data =
            JSONObject()
                .put("stage", stage)
                .put("acceptanceRunId", directory.parentFile.name)
                .put("navigation", navigation ?: JSONObject.NULL)
                .put("transitionArmed", arm ?: JSONObject.NULL)
                .put("targetTransitionPending", arm != null && transition == null)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("gesture", gesture)
                .put("talkBack", talkBack)
                .put(
                    "expectedSpeech",
                    target?.label.orEmpty(),
                ).put(
                    "targetBounds",
                    actionBounds?.let(::JSONArray) ?: JSONObject.NULL,
                ).put(
                    "targetFocused",
                    arm == null && focused != null && target != null &&
                        identityKey(focused) == target.focusIdentity,
                ).put("targetRetired", transition != null)
                .put("nativeTransition", transition ?: JSONObject.NULL)
                .put("namedIdentity", target?.namedIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("actionIdentity", target?.actionIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("targetLabel", target?.label ?: JSONObject.NULL)
                .put("focusIdentity", target?.focusIdentity?.let(::JSONObject) ?: JSONObject.NULL)
                .put("actionChain", target?.actionChain?.let { JSONArray(it.map(::JSONObject)) } ?: JSONObject.NULL)
                .put("actualFocusedIdentity", focused?.let(::identity) ?: JSONObject.NULL)
                .put("focusInitialization", if (target != null && talkBack) focusInitialization else JSONObject.NULL)
                .put("focusSettled", if (target != null && talkBack) focusSettled else JSONObject.NULL)
                .put(
                    "focusRequestUptimeMillis",
                    if (target != null &&
                        talkBack
                    ) {
                        focusRequestUptimeMillis
                    } else {
                        JSONObject.NULL
                    },
                ).put(
                    "comparisonSections",
                    target?.sectionTag?.let { sectionEvidence(it, target.named) } ?: JSONObject.NULL,
                ).put("windows", windows)
        val temporary = File(directory, "$stage.json.tmp")
        temporary.writeText(data.toString(2))
        check(temporary.renameTo(File(directory, "$stage.json")))
        return transition != null
    }

    private fun validateArm(
        stage: String,
        target: Target,
        arm: JSONObject,
        previous: JSONObject?,
    ) {
        check(
            arm.keys().asSequence().toSet() ==
                setOf(
                    "schema",
                    "acceptanceRunId",
                    "stage",
                    "kind",
                    "targetNamedIdentity",
                    "targetActionIdentity",
                    "targetFocusIdentity",
                    "targetBounds",
                    "sourceWindowId",
                    "snapshotUptimeMillis",
                    "verifiedAtUptimeMillis",
                ),
        ) { "Unknown physical arm schema" }
        check(arm.get("schema") == 1 && arm.getString("acceptanceRunId") == directory.parentFile.name)
        check(arm.getString("stage") == stage && arm.getString("kind") == checkNotNull(physicalTransition(stage)).name)
        check(arm.getJSONObject("targetNamedIdentity").toString() == target.namedIdentity)
        check(arm.getJSONObject("targetActionIdentity").toString() == target.actionIdentity)
        check(arm.getJSONObject("targetFocusIdentity").toString() == target.focusIdentity)
        check(
            arm.getJSONArray("targetBounds").toString() ==
                JSONArray(checkNotNull(target.lastValidatedBounds)).toString(),
        )
        check(arm.get("sourceWindowId") == JSONObject(target.namedIdentity).getInt("windowId"))
        listOf("snapshotUptimeMillis", "verifiedAtUptimeMillis").forEach {
            check(arm.get(it) is Int || arm.get(it) is Long) { "Non-integral physical arm timestamp" }
        }
        val now = SystemClock.uptimeMillis()
        val snapshot = arm.getLong("snapshotUptimeMillis")
        val verified = arm.getLong("verifiedAtUptimeMillis")
        check(snapshot >= 0 && snapshot <= verified && verified <= now && now - verified <= 15_000)
        if (previous == null) {
            check(verified - snapshot <= 5_000 && now - verified <= 5_000) { "Stale pre-input arm" }
        } else {
            check(previous.toString() == arm.toString()) { "Physical arm changed after input" }
        }
    }

    private enum class PhysicalTransition(
        val destinationTag: String,
    ) {
        OpenReview(RipDpiTestTags.HomeRuntimeReconnectDialog),
        ReturnHome("home-screen"),
    }

    private fun physicalTransition(stage: String): PhysicalTransition? =
        when (stage) {
            "open-cancel-review", "open-stale-review", "open-fresh-review" -> PhysicalTransition.OpenReview
            "cancel-review", "confirm-review" -> PhysicalTransition.ReturnHome
            else -> null
        }

    private fun transitionEvidence(
        stage: String,
        windows: JSONArray,
        target: Target,
    ): JSONObject? {
        val transition = physicalTransition(stage) ?: return null
        val snapshots = (0 until windows.length()).map(windows::getJSONObject)

        fun hasVisibleTag(
            window: JSONObject,
            tag: String,
        ): Boolean {
            val nodes = window.getJSONArray("nodes")
            if (nodes.length() == 0 || nodes.getJSONObject(0).getString("package") != context.packageName) return false
            return (0 until nodes.length()).map(nodes::getJSONObject).count { node ->
                val identity = node.getJSONObject("identity")
                val rectangle = node.getJSONArray("bounds")
                node.getString("package") == context.packageName && node.getBoolean("visible") &&
                    identity.getInt("windowId") == window.getInt("id") &&
                    identity.getString("viewId").substringAfterLast('/') == tag &&
                    rectangle.getInt(2) > rectangle.getInt(0) && rectangle.getInt(3) > rectangle.getInt(1)
            } == 1
        }
        if (transition == PhysicalTransition.ReturnHome &&
            snapshots.any { hasVisibleTag(it, RipDpiTestTags.HomeRuntimeReconnectDialog) }
        ) {
            return null
        }
        val sourceWindow = JSONObject(target.namedIdentity).getInt("windowId")
        val destination =
            snapshots.singleOrNull {
                it.getInt("type") == AccessibilityWindowInfo.TYPE_APPLICATION && it.getBoolean("active") &&
                    it.getBoolean("focused") && it.getInt("id") != sourceWindow &&
                    hasVisibleTag(it, transition.destinationTag)
            } ?: return null
        return JSONObject()
            .put("kind", transition.name)
            .put("sourceWindowId", sourceWindow)
            .put("destinationWindowId", destination.getInt("id"))
            .put("destinationTag", transition.destinationTag)
            .put("observedAtUptimeMillis", SystemClock.uptimeMillis())
    }

    private fun sectionEvidence(
        selectedTag: String,
        named: AccessibilityNodeInfo,
    ): JSONObject {
        val dialog =
            appRoots().flatMap(::allNodes).single {
                hasTag(it, RipDpiTestTags.HomeRuntimeReconnectDialog) && it.isVisibleToUser
            }
        val sections =
            listOf(
                RipDpiTestTags.HomeRuntimeReconnectCurrent to R.string.runtime_config_preview_current,
                RipDpiTestTags.HomeRuntimeReconnectSaved to R.string.runtime_config_preview_saved,
            ).map { (tag, headingId) ->
                val section = allNodes(dialog).single { hasTag(it, tag) && it.isVisibleToUser }
                assertTrue("Comparison section must be inside its dialog", bounds(dialog).contains(bounds(section)))
                assertTrue("Comparison section must use the dialog window", section.windowId == dialog.windowId)
                val heading =
                    allNodes(
                        section,
                    ).single { it.isVisibleToUser && it.text?.toString() == context.getString(headingId) }
                assertTrue("Section heading must be inside its section", bounds(section).contains(bounds(heading)))
                if (tag == selectedTag) {
                    assertTrue("Read target must belong to the selected section", allNodes(section).any { it == named })
                }
                section to
                    JSONObject()
                        .put("tag", tag)
                        .put("identity", identity(section))
                        .put("bounds", rectangle(section))
                        .put("heading", context.getString(headingId))
                        .put("headingIdentity", identity(heading))
                        .put("headingBounds", rectangle(heading))
            }
        val current = sections[0].first
        val saved = sections[1].first
        assertTrue("Current and Saved must be different native nodes", current != saved)
        assertTrue("Current and Saved must have distinct visible bounds", bounds(current) != bounds(saved))
        assertTrue("Comparison sections must have nonempty bounds", !bounds(current).isEmpty && !bounds(saved).isEmpty)
        return JSONObject()
            .put("dialogTag", RipDpiTestTags.HomeRuntimeReconnectDialog)
            .put("dialogIdentity", identity(dialog))
            .put("dialogBounds", rectangle(dialog))
            .put("sections", JSONArray(sections.map { it.second }))
            .put("nativeSectionsEqual", current == saved)
            .put("selectedTag", selectedTag)
    }

    private fun rectangle(node: AccessibilityNodeInfo): JSONArray =
        bounds(node).let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }

    private fun collect(
        node: AccessibilityNodeInfo,
        nodes: JSONArray,
        parentIndex: Int? = null,
    ) {
        val index = nodes.length()
        val rectangle = bounds(node)
        nodes.put(
            JSONObject()
                .put("index", index)
                .put("parentIndex", parentIndex ?: JSONObject.NULL)
                .put("identity", identity(node))
                .put("package", node.packageName?.toString().orEmpty())
                .put("class", node.className?.toString().orEmpty())
                .put("text", node.text?.toString().orEmpty())
                .put("description", node.contentDescription?.toString().orEmpty())
                .put(
                    "state",
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.R
                    ) {
                        node.stateDescription?.toString().orEmpty()
                    } else {
                        ""
                    },
                ).put("scrollable", node.isScrollable)
                .put("visible", node.isVisibleToUser)
                .put("focused", node.isAccessibilityFocused)
                .put("clickable", node.isClickable)
                .put("actions", JSONArray(node.actionList.map { it.id }))
                .put("bounds", JSONArray(listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom))),
        )
        repeat(node.childCount) { node.getChild(it)?.let { child -> collect(child, nodes, index) } }
    }

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

    private fun validateTarget(target: Target) {
        check(target.named.refresh() && target.focus.refresh()) { "The selected native target disappeared" }
        check(identityKey(target.named) == target.namedIdentity && identityKey(target.focus) == target.focusIdentity)
        check(target.named.isVisibleToUser && !bounds(target.named).isEmpty)
        check(target.named.packageName?.toString() == context.packageName)
        check(actionChain(target.named, target.action).map(::identityKey) == target.actionChain)
        target.action?.let { action ->
            check(identityKey(action) == target.actionIdentity)
            check(action.isVisibleToUser && action.isClickable && action.isEnabled)
            check(action.windowId == target.named.windowId && bounds(action).contains(bounds(target.named)))
            check(action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }
    }

    /** Observe stable native state. This function never changes accessibility focus. */
    private fun stableFocus(
        target: Target,
        requireTarget: Boolean,
    ): JSONObject {
        var previous: String? = null
        var stableAt = 0L
        var evidence: JSONObject? = null
        HomeReconnectNetworkAcceptanceSupport.await("Native focus did not settle", 15_000) {
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

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    companion object {
        private const val FocusStableMillis = 1_000L
        private const val TalkBackPackage = "com.google.android.marvin.talkback"
    }
}
