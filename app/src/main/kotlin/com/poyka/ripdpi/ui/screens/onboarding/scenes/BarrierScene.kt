// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private const val Pi = PI.toFloat()
private const val Gates = 3
private const val Fallen = 9
private val GateX = floatArrayOf(84f, 146f, 208f)
private val DoorCrack = floatArrayOf(1f, -46f, -4f, -36f, 3f, -27f, -3f, -17f, 2f, -8f, -2f, 0f)
private val DoorBranchA = floatArrayOf(3f, -27f, 9f, -22f)
private val DoorBranchB = floatArrayOf(-3f, -17f, -10f, -13f)
private val ArchCrack = floatArrayOf(-13f, -30f, -11f, -38f, -14f, -43f)

// The rubble of a fallen door: the place and the size of each stone.
private val Rubble = floatArrayOf(-7f, -2f, 4f, 1f, -3f, 5f, 7f, -1f, 3.5f, -2f, -7f, 3.5f, 4f, -8f, 2.6f)

/** The road rises a little in the middle of the scene. */
private fun road(x: Float): Float = Ground - 7f * sin(cl((x - 10f) / 280f) * Pi)

/**
 * Three barriers, one for each stage of the connection test, on the road to the open internet. A scanner reads
 * the ghost at each barrier. On a pass the ghost does not open the barrier: it goes through it, and the barrier
 * cracks. On a failure the barrier takes the failure color, bars come down, and the ghost is pushed back.
 *
 * With [arrival] it is the last scene: after a passed test the barriers fall into rubble one after the other,
 * small ghosts drift through the arches, and the seal is the app icon with a new crack in its stone.
 */
