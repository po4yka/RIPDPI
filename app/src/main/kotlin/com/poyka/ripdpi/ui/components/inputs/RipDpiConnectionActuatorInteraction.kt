package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Input for RipDpiConnectionActuator: the commit model, the gesture that drives
// the carriage, and the semantics the rail exposes. Split from the rendering so
// the rail's look and its behaviour can be read apart; everything here is
// file-private except the modifier bundle the layout consumes.

/**
 * How much of the carriage's travel a drag has to cover before it commits, in
 * either direction.
 *
 * Releasing used to clear at 0.28 while engaging took 0.72, which inverts the
 * risk the rail is built around: the outcome worth guarding is a line dropped
 * by accident, not one raised by accident, and that was the cheaper of the two
 * gestures by a factor of two and a half.
 */
private const val CommitDragThreshold = 0.72f

/**
 * How far the carriage pulls back when a release tap arms instead of committing.
 * Far enough to read as the control answering the tap and pointing at the
 * gesture, well short of the threshold that gesture would have to clear.
 */
private const val ArmedNudgeFraction = 0.18f

/** How long an armed release waits for the tap that confirms it. */
private const val ArmWindowMillis = 4_000L

/** Keys that count as an explicit commit on a focused rail. */
private val ActuatorCommitKeys =
    setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * The one commit path every input shares: tap, keypress and semantics action all
 * come through here, so the arm-then-confirm step cannot be true for one of them
 * and false for another.
 *
 * Returns true whenever the input was handled, arming included — an arming tap
 * is an answer, not a dropped gesture.
 */
@Composable
private fun rememberActuatorCommit(
    state: HomeConnectionActuatorUiState,
    requiresConfirmation: Boolean,
    armed: MutableState<Boolean>,
    carriage: ActuatorCarriage,
    travelPx: State<Float>,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
): () -> Boolean {
    val nudgeSpec = RipDpiThemeTokens.motion.quickTween<Float>()

    // An armed release expires on its own, so a control left alone goes back to
    // reporting the line instead of sitting on a stale prompt.
    LaunchedEffect(armed.value) {
        if (armed.value) {
            delay(ArmWindowMillis)
            armed.value = false
        }
    }

    return {
        if (requiresConfirmation && !armed.value) {
            armed.value = true
            performHaptic(RipDpiHapticFeedback.Toggle)
            // The carriage answers by pulling towards the gesture it wants and
            // settling back, so the first tap teaches the drag rather than
            // looking like the control ignored it.
            carriage.nudge(nudgeSpec, -travelPx.value * ArmedNudgeFraction)
            true
        } else {
            armed.value = false
            invokeActuatorClick(state, performHaptic, onActivate, onDeactivate)
        }
    }
}

/**
 * The drag itself, and the detent it reports as it passes.
 *
 * The threshold used to announce itself only at release, so a gesture that had
 * already done enough looked and felt identical to one that had not, at the one
 * moment the user could still back out. The tick and the track fill both read
 * the same [commitFor] the release reads.
 */
@Composable
private fun rememberActuatorDragState(
    state: HomeConnectionActuatorUiState,
    carriage: ActuatorCarriage,
    travelPx: MutableFloatState,
    pastThreshold: MutableState<Boolean>,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
): DraggableState {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return rememberDraggableState { delta ->
        val orientedDelta = if (isRtl) -delta else delta
        val next =
            (carriage.value + orientedDelta)
                .coerceIn(-travelPx.floatValue, travelPx.floatValue)
        carriage.dragTo(next)
        val reached = state.commitFor(next, travelPx.floatValue) != null
        if (reached != pastThreshold.value) {
            pastThreshold.value = reached
            if (reached) performHaptic(RipDpiHapticFeedback.Selection)
        }
    }
}

