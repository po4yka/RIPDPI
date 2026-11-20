// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.sin

private const val Pi = PI.toFloat()
private const val Items = 3
private const val Flight = 1f
private const val MouthX = 77f
private const val MouthY = Ground - 46f
private const val SlotX = 193f
private const val DeviceX = 206f
private const val GhostX = 142f

/**
 * Ready. A crate beside the device opens. A server, a route, and a pair of controls leave the crate one after
 * the other and take their rows on the screen, and each row gets its mark. The ghost watches each one go and has
 * nothing to do. Then the rows clear, the crate closes, and the next delivery starts.
 */
internal class ReadyScene(
    parts: Parts,
) : Scene(parts) {
    private val loop = Loop(8f)
    private val lid = Spring()
    private val docks = List(Items) { Spring() }
    private val glad = Spring()

    override val stillAt = 5f

    override fun enter(frame: Frame) {
        super.enter(frame)
        loop.reset()
        (docks + lid + glad).forEach { spring ->
            spring.x = 0f
            spring.v = 0f
        }
    }

    private fun launch(item: Int): Float = 1.2f + item * 0.7f

    private fun slotY(item: Int): Float = 66f + item * 24f

    override fun Pen.draw(frame: Frame) {
        val dt = frame.dt
        loop.step(since)
        val p = loop.at
        val leaves = cl((p - 7f) / 0.6f)
        lid.step(if (p > 0.5f && p < 7.2f) 1f else 0f, 90f, 9f, dt)
        glad.step(0f, 180f, 10f, dt)
        if (loop.passed(0.5f)) parts.dust(MouthX, MouthY, colors.ink, 4)
        if (loop.passed(launch(Items - 1) + Flight + 0.3f)) {
            parts.spark(DeviceX, 42f, colors.signal, 7)
            glad.v += 6f
        }
        sky(0.7f)
        rise(0f) { crate() }
        rise(2f) { device(p, leaves, dt) }
        var lookX = 1f
        var lookY = 0f
        repeat(Items) { i ->
            val u = (p - launch(i)) / Flight
            if (u > 0f && u < 1f) {
                lookX = cl((itemX(u) - GhostX) / 30f, -1f, 1f)
                lookY = cl((itemY(i, u) - 100f) / 30f, -1f, 1f)
            }
        }
        val done = p > launch(Items - 1) + Flight && leaves < 1f
        shadow(GhostX, Ground + 1f, 12f)
        ghost(
            Ghost(
                x = GhostX,
                y = Ground - 8f,
                s = 1.1f,
                tilt = glad.x * 0.06f,
                look = lookX,
                lookY = lookY,
                eye = if (done) Eye.Narrow else Eye.Open,
                id = 5,
            ),
        )
        repeat(Items) { i -> flight(i, (p - launch(i)) / Flight) }
    }

    private fun itemX(u: Float): Float = mix(MouthX, SlotX, ease(u))

    private fun itemY(
        item: Int,
        u: Float,
    ): Float = mix(MouthY, slotY(item), ease(u)) - sin(ease(u) * Pi) * (52f - item * 8f)

    /** An item on its way from the crate to its row. It takes the light of the screen as it comes over the device. */
    private fun Pen.flight(
        item: Int,
        u: Float,
    ) {
        if (u <= 0f || u >= 1f) return
        val x = itemX(u)
        // It lands at the size of its row, and it takes the light of the screen only over the screen.
        val size = mix(0.7f, 1f, ease(u)) + sin(u * Pi) * 0.4f
        val color = lerp(colors.ink, colors.nightLight, cl((x - 178f) / 4f))
        at(x, itemY(item, u), (1f - ease(u)) * 4f, size, size) { glyph(item, color, cl(u * 6f)) }
    }

    private fun Pen.crate() {
        shadow(MouthX, Ground + 1f, 40f)
        rr(46f, Ground - 42f, 62f, 42f, 3f)
        fill(colors.paper)
        fill(colors.ink, 0.09f)
        stroke(colors.ink, 0.85f, 1.6f)
        listOf(66.7f, 87.3f).forEach { x -> seg(x, Ground - 39f, x, Ground - 3f, colors.ink, 0.22f, 1f) }
        rr(46f, Ground - 27f, 62f, 7f, 0f)
        fill(colors.ink, 0.14f)
        rr(49f, Ground - 45f, 56f, 4f, 2f)
        fill(colors.ink, 0.55f * cl(lid.x))
        // The lid turns on the corner of the crate that is away from the device.
        at(46f, Ground - 42f, -lid.x * 1.9f) {
            rr(-2f, -5f, 66f, 5f, 2f)
            fill(colors.paper)
            fill(colors.ink, 0.16f)
            stroke(colors.ink, 0.85f, 1.5f)
        }
    }

    private fun Pen.device(
        p: Float,
        leaves: Float,
        dt: Float,
    ) {
        shadow(DeviceX, Ground + 1f, 30f)
        phone(DeviceX, Ground, 58f, 96f, 0f, colors.night)
        rr(198f, 53f, 16f, 3f, 1.5f)
        fill(colors.tone(0.3f))
        repeat(Items) { i ->
            val y = slotY(i)
            val landed = p >= launch(i) + Flight
            if (loop.passed(launch(i) + Flight)) {
                docks[i].v += 7f
                parts.ring(SlotX, y, colors.glow, 4f)
            }
            docks[i].step(0f, 220f, 13f, dt)
            val shown = if (landed) 1f - leaves else 0f
            rr(184f, y - 9f, 44f, 18f, 5f)
            fill(colors.tone(0.2f), shown)
            stroke(colors.tone(0.45f), 1f, 1f, dash = if (shown < 0.5f) 2f else 0f, gap = 2.5f)
            if (shown > 0f) {
                val bump = 1f + docks[i].x * 0.06f
                at(SlotX, y, 0f, bump, bump) { glyph(i, colors.nightLight, shown) }
                rr(203f, y - 1.5f, 11f, 3f, 1.5f)
                fill(colors.tone(0.5f), shown)
                circ(221.5f, y, 4f * bump)
                fill(colors.signal, shown)
                seg(219.7f, y, 221f, y + 1.5f, colors.night, shown, 1.2f)
                seg(221f, y + 1.5f, 223.5f, y - 1.6f, colors.night, shown, 1.2f)
            }
        }
    }

    /** What the preset holds, each about 12 units wide: a server, a route with two ends, and two controls. */
    private fun Pen.glyph(
        item: Int,
        color: Color,
        a: Float,
    ) {
        when (item) {
            0 -> {
                listOf(-6f, 0.5f).forEach { y ->
                    rr(-6f, y, 12f, 5f, 1.5f)
                    stroke(color, a, 1.3f)
                    circ(-3.2f, y + 2.5f, 0.9f)
                    fill(color, a)
                }
            }

            1 -> {
                listOf(-4.5f, 4.5f).forEach { y ->
                    shape {
                        moveTo(-6f, 0f)
                        lineTo(-1f, 0f)
                        quadraticTo(2f, y, 6f, y)
                    }
                    stroke(color, a, 1.3f)
                    circ(6f, y, 1.4f)
                    fill(color, a)
                }
                circ(-6f, 0f, 1.4f)
                fill(color, a)
            }

            else -> {
                listOf(-3f, 3f).forEach { y ->
                    seg(-6f, y, 6f, y, color, a, 1.3f)
                    circ(if (y < 0f) -2f else 3f, y, 2f)
                    fill(color, a)
                }
            }
        }
    }
}
