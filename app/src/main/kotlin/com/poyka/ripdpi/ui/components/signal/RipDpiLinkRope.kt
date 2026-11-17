package com.poyka.ripdpi.ui.components.signal

import kotlin.math.hypot

/** Where the cable is held: at the plug, in the line of the plug, and at the edge of the tile. Sizes are in dp. */
internal class LinkRopeEnds {
    var fromX = 0f
    var fromY = 0f

    /** The direction in which the cable leaves the plug, as a unit vector. */
    var alongX = 1f
    var alongY = 0f
    var toX = 0f
    var toY = 0f

    /** The length of the cable as a share of the straight distance between its ends. More than 1 lets it hang. */
    var stretch = 1f
}

/**
 * The cable behind the plug: a chain of points of a fixed spacing that hangs under its weight. Two points at each
 * end are held, so the cable leaves the plug in the line of the plug and leaves the tile level. The points between
 * them fall, and the spacing pulls them back, so the cable swings when the plug moves and then comes to rest.
 */
internal class LinkRope {
    private val x = FloatArray(RopeNodes)
    private val y = FloatArray(RopeNodes)
    private val beforeX = FloatArray(RopeNodes)
    private val beforeY = FloatArray(RopeNodes)
    private var spare = 0f

    // The ends that the cable was put at rest between, and whether it has not moved since.
    private val rested = LinkRopeEnds()
    private var atRest = false

    /** The speed of the fastest point, in dp a second. */
    var speed = 0f
        private set

    val startX: Float get() = x[0]
    val endX: Float get() = x[RopeNodes - 1]

    /** Moves the cable by [seconds], in steps of a fixed length so that the motion does not depend on the frames. */
    fun step(
        seconds: Float,
        ends: LinkRopeEnds,
    ) {
        spare += seconds
        while (spare >= RopeStep) {
            spare -= RopeStep
            atRest = false
            move(ends, RopeDamping)
        }
    }

    /** Puts the cable at rest between [ends]. */
    fun settle(ends: LinkRopeEnds) {
        // The cable is already at rest between these ends, so the still frame of a new traffic level is free.
        if (rests(ends)) return
        for (index in 0 until RopeNodes) {
            val share = index / (RopeNodes - 1f)
            x[index] = ends.fromX + (ends.toX - ends.fromX) * share
            y[index] = ends.fromY + (ends.toY - ends.fromY) * share
            beforeX[index] = x[index]
            beforeY[index] = y[index]
        }
        repeat(SettleSteps) { move(ends, SettleDamping) }
        x.copyInto(beforeX)
        y.copyInto(beforeY)
        speed = 0f
        spare = 0f
        keep(ends)
    }

    private fun rests(ends: LinkRopeEnds): Boolean {
        val from = rested.fromX == ends.fromX && rested.fromY == ends.fromY
        val along = rested.alongX == ends.alongX && rested.alongY == ends.alongY
        val to = rested.toX == ends.toX && rested.toY == ends.toY && rested.stretch == ends.stretch
        return atRest && from && along && to
    }

    private fun keep(ends: LinkRopeEnds) {
        rested.fromX = ends.fromX
        rested.fromY = ends.fromY
        rested.alongX = ends.alongX
        rested.alongY = ends.alongY
        rested.toX = ends.toX
        rested.toY = ends.toY
        rested.stretch = ends.stretch
        atRest = true
    }

    /** The place of the cable at the share [t] of its length, on a smooth curve through its points. */
    fun x(t: Float): Float = along(x, t)

    fun y(t: Float): Float = along(y, t)

    private fun move(
        ends: LinkRopeEnds,
        damping: Float,
    ) {
        val rest = hypot(ends.toX - ends.fromX, ends.toY - ends.fromY) / (RopeNodes - 1) * ends.stretch
        for (index in FirstFree..LastFree) {
            val moveX = (x[index] - beforeX[index]) * damping
            val moveY = (y[index] - beforeY[index]) * damping
            beforeX[index] = x[index]
            beforeY[index] = y[index]
            x[index] += moveX
            y[index] += moveY + RopeGravity * RopeStep * RopeStep
        }
        x[0] = ends.fromX
        y[0] = ends.fromY
        x[1] = ends.fromX + ends.alongX * rest
        y[1] = ends.fromY + ends.alongY * rest
        x[RopeNodes - 1] = ends.toX
        y[RopeNodes - 1] = ends.toY
        x[RopeNodes - 2] = ends.toX - rest
        y[RopeNodes - 2] = ends.toY
        repeat(RopeIterations) {
            for (index in 0 until RopeNodes - 1) space(index, rest)
        }
        var fastest = 0f
        for (index in FirstFree..LastFree) {
            fastest = maxOf(fastest, hypot(x[index] - beforeX[index], y[index] - beforeY[index]) / RopeStep)
        }
        speed = fastest
    }

    /** Puts the points [index] and the next at the distance [rest]. A held point does not move. */
    private fun space(
        index: Int,
        rest: Float,
    ) {
        val first = index in FirstFree..LastFree
        val second = index + 1 in FirstFree..LastFree
        val gapX = x[index + 1] - x[index]
        val gapY = y[index + 1] - y[index]
        val gap = hypot(gapX, gapY)
        if (gap < Tiny || !(first || second)) return
        val part = (gap - rest) / gap * if (first && second) Half else 1f
        if (first) {
            x[index] += gapX * part
            y[index] += gapY * part
        }
        if (second) {
            x[index + 1] -= gapX * part
            y[index + 1] -= gapY * part
        }
    }

    /** A cubic through the points: at each point the curve runs in the direction from the point before to the next. */
    private fun along(
        values: FloatArray,
        t: Float,
    ): Float {
        val place = t.coerceIn(0f, 1f) * (RopeNodes - 1)
        val index = minOf(place.toInt(), RopeNodes - 2)
        val f = place - index
        val from = values[index]
        val to = values[index + 1]
        val leave = (to - values[maxOf(index - 1, 0)]) * Half
        val arrive = (values[minOf(index + 2, RopeNodes - 1)] - from) * Half
        val square = f * f
        val cube = square * f
        return (2f * cube - Three * square + 1f) * from + (cube - 2f * square + f) * leave +
            (Three * square - 2f * cube) * to + (cube - square) * arrive
    }
}

private const val RopeNodes = 10
private const val FirstFree = 2
private const val LastFree = RopeNodes - 3

// The cable moves in steps of 1/120 second. Its weight is in dp a second squared.
private const val RopeStep = 1f / 120f
private const val RopeGravity = 900f
private const val RopeDamping = 0.98f
private const val RopeIterations = 10
private const val SettleSteps = 240
private const val SettleDamping = 0.9f
private const val Tiny = 0.001f
private const val Half = 0.5f
private const val Three = 3f
