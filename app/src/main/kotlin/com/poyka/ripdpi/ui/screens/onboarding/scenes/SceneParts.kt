// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val PartLimit = 160

private enum class PartKind { Dust, Spark, Chip, Ring }

private class Part(
    val kind: PartKind,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val life: Float,
    val r: Float,
    val color: Color,
    var rot: Float = 0f,
    val spin: Float = 0f,
    val floor: Float = Float.MAX_VALUE,
) {
    var t = 0f
}

/** The short-lived marks of a scene: dust, sparks, falling chips, and rings. */
internal class Parts(
    private val random: Random,
) {
    private val list = ArrayList<Part>()

    fun clear() = list.clear()

    private fun add(part: Part) {
        if (list.size < PartLimit) list += part
    }

    fun rnd(span: Float = 1f): Float = random.nextFloat() * span

    fun dust(
        x: Float,
        y: Float,
        color: Color,
        count: Int = 5,
    ) = repeat(count) {
        add(Part(PartKind.Dust, x, y, (rnd() - 0.5f) * 60f, -rnd(20f), 0.45f + rnd(0.3f), 1.6f + rnd(2f), color))
    }

    fun spark(
        x: Float,
        y: Float,
        color: Color,
        count: Int = 6,
        speed: Float = 70f,
    ) = repeat(count) {
        val angle = rnd(2f * PI.toFloat())
        val v = speed * (0.4f + rnd(0.6f))
        add(
            Part(
                PartKind.Spark,
                x,
                y,
                cos(angle) * v,
                sin(angle) * v,
                0.5f + rnd(0.4f),
                2.5f + rnd(3f),
                color,
                rnd(3f),
            ),
        )
    }

    fun chips(
        x: Float,
        y: Float,
        color: Color,
        count: Int = 4,
        floor: Float = Ground + 2f,
    ) = repeat(count) {
        val part =
            Part(
                kind = PartKind.Chip,
                x = x + rnd(6f) - 3f,
                y = y,
                vx = (rnd() - 0.5f) * 70f,
                vy = -20f - rnd(50f),
                life = 0.9f + rnd(0.4f),
                r = 0.9f + rnd(1.4f),
                color = color,
                rot = rnd(6f),
                spin = 9f,
                floor = floor,
            )
        add(part)
    }

    fun ring(
        x: Float,
        y: Float,
        color: Color,
        r: Float = 6f,
    ) = add(Part(PartKind.Ring, x, y, 0f, 0f, 0.6f, r, color))

    fun draw(
        pen: Pen,
        dt: Float,
    ) {
        list.removeAll { part ->
            part.t += dt
            part.t >= part.life
        }
        list.forEach { part -> pen.draw(part, dt) }
    }

    private fun Pen.draw(
        part: Part,
        dt: Float,
    ) {
        val u = part.t / part.life
        // A chip that falls to the ground stays on it.
        val lies = part.vy > 0f && part.y >= part.floor
        if (!lies) {
            part.x += part.vx * dt
            part.y += part.vy * dt
        }
        when (part.kind) {
            PartKind.Dust -> {
                part.vx *= 0.94f
                circ(part.x, part.y, part.r * (1f + u * 1.6f))
                fill(part.color, (1f - u) * 0.45f)
            }

            PartKind.Spark -> {
                part.vx *= 0.93f
                part.vy *= 0.93f
                star(part.x, part.y, part.r * (1f - u * 0.5f), part.rot + u * 3f)
                fill(part.color, 1f - u)
            }

            PartKind.Chip -> {
                if (!lies) {
                    part.vy += 330f * dt
                    part.rot += part.spin * dt
                }
                at(part.x, part.y, part.rot) {
                    rr(-part.r, -part.r * 0.6f, part.r * 2f, part.r * 1.2f, 0.6f)
                    fill(part.color, cl((1f - u) * 3f))
                }
            }

            PartKind.Ring -> {
                circ(part.x, part.y, part.r + u * 26f)
                stroke(part.color, (1f - u) * 0.7f, 1.5f)
            }
        }
    }
}

/**
 * One scene of the onboarding stage. It moves and draws in one pass: [draw] advances the scene by the time in
 * the frame, which is zero when the stage only draws it again.
 */
internal abstract class Scene(
    protected val parts: Parts,
) {
    /** Seconds since the scene came in. The props of a scene come in one after the other. */
    var since = 0f

    /** The time at which the still frame of the scene is taken, when nothing may move. */
    open val stillAt: Float = 6f

    open fun enter(frame: Frame) {
        since = 0f
    }

    abstract fun Pen.draw(frame: Frame)

    protected fun inn(order: Float): Float = back(cl((since - order * 0.09f) / 0.55f))

    protected inline fun Pen.rise(
        order: Float,
        block: () -> Unit,
    ) {
        val k = inn(order)
        faded(k * 1.6f) { at(0f, (1f - k) * 18f) { block() } }
    }
}

/** The place in a loop of [period] seconds, and the moments that the loop passed in this frame. */
internal class Loop(
    private val period: Float,
) {
    var at = 0f
        private set
    private var was = 0f

    fun reset() {
        at = 0f
        was = 0f
    }

    fun step(since: Float) {
        was = at
        at = since % period
        // A new round passes every moment from its start.
        if (at < was) was = -1f
    }

    fun passed(moment: Float): Boolean = was < moment && at >= moment
}
