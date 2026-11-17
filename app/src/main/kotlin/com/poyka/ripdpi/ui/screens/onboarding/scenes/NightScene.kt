// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.abs
import kotlin.math.sin

private const val NightGround = Ground - 6f
private const val Watch = 10f
private const val GraveX = 206f
private const val HandX = 190f

// The stars: the place, the size, and the phase of each.
private val Stars =
    floatArrayOf(
        24f,
        22f,
        2f,
        0.4f,
        52f,
        54f,
        1.4f,
        2.1f,
        78f,
        18f,
        2.6f,
        4.4f,
        104f,
        40f,
        1.6f,
        1.2f,
        126f,
        14f,
        2.2f,
        3.3f,
        148f,
        62f,
        1.3f,
        5.1f,
        170f,
        26f,
        2.8f,
        0.9f,
        192f,
        48f,
        1.5f,
        2.8f,
        206f,
        16f,
        1.9f,
        4f,
        262f,
        22f,
        2.4f,
        1.7f,
        274f,
        62f,
        1.4f,
        3.7f,
        40f,
        76f,
        1.3f,
        5.6f,
    )

/**
 * The night watch. Day turns to night at the grave of DPI. The phone sleeps, and its tunnel key stays lit. The
 * ghost keeps its round with a lantern. When a hand comes up from the grave, the ghost goes to it, and the light of
 * the lantern sends the hand back down.
 */