@Composable
internal fun rememberActuatorInteractionModifier(
    state: HomeConnectionActuatorUiState,
    dragEnabled: Boolean,
    nodeTestTag: String?,
    armed: MutableState<Boolean>,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
): ActuatorInteractionModifier {
    val scope = rememberCoroutineScope()
    // Re-keyed on status: once the new status lands, the base fraction owns the
    // carriage again and this offset starts over at zero.
    val carriage = remember(state.status) { ActuatorCarriage(scope) }
    val travelPx = remember { mutableFloatStateOf(0f) }
    val returnSpec = RipDpiThemeTokens.motion.quickTween<Float>()
    val actionEnabled = state.isActivationAvailable || state.isDeactivationAvailable
    // Only a release asks to be confirmed, and only where a rail exists to drag
    // instead. See the commit-model note on [RipDpiConnectionActuator].
    val requiresConfirmation = actionEnabled && dragEnabled && !state.isActivationAvailable
    val focusRequester = remember { FocusRequester() }
    val commit =
        rememberActuatorCommit(
            state = state,
            requiresConfirmation = requiresConfirmation,
            armed = armed,
            carriage = carriage,
            travelPx = travelPx,
            performHaptic = performHaptic,
            onActivate = onActivate,
            onDeactivate = onDeactivate,
        )

    val pastThreshold = remember(state.status) { mutableStateOf(false) }
    val draggableState =
        rememberActuatorDragState(
            state = state,
            carriage = carriage,
            travelPx = travelPx,
            pastThreshold = pastThreshold,
            performHaptic = performHaptic,
        )
    val modifier =
        Modifier
            .actuatorTapCommit(
                enabled = actionEnabled,
                toggleableState = state.status.toToggleableState(),
                onCommit = { commit() },
            ).actuatorSemantics(
                state = state,
                nodeTestTag = nodeTestTag,
                actionEnabled = actionEnabled,
                armed = armed.value,
                focusRequester = focusRequester,
                onCommit = commit,
            ).actuatorKeyCommit(
                enabled = actionEnabled,
                focusRequester = focusRequester,
                onCommit = commit,
            ).draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = actionEnabled,
                onDragStarted = {
                    // A drag is the other way to commit the same action, so it
                    // takes the control back from a pending confirmation.
                    armed.value = false
                },
                onDragStopped = {
                    // The gesture is always consumed so a horizontal swipe never
                    // degrades into a tap, but it only commits where a rail exists.
                    val committed =
                        dragEnabled &&
                            handleActuatorDragStop(
                                state = state,
                                dragDeltaPx = carriage.value,
                                travelPx = travelPx.floatValue,
                                performHaptic = performHaptic,
                                onActivate = onActivate,
                                onDeactivate = onDeactivate,
                            )
                    // A committed gesture holds the carriage where the finger left
                    // it and waits for the status to land. Zeroing here instead
                    // rewound the carriage to the start of the drag for as long as
                    // the ViewModel took to answer, so a successful drag visibly
                    // undid itself before the connection began.
                    pastThreshold.value = false
                    if (!committed) {
                        carriage.returnToRest(returnSpec)
                    }
                },
            )

    return ActuatorInteractionModifier(
        modifier = modifier,
        dragDeltaPx = carriage.offsetPx,
        pastThreshold = pastThreshold,
        onTravelChanged = { distancePx -> travelPx.floatValue = distancePx },
    )
}

/**
 * The platform tap. A release now routes it through the arm-then-confirm step
 * rather than being denied one, so this is mounted in every state the action is
 * available instead of only where a tap was allowed to commit outright.
 */
private fun Modifier.actuatorTapCommit(
    enabled: Boolean,
    toggleableState: ToggleableState,
    onCommit: () -> Unit,
): Modifier =
    if (enabled) {
        triStateToggleable(
            state = toggleableState,
            enabled = true,
            role = Role.Switch,
            onClick = onCommit,
        )
    } else {
        this
    }

/**
 * Every semantics property for the rail, declared in one block.
 *
 * Two semantics sources on this node produce two platform accessibility nodes:
 * one interactive but nameless, one named but inert, which is how TalkBack
 * ended up announcing an unlabelled switch. Clearing first collapses them, so
 * role and the click action are restated here rather than inherited from
 * `triStateToggleable`.
 */
private fun Modifier.actuatorSemantics(
    state: HomeConnectionActuatorUiState,
    nodeTestTag: String?,
    actionEnabled: Boolean,
    armed: Boolean,
    focusRequester: FocusRequester,
    onCommit: () -> Boolean,
): Modifier =
    clearAndSetSemantics {
        nodeTestTag?.let { testTag = it }
        role = Role.Switch
        toggleableState = state.status.toToggleableState()
        contentDescription = state.actionLabel
        // No stateDescription here: this string is already the visible headline,
        // which is its own node, so carrying it on the switch too made TalkBack
        // read the status twice on every landing and every transition. Role plus
        // toggleableState still announce on/off; the headline owns the prose.
        if (actionEnabled) {
            onClick(label = state.actionLabel) { onCommit() }
        }
        // The live region is scoped to the pending confirmation for the same
        // reason: the headline still reports the line rather than the prompt, so
        // arming is the one change on this node with no other surface to
        // announce it.
        if (armed) {
            liveRegion = LiveRegionMode.Polite
        }
        // The reset above drops the toggleable's own RequestFocus action, so the
        // focus entry point is restated here against the same requester the tap
        // is mounted with. Without it the rail is unreachable by keyboard.
        if (actionEnabled) {
            requestFocus {
                focusRequester.requestFocus()
                true
            }
        }
    }

