package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/**
 * What the actuator's input layer hands its layout: the modifier carrying every
 * gesture and the semantics, plus the two live readings the rail draws from.
 *
 * This is the whole contract between the two halves of the component, which is
 * why it sits on its own rather than inside either of them.
 */
internal class ActuatorInteractionModifier(
    val modifier: Modifier,
    val dragDeltaPx: State<Float>,
    val pastThreshold: State<Boolean>,
    /** Reported by the layout once it knows how far the carriage can run. */
    val onTravelChanged: (Float) -> Unit,
)
