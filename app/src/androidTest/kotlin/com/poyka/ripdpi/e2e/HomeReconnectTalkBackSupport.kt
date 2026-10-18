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
        var target: Target? = null
        HomeReconnectNetworkAcceptanceSupport.await("No unique visible action $tag / $label") {
            target = findAction(tag, label)
            target != null
        }
        checkpoint(stage, checkNotNull(target), if (talkBack) "double-tap" else "tap")
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
            Log.i("HomeReconnectAcceptance", "Focus: $stage")
            assertTrue(
                "Cannot focus the actual named node",
                target.named.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
            )
            HomeReconnectNetworkAcceptanceSupport.await("Actual accessibility focus did not reach the named node") {
                automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) == target.named
            }
        }
        val acknowledgement = File(directory, "$stage.continue")
        acknowledgement.delete()
        rawEvidence(stage, target, gesture)
        File(directory, "stage").writeText(stage)
        Log.i("HomeReconnectAcceptance", "Ready: $stage")
        var snapshotAt = SystemClock.uptimeMillis()
        HomeReconnectNetworkAcceptanceSupport.await("No external acknowledgement: $stage", 120_000) {
            if (SystemClock.uptimeMillis() - snapshotAt >= 500) {
                rawEvidence(stage, target, gesture)
                assertBound()
                snapshotAt = SystemClock.uptimeMillis()
            }
            acknowledgement.exists()
        }
        acknowledgement.delete()
        assertBound()
    }

    private data class Target(
        val named: AccessibilityNodeInfo,
        val action: AccessibilityNodeInfo?,
        val sectionTag: String? = null,
    )

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
                    if (action != null && action.isVisibleToUser && action.isClickable &&
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
    ) {
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
        target?.named?.refresh()
        target?.action?.refresh()
        val actionBounds = target?.action?.let(::bounds)
        val data =
            JSONObject()
                .put("stage", stage)
                .put("uptimeMillis", SystemClock.uptimeMillis())
                .put("gesture", gesture)
                .put("talkBack", talkBack)
                .put(
                    "expectedSpeech",
                    target?.named?.text?.toString() ?: target
                        ?.named
                        ?.contentDescription
                        ?.toString()
                        .orEmpty(),
                ).put(
                    "targetBounds",
                    actionBounds?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL,
                ).put("targetFocused", target?.named?.isAccessibilityFocused ?: false)
                .put("namedIdentity", target?.named?.let(::identity) ?: JSONObject.NULL)
                .put("actionIdentity", target?.action?.let(::identity) ?: JSONObject.NULL)
                .put(
                    "comparisonSections",
                    target?.sectionTag?.let { sectionEvidence(it, target.named) } ?: JSONObject.NULL,
                ).put("windows", windows)
        val temporary = File(directory, "$stage.json.tmp")
        temporary.writeText(data.toString(2))
        check(temporary.renameTo(File(directory, "$stage.json")))
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
                ).put("visible", node.isVisibleToUser)
                .put("focused", node.isAccessibilityFocused)
                .put("clickable", node.isClickable)
                .put("actions", JSONArray(node.actionList.map { it.id }))
                .put("bounds", JSONArray(listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom))),
        )
        repeat(node.childCount) { node.getChild(it)?.let { child -> collect(child, nodes, index) } }
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
        private const val TalkBackPackage = "com.google.android.marvin.talkback"
    }
}