/**
 * Keyboard and D-pad commit, plus the focus target the semantics reset above
 * points at. `triStateToggleable` carries key activation of its own, but only
 * for a node that is focusable through its own semantics; clearing them leaves
 * this the one handler that fires, which is also what keeps a single keypress
 * from arming and committing in one go.
 */
private fun Modifier.actuatorKeyCommit(
    enabled: Boolean,
    focusRequester: FocusRequester,
    onCommit: () -> Boolean,
): Modifier =
    if (enabled) {
        focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key in ActuatorCommitKeys) {
                    onCommit()
                } else {
                    false
                }
            }.focusable()
    } else {
        this
    }

private fun HomeConnectionActuatorStatus.toToggleableState(): ToggleableState =
    when (this) {
        HomeConnectionActuatorStatus.Open,
        HomeConnectionActuatorStatus.Fault,
        -> ToggleableState.Off

        HomeConnectionActuatorStatus.Locked,
        HomeConnectionActuatorStatus.Degraded,
        -> ToggleableState.On

        HomeConnectionActuatorStatus.Engaging -> ToggleableState.Indeterminate
    }

private fun invokeActuatorClick(
    state: HomeConnectionActuatorUiState,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
): Boolean {
    if (state.isActivationAvailable) {
        performHaptic(RipDpiHapticFeedback.Action)
        onActivate()
    } else {
        performHaptic(RipDpiHapticFeedback.Toggle)
        onDeactivate()
    }
    return true
}

private enum class ActuatorCommit { Activate, Deactivate }

/**
 * What the gesture would commit to if it ended here, or null if it is still
 * short. One reading serves both the live threshold signal and the release, so
 * the tick the finger feels cannot disagree with what letting go does.
 */
private fun HomeConnectionActuatorUiState.commitFor(
    dragDeltaPx: Float,
    travelPx: Float,
): ActuatorCommit? {
    if (travelPx <= 0f) return null
    val commitDistance = travelPx * CommitDragThreshold
    return when {
        isActivationAvailable && dragDeltaPx >= commitDistance -> ActuatorCommit.Activate
        isDeactivationAvailable && dragDeltaPx <= -commitDistance -> ActuatorCommit.Deactivate
        else -> null
    }
}

/** Returns true when the gesture committed, so the caller can hold the carriage. */
private fun handleActuatorDragStop(
    state: HomeConnectionActuatorUiState,
    dragDeltaPx: Float,
    travelPx: Float,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
): Boolean =
    when (state.commitFor(dragDeltaPx, travelPx)) {
        ActuatorCommit.Activate -> {
            performHaptic(RipDpiHapticFeedback.Action)
            onActivate()
            true
        }

        ActuatorCommit.Deactivate -> {
            performHaptic(RipDpiHapticFeedback.Toggle)
            onDeactivate()
            true
        }

        null -> {
            false
        }
    }

/**
 * The carriage's live offset from wherever its status parks it.
 *
 * A drag writes the offset synchronously, because the release reads the same
 * value. Routing drag deltas through a coroutine let a fast pull-back land
 * after `onDragStopped` had already read the previous offset, so a gesture the
 * user visibly backed out of still committed. Animations own the offset only
 * between drags, and any drag write cancels the one in flight.
 */
@Stable
private class ActuatorCarriage(
    private val scope: CoroutineScope,
) {
    private val offset = mutableFloatStateOf(0f)
    private var animation: Job? = null

    val offsetPx: State<Float> = offset

    val value: Float
        get() = offset.floatValue

    fun dragTo(offsetPx: Float) {
        animation?.cancel()
        offset.floatValue = offsetPx
    }

    fun returnToRest(spec: AnimationSpec<Float>) = runAnimation(spec, 0f)

    /** Pull towards [distancePx] and settle back — the answer to an arming tap. */
    fun nudge(
        spec: AnimationSpec<Float>,
        distancePx: Float,
    ) = runAnimation(spec, distancePx, 0f)

    private fun runAnimation(
        spec: AnimationSpec<Float>,
        vararg targets: Float,
    ) {
        animation?.cancel()
        animation =
            scope.launch {
                targets.forEach { target ->
                    animate(offset.floatValue, target, animationSpec = spec) { value, _ ->
                        offset.floatValue = value
                    }
                }
            }
    }
}