internal class NightScene(
    parts: Parts,
) : Scene(parts) {
    private val hand = Spring()
    private var stirred = -1
    private var settled = -1

    override val stillAt = 6f

    override fun Pen.draw(frame: Frame) {
        val night = ease((since - 0.15f) / 1.1f)
        val cycle = since % Watch
        val round = (since / Watch).toInt()
        val ghostX =
            when {
                cycle < 3f -> mix(50f, 122f, ease(cycle / 3f))
                cycle < 3.5f -> 122f
                cycle < 4.4f -> mix(122f, 178f, ease((cycle - 3.5f) / 0.9f))
                cycle < 6f -> 178f
                else -> mix(178f, 50f, ease((cycle - 6f) / 4f))
            }
        sky(1f - night)
        rr(8f, 5f, 284f, 156f, 20f)
        clipped {
            faded(night) {
                heavens()
                tree(34f, NightGround + 2f, 1.15f, colors.night, 1f)
                owl(ghostX)
                fence(NightGround + 1f, 0.5f, colors.night)
                hill(NightGround - 2f, 5f, 29f, 4.4f, colors.tone(0.14f), 1f)
                rr(0f, NightGround, SceneWidth, 40f, 0f)
                fill(colors.tone(0.16f))
                grave(cycle, round, ghostX, frame.dt)
                sleeper()
                grass(60f, NightGround + 2f, 5, colors.tone(0.27f), 1f)
                grass(150f, NightGround + 3f, 4, colors.tone(0.27f), 1f)
                grass(250f, NightGround + 2f, 5, colors.tone(0.27f), 1f)
                watcher(cycle, ghostX)
                repeat(6) { i ->
                    val x = 40f + i * 44f + sin(time * 0.7f + i * 1.9f) * 18f
                    val y = NightGround - 34f + sin(time * 0.9f + i * 2.7f) * 14f
                    wisp(x, y, 1.2f, 0.3f + 0.7f * maxOf(0f, sin(time * 2.2f + i * 2f)))
                }
            }
        }
        rr(8f, 5f, 284f, 156f, 20f)
        stroke(colors.ink, 0.2f * night, 1f)
    }

    private fun Pen.heavens() {
        rr(8f, 5f, 284f, 156f, 20f)
        fill(colors.tone(0.06f))
        repeat(Stars.size / 4) { i ->
            val twinkle = 0.5f + 0.5f * sin(time * 2f + Stars[i * 4 + 3])
            star(Stars[i * 4], Stars[i * 4 + 1], Stars[i * 4 + 2] * (0.6f + 0.5f * twinkle), Stars[i * 4 + 3])
            fill(colors.nightLight, 0.35f + 0.6f * twinkle)
        }
        val fall = (time * 0.11f) % 1.6f
        if (fall < 0.22f) {
            val u = fall / 0.22f
            seg(60f + u * 90f, 20f + u * 26f, 40f + u * 90f, 14f + u * 26f, colors.nightLight, 1f - u, 1.4f)
        }
        circ(232f, 36f, 22f)
        fill(colors.glow, 0.07f)
        circ(232f, 36f, 15f)
        fill(colors.nightLight)
        circ(238.5f, 31.5f, 13f)
        fill(colors.tone(0.06f))
        cloud((time * 5f) % 380f - 40f, 30f, 1.1f, colors.tone(0.16f), 1f)
        cloud((time * 3f + 190f) % 380f - 40f, 56f, 0.8f, colors.tone(0.12f), 1f)
        hill(NightGround - 10f, 12f, 50f, 2f, colors.tone(0.1f), 1f)
    }

    /** The eyes of an owl in the tree. They follow the ghost. */
    private fun Pen.owl(ghostX: Float) {
        val open = if (fract(time * 0.17f) > 0.92f) 0.15f else 1f
        val look = cl((ghostX - 34f) / 160f, -1f, 1f) * 1.3f
        listOf(26f, 31.5f).forEach { x ->
            ell(x, NightGround - 52f, 2.3f, 2.3f * open)
            fill(colors.glowHigh)
            circ(x + look, NightGround - 52f, open)
            fill(colors.night)
        }
    }

    /** The grave of DPI. The soil stirs, a hand comes up, and it goes back down when the lantern is near. */
    private fun Pen.grave(
        cycle: Float,
        round: Int,
        ghostX: Float,
        dt: Float,
    ) {
        val active = cycle > 2.4f && cycle < 8f
        val lanternNear = ghostX >= 160f
        hand.step(
            if (active &&
                !lanternNear
            ) {
                1f
            } else {
                0f
            },
            if (lanternNear) 90f else 26f,
            if (lanternNear) 13f else 7f,
            dt,
        )
        val up = cl(hand.x, 0f, 1.1f)
        if (cycle > 2.2f && stirred != round) {
            stirred = round
            parts.chips(HandX, NightGround + 1f, colors.tone(0.38f))
        }
        if (cycle > 4.3f && cycle < 4.6f && settled != round) {
            settled = round
            parts.dust(HandX, NightGround + 1f, colors.tone(0.38f), 6)
        }
        ell(GraveX, NightGround + 2f, 34f, 7f)
        fill(colors.tone(0.03f))
        ell(HandX, NightGround + 2f, 9f + up * 3f, 2.5f + up)
        fill(colors.night)
        tomb(GraveX, NightGround + 1f, 42f, 54f, carving = 1f, cracked = 1f, night = true)
        if (up > 0.02f) {
            at(HandX, NightGround + 3f, -0.2f + sin(time * 9f) * 0.05f * up) {
                repeat(4) { k ->
                    rr(-5f + k * 3f, -13f * up - (k % 2) * 2f * up + sin(time * 6f + k) * up, 2.2f, 9f * up + 2f, 1.1f)
                    fill(colors.tone(0.6f))
                }
                rr(-5.5f, -6f * up, 12f, 6f * up + 2f, 2f)
                fill(colors.tone(0.6f))
            }
        }
    }

    /** The phone sleeps. The key of its tunnel stays lit. */
    private fun Pen.sleeper() {
        val breath = sin(time * 1.4f) * 0.015f
        shadow(104f, NightGround + 1f, 16f, 0.3f)
        at(104f, NightGround, 0f, 1f - breath, 1f + breath) {
            phone(0f, 0f, 26f, 46f, -0.07f, colors.night, colors.tone(0.24f))
            val lit = 0.6f + 0.4f * sin(time * 2.1f)
            circ(-3f, -25f, 3.2f)
            stroke(colors.glow, lit, 1.5f)
            seg(0f, -25f, 7f, -25f, colors.glow, lit, 1.5f)
            seg(5f, -25f, 5f, -22.5f, colors.glow, lit, 1.5f)
        }
        repeat(3) { i ->
            val u = fract(time * 0.3f + i / 3f)
            text(
                "z",
                121f + u * 12f + sin(u * 6f) * 3f,
                NightGround - 48f - u * 26f,
                7f + u * 6f,
                colors.nightLight,
                (1f - u) * 0.8f,
            )
        }
    }

    /** The ghost on its round, with the lantern in front of it. */
    private fun Pen.watcher(
        cycle: Float,
        ghostX: Float,
    ) {
        val side = if (cycle < 6f) 1f else -1f
        val rush = cycle > 3.5f && cycle < 4.4f
        val walks = cycle < 3f || cycle > 6f
        val swing = if (rush) -side * 5f else sin(time * 2.6f) * 1.5f
        val lampX = ghostX + side * 17f + swing
        val lampY = NightGround - 22f + abs(swing) * 0.4f
        val flicker = 1f + sin(time * 23f) * 0.06f + sin(time * 7f) * 0.05f
        val wide = if (cycle > 4.2f && cycle < 6f) 1.25f else 1f
        ell(lampX, NightGround + 3f, 20f * wide, 4f)
        fill(colors.glow, 0.16f)
        listOf(22f, 13f, 7f).forEachIndexed { i, r ->
            circ(lampX, lampY, r * flicker * wide)
            fill(colors.glow, 0.07f + i * i * 0.06f)
        }
        ghost(
            Ghost(
                x = ghostX,
                y = NightGround - 6f,
                s = 1.05f,
                lag =
                    if (rush) {
                        1f
                    } else if (walks) {
                        0.45f
                    } else {
                        0f
                    },
                flip = side < 0f,
                look = 1f,
                lookY = if (cycle > 4.3f && cycle < 6f) 1f else 0f,
                eye = if (cycle > 3f && cycle < 4.6f) Eye.Narrow else Eye.Open,
                id = 6,
                night = true,
            ),
        )
        seg(ghostX + side * 10f, NightGround - 22f, lampX, lampY - 5f, colors.nightLight, 0.9f, 1.2f)
        rr(lampX - 3f, lampY - 4.5f, 6f, 7.5f, 2f)
        fill(colors.glowHigh)
        stroke(colors.nightLight, 1f, 1f)
    }
}