internal class BarrierScene(
    parts: Parts,
    private val arrival: Boolean,
) : Scene(parts) {
    private class Follower(
        var x: Float,
        val height: Float,
        val id: Int,
    )

    private val doors = List(Gates) { Spring() }
    private val marks = IntArray(Gates)
    private val popped = FloatArray(Gates) { -9f }
    private val followers = ArrayList<Follower>()
    private val walker = Spring(-14f)
    private val sealIn = Spring()
    private var shake = 0f
    private var failed = false
    private var ringed = -1
    private var due = 0f
    private var sealed = false

    override val stillAt = 6f

    override fun enter(frame: Frame) {
        super.enter(frame)
        followers.clear()
        sealIn.x = 0f
        sealIn.v = 0f
        sealed = false
        ringed = -1
        if (arrival) {
            // The doors of a passed test are already cracked when the last scene opens.
            doors.forEach { it.x = if (frame.passed) 1f else 0f }
            marks.fill(if (frame.passed) 4 else 0)
        } else if (!frame.passed) {
            walker.x = -14f
            walker.v = 0f
        }
    }

    override fun Pen.draw(frame: Frame) {
        val dt = frame.dt
        val bad = if (arrival) -1 else frame.failedAt
        val done =
            when {
                frame.passed -> Gates
                arrival -> 0
                bad >= 0 -> bad
                else -> frame.stage
            }
        if (bad >= 0 && !failed) {
            shake = 1f
            walker.v = -70f
            parts.spark(GateX[bad] - 12f, road(GateX[bad]) - 14f, colors.danger, 6, 60f)
        }
        failed = bad >= 0
        shake *= exp(-dt * 5f)
        sky(0.8f)
        roadAndGlobe(done)
        repeat(Gates) { i -> rise(1f + i) { gate(i, done, bad, frame.running && !arrival, dt) } }
        if (arrival) arrive(frame.passed, dt) else traveller(done, bad, frame.running, dt)
    }

    private fun Pen.roadAndGlobe(done: Int) {
        shape {
            moveTo(-4f, road(-4f) + 3f)
            var x = 4f
            while (x <= SceneWidth + 4f) {
                lineTo(x, road(x) + 3f)
                x += 8f
            }
        }
        stroke(colors.ink, 0.13f, 9f)
        rise(4f) {
            val open = done >= Gates
            globe(270f, road(270f) - 30f, 15f, 0f, if (open) 1f else 0f)
            if (open) {
                repeat(2) { i ->
                    val u = fract(time * 0.35f + i * 0.5f)
                    circ(270f, road(270f) - 30f, 17f + u * 16f)
                    stroke(colors.signal, (1f - u) * 0.5f, 1.3f)
                }
            }
        }
    }

    private fun Pen.archShape(half: Float) {
        shape {
            moveTo(-half, 0f)
            lineTo(-half, -34f)
            arcTo(Rect(-half, -34f - half, half, -34f + half), 180f, 180f, false)
            lineTo(half, 0f)
        }
    }

    private fun Pen.gate(
        index: Int,
        done: Int,
        bad: Int,
        running: Boolean,
        dt: Float,
    ) {
        val open = index < done
        val isBad = bad == index
        val door = doors[index]
        door.step(if (open) 1f else 0f, 34f, 7f, dt)
        val k = cl(door.x)
        val fall = if (arrival && open) ease((since - 0.35f - index * 0.4f) / 0.55f) else 0f
        gateEvents(index, open, k, fall)
        val x = GateX[index]
        at(x, road(x) + 2f, if (isBad) sin(time * 38f) * shake * 0.07f else 0f) {
            shadow(0f, 1f, 18f)
            if (fall < 1f) door(k, fall, isBad, running && index == done)
            if (fall > 0.3f) rubble(cl((fall - 0.3f) / 0.7f))
            archShape(13f)
            stroke(colors.ink, 0.95f, 7.5f)
            stroke(colors.paper, 1f, 4.4f)
            stroke(colors.ink, 0.16f, 4.4f)
            listOf(-13f, 13f).forEach { foot ->
                rr(foot - 5f, -4f, 10f, 5f, 2f)
                fill(colors.ink, 0.95f)
            }
            if (open && k > 0.6f) crack(ArchCrack, 3, colors.ink, 0.9f, 1.3f)
            seg(0f, -47f, 0f, -52f, colors.ink, 0.9f, 2f)
            lamp(index, open, isBad, running && index == done)
        }
    }

    /** The marks of a barrier that the ghost has passed: its lamp pops, and chips fall as the crack grows. */
    private fun Pen.gateEvents(
        index: Int,
        open: Boolean,
        k: Float,
        fall: Float,
    ) {
        val x = GateX[index]
        val y = road(x) + 2f
        if (!open) {
            marks[index] = 0
            return
        }
        if (marks[index] == 0) {
            marks[index] = 1
            popped[index] = time
        }
        val steps = floatArrayOf(0.25f, 0.55f, 0.85f)
        val step = marks[index] - 1
        if (step in steps.indices && k > steps[step]) {
            marks[index]++
            parts.chips(x + (step - 1) * 3f, y - 40f + step * 13f, colors.ink, 3)
        }
        if (fall > 0f && marks[index] < Fallen) {
            marks[index] = Fallen
            parts.dust(x - 6f, y, colors.mid)
            parts.dust(x + 6f, y, colors.mid)
            parts.chips(x, y - 20f, colors.ink, 7)
        }
    }

    private fun Pen.door(
        k: Float,
        fall: Float,
        isBad: Boolean,
        scanned: Boolean,
    ) {
        archShape(10.5f)
        path.close()
        clipped {
            at(0f, fall * 46f, fall * 0.25f) {
                rr(-11f, -48f, 22f, 48f, 0f)
                fill(if (isBad) colors.danger else colors.ink, 0.95f)
                repeat(6) { row ->
                    seg(-11f, -6f - row * 8f, 11f, -6f - row * 8f, colors.paper, 0.2f, 1f)
                    val joint = if (row % 2 == 1) -4f else 4f
                    seg(joint, -6f - row * 8f, joint, 2f - row * 8f, colors.paper, 0.2f, 1f)
                }
                if (k > 0f) {
                    crack(DoorCrack, 2 + (k * 4f).roundToInt(), colors.paper, 0.95f, 1.6f)
                    if (k > 0.5f) {
                        crack(DoorBranchA, 2, colors.paper, 0.9f, 1.2f)
                        crack(DoorBranchB, 2, colors.paper, 0.9f, 1.2f)
                    }
                }
                if (scanned) {
                    val y = -44f + (time * 30f) % 44f
                    rr(-11f, y, 22f, 2.5f, 0f)
                    fill(colors.danger, 0.9f)
                    rr(-11f, y - 7f, 22f, 7f, 0f)
                    fill(colors.danger, 0.25f)
                }
                if (isBad) {
                    // Bars come down on a barrier that did not let the ghost through.
                    repeat(
                        3,
                    ) { bar ->
                        seg(
                            -6f + bar * 6f,
                            -48f,
                            -6f + bar * 6f,
                            -48f + 48f * cl((1f - shake) * 3f),
                            colors.paper,
                            0.9f,
                            2f,
                        )
                    }
                }
            }
        }
    }

    private fun Pen.rubble(grown: Float) {
        repeat(Rubble.size / 3) { i ->
            val size = Rubble[i * 3 + 2] * grown
            rr(Rubble[i * 3] - size, Rubble[i * 3 + 1] * grown - size * 0.7f, size * 2f, size * 1.5f, size * 0.5f)
            fill(colors.ink, 0.9f)
        }
    }

    /** The lamp of a barrier: its number, the eye of its scanner, a check mark, or a cross. */
    private fun Pen.lamp(
        index: Int,
        open: Boolean,
        isBad: Boolean,
        scanning: Boolean,
    ) {
        val age = time - popped[index]
        val pop = if (open) 1f + exp(-age * 7f) * sin(age * 18f) * 0.5f else 1f
        at(0f, -59f, 0f, pop, pop) {
            circ(0f, 0f, 8f)
            fill(colors.paper)
            val tint =
                when {
                    isBad -> colors.danger
                    open -> colors.signal
                    else -> colors.ink
                }
            fill(tint, if (open || isBad) 1f else 0.07f)
            stroke(colors.ink, 0.9f, 1.5f)
            when {
                scanning -> {
                    ell(0f, 0f, 5.4f, 5.4f * abs(sin(time * 3f)) + 0.6f)
                    fill(colors.paper)
                    stroke(colors.danger, 1f, 1.3f)
                    circ(cl((walker.x - GateX[index]) / 8f, -2f, 2f), 0f, 2f)
                    fill(colors.danger)
                }

                isBad -> {
                    seg(-2.6f, -2.6f, 2.6f, 2.6f, colors.paper, 1f, 1.7f)
                    seg(-2.6f, 2.6f, 2.6f, -2.6f, colors.paper, 1f, 1.7f)
                }

                open -> {
                    shape {
                        moveTo(-3.4f, 0f)
                        lineTo(-1f, 2.6f)
                        lineTo(3.6f, -2.6f)
                    }
                    stroke(colors.paper, 1f, 1.7f)
                }

                else -> {
                    text((index + 1).toString(), 0f, 0.5f, 9f, colors.ink, 0.75f)
                }
            }
        }
    }

    /** The ghost on the road during the test. */
    private fun Pen.traveller(
        done: Int,
        bad: Int,
        running: Boolean,
        dt: Float,
    ) {
        val target =
            when {
                bad >= 0 -> GateX[bad] - 33f
                done >= Gates -> 242f
                running -> GateX[done] - 24f
                else -> 30f
            }
        walker.step(target, 24f, 8.5f, dt)
        var through = 0f
        repeat(done.coerceAtMost(Gates)) { i ->
            val near = exp(-((walker.x - GateX[i]) / 15f) * ((walker.x - GateX[i]) / 15f))
            through = maxOf(through, near)
            if (near > 0.75f && ringed != i) {
                ringed = i
                parts.ring(GateX[i], road(GateX[i]) - 20f, colors.signal, 5f)
            }
        }
        if (bad >= 0) through = 0f
        val y = road(walker.x) - 3f
        val waits = running && bad < 0 && abs(walker.v) < 8f
        val eye =
            when {
                bad >= 0 -> Eye.Crossed
                waits || through > 0.3f -> Eye.Narrow
                else -> Eye.Open
            }
        val x = walker.x + if (bad >= 0) sin(time * 50f) * shake * 2f else 0f
        shadow(walker.x, y + 5f, 9f * (1f - through * 0.8f), 0.09f * (1f - through))
        ghost(
            Ghost(
                x = x,
                y = y,
                ph = through * 0.97f,
                lag = walker.v / 60f,
                tilt = if (bad >= 0) -0.14f - shake * 0.2f else 0f,
                look = 1f,
                lookY = if (waits) -0.8f else 0f,
                eye = eye,
                id = 7,
            ),
        )
        if (waits) scanLine(x, y, (time * 30f) % 44f / 44f)
    }

    /** The last scene: the followers, the ghost at the globe, and the seal. */
    private fun Pen.arrive(
        passed: Boolean,
        dt: Float,
    ) {
        if (passed && since > 1.6f) {
            due += dt
            if (due > 0.8f) {
                due = 0f
                followers += Follower(-14f, 8f + parts.rnd(34f), parts.rnd(9f).toInt())
            }
        }
        followers.forEach { it.x += dt * 36f }
        followers.removeAll { it.x > SceneWidth }
        followers.forEach { follower ->
            val a = cl((follower.x + 14f) / 20f) * cl((252f - follower.x) / 24f) * 0.9f
            ghost(
                Ghost(
                    follower.x,
                    road(follower.x) - follower.height,
                    0.5f,
                    a = a,
                    lag = 0.38f,
                    look = 1f,
                    id = follower.id,
                ),
            )
        }
        // The ghost looks back at the rubble, and then turns to the globe.
        val looksBack = passed && since < 2.2f
        val x = if (passed) 238f else 34f
        val y = if (passed) road(238f) - 14f + sin(time * 0.6f) * 4f else road(34f) - 3f
        ghost(Ghost(x, y, 1.1f, flip = looksBack, look = 1f, eye = if (looksBack) Eye.Narrow else Eye.Open, id = 7))
        seal(passed, dt)
    }

    private fun Pen.seal(
        passed: Boolean,
        dt: Float,
    ) {
        sealIn.step(if (since > 2f) 1f else 0f, 150f, 10f, dt)
        val k = cl(sealIn.x, 0f, 1.3f)
        val tint = if (passed) colors.signal else colors.mid
        if (k > 0.9f && !sealed) {
            sealed = true
            parts.ring(150f, 30f, tint, 22f)
        }
        if (k <= 0.02f) return
        at(150f, 30f, 0f, k, k) {
            at(0f, 0f, time * 0.3f) {
                circ(0f, 0f, 24f)
                stroke(tint, 0.6f, 1.3f, dash = 3f, gap = 4f)
            }
            circ(0f, 0f, 19f)
            fill(colors.paper)
            stroke(colors.ink, 0.9f, 1.5f)
            tomb(0f, 13f, 22f, 27f, cracked = if (passed) cl((since - 2.6f) / 0.8f) else 0f)
            ghost(Ghost(0f, 7.5f, 0.42f, still = true, id = 1))
            val drawn = cl((since - 3f) / 0.4f)
            if (passed && drawn > 0f) {
                circ(14f, 12f, 7f * back(drawn))
                fill(colors.signal)
                val first = cl(drawn / 0.4f)
                val second = cl((drawn - 0.4f) / 0.6f)
                shape {
                    moveTo(10.6f, 12f)
                    lineTo(10.6f + 2.4f * first, 12f + 2.4f * first)
                    if (second > 0f) lineTo(13f + 4.6f * second, 14.4f - 5.4f * second)
                }
                stroke(colors.paper, 1f, 1.8f)
            }
        }
    }
}
