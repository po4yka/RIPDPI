package com.poyka.ripdpi.ui.components.signal

import com.poyka.ripdpi.ui.theme.RipDpiMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The state of the link that the hero tile shows. The caller names the state; the figure never carries it alone. */
enum class RipDpiLinkMode {
    /** No session: the plug lies out of the socket. */
    Open,

    /** A session is being set up: the plug waits at the socket, and goes in one contact for each completed stage. */
    Seeking,

    /** A session is live: the plug is seated and packets travel in the cable. */
    Live,

    /** A session is live with a warning. */
    Unstable,

    /** The session failed: the plug stops at the socket, and the lamp of the failed stage shows the failure. */
    Broken,

    /** The session is paused: the plug is held at the latch, with its contacts apart. */
    Held,
}

/** The state of one stage of the path. The socket has one lamp for each stage. */
enum class RipDpiLinkStage { Pending, Active, Complete, Warning, Failed, Held }

/** What the figure follows. Sizes are in dp; [sent] and [received] are traffic levels from 0 to 1. */
internal class LinkInput(
    val mode: RipDpiLinkMode,
    val stages: List<RipDpiLinkStage>,
    val sent: Float = 0f,
    val received: Float = 0f,
    /** How far the connection action is pressed, from 0 to 1. A loose plug leans toward the socket. */
    val press: Float = 0f,
    /** How far a finger holds the plug out of the socket, or null when no finger holds it. */
    val drag: Float? = null,
    /** The length of the cable from one edge of the tile to the other. */
    val length: Float = LinkDefaultLength,
    /** How far along the cable the plug ends. A probe packet starts there. */
    val probeFrom: Float = LinkDefaultProbeFrom,
)

/** A damped spring on one value. It can take a new target at any time and keeps its speed. */
internal class LinkSpring(
    var at: Float = 0f,
) {
    var rate = 0f

    fun step(
        target: Float,
        stiffness: Float,
        damping: Float,
        seconds: Float,
    ) {
        val part = seconds / SpringSteps
        repeat(SpringSteps) {
            rate += ((target - at) * stiffness - rate * damping) * part
            at += rate * part
        }
    }

    fun snap(target: Float) {
        at = target
        rate = 0f
    }
}

/** One packet in a lane of the cable. [at] is how far it has travelled from its own end of the cable, in dp. */
internal class LinkPulse(
    var at: Float,
    val length: Float = LinkPulseLength,
)

/** A lane of the cable: sent packets travel away from the device, received packets toward it. */
internal class LinkLane {
    val pulses = ArrayList<LinkPulse>()

    /** How visible the lane is, from 0 to 1. */
    var alpha = 0f
    var level = 0f
    var until = 0f
    var wait = 0f

    /** How many more packets of the present burst follow closely. */
    var burst = 0
}

/** One lamp of the socket. A warning gives it four points, and a failure turns the points to a cross. */
internal class LinkLamp {
    /** The stage that the lamp shows. It follows the stage of the path when its turn in the cascade comes. */
    var stage = RipDpiLinkStage.Pending

    /** The stage that the lamp showed before, and how far its color has changed from it, from 0 to 1. */
    var before = RipDpiLinkStage.Pending
    var mix = 1f

    /** The size of the lamp. It swells for a moment when the lamp changes. */
    val size = LinkSpring(1f)
    var next = RipDpiLinkStage.Pending
    var wait = 0f
    var glow = 0f
    var notch = 0f
    var turn = 0f
    var phase = 0f
}

/**
 * The moving parts of the link tile: a plug, a socket, a cable, and the packets in it. [advance] moves them by one
 * frame; [settle] puts them in the still frame of a state. Sizes are in dp and times in seconds.
 */
internal class LinkFigure(
    private val random: Random = Random(LinkSeed),
    motion: RipDpiMotion = RipDpiMotion(),
) {
    // The duration buckets of the app, in seconds.
    private val quick = motion.quickDurationMillis / MillisPerSecond
    private val state = motion.stateDurationMillis / MillisPerSecond
    private val emphasized = motion.emphasizedDurationMillis / MillisPerSecond

    /** How far the plug is out of the socket. Below zero it presses into the socket. */
    val pull = LinkSpring(LinkGap)

    /** The socket after the plug hits it. */
    val recoil = LinkSpring()

    /** The latch tab of the plug. */
    val click = LinkSpring()

    /** The cable behind the plug. It hangs under its weight, and swings when the plug moves. */
    val rope = LinkRope()
    private val ends = LinkRopeEnds()

    /** How much the plug lies down, from 0 in line with the socket to 1. A plug that falls out bounces once. */
    val lie = LinkSpring(1f)

    /** How much the plug is turned, as [lie]. It turns more slowly than it falls, so it rocks after it lands. */
    val tilt = LinkSpring(1f)

    /** How far the live tint has spread along the cable from the socket, from 0 to 1. */
    var charge = 0f
        private set

    /** The ring that spreads from the socket when the plug seats, from 0 at the contact to 1. */
    var ring = 1f
        private set

    /** The shake of a plug whose session has just failed, from 0 at the failure to 1. */
    var shake = 1f
        private set

    val sent = LinkLane()
    val received = LinkLane()
    val lamps = List(LinkStageCount) { LinkLamp() }

    private var flow = 1f
    private var time = 0f
    private var seated = false
    private var mode: RipDpiLinkMode? = null
    private var complete = 0

    /** Where the plug rests: loose, at the latch, one contact in for each completed stage, or home. */
    fun target(input: LinkInput): Float =
        input.drag ?: when (input.mode) {
            RipDpiLinkMode.Open -> {
                LinkGap - LinkLean * input.press
            }

            RipDpiLinkMode.Held -> {
                LinkLatch
            }

            RipDpiLinkMode.Seeking -> {
                LinkDetent * (LinkStageCount - input.stages.completed()) / LinkStageCount
            }

            // A failed plug stops at the socket. The lamp names the stage: a few dp of travel cannot.
            RipDpiLinkMode.Broken -> {
                LinkDetent
            }

            RipDpiLinkMode.Live, RipDpiLinkMode.Unstable -> {
                0f
            }
        }

    /** How far the plug is out, with the short travel into the socket that a seat takes. */
    val out: Float get() = pull.at.coerceAtLeast(-LinkOvershoot)

    /** How loose the plug is, from 0 to 1. It lines up with the socket before it enters. */
    val loose: Float get() = smooth(((out / LinkGap).coerceIn(0f, 1f) - LinkAlignAt) / (1f - LinkAlignAt))

    /** The sideways offset of the shake. */
    val shakeBy: Float get() = LinkShake * sin(shake * LinkTau * LinkShakeTurns) * (1f - shake)

    fun advance(
        seconds: Float,
        input: LinkInput,
    ) {
        enter(input)
        movePlug(seconds, input)

        val nowSeated = out < LinkSeatedWithin
        val byItself = input.mode != RipDpiLinkMode.Open && input.drag == null
        if (nowSeated && !seated && byItself) {
            ring = 0f
            tap()
        }
        seated = nowSeated
        val done = input.stages.completed()
        if (input.mode == RipDpiLinkMode.Seeking && done > complete) tap()
        complete = done
        ring = (ring + seconds / emphasized).coerceAtMost(1f)
        shake = (shake + seconds / emphasized).coerceAtMost(1f)
        // A seated live plug lights the cable from the socket to its ends. The light goes back faster than it came.
        val lit = input.mode.live() && seated
        val spread = seconds / if (lit) emphasized * ChargeBuckets else state
        charge = if (lit) (charge + spread).coerceAtMost(1f) else (charge - spread).coerceAtLeast(0f)

        // On a failure or a pause the packets slow to a stop; otherwise the ones in the cable run out to its ends.
        val stops = input.mode == RipDpiLinkMode.Broken || input.mode == RipDpiLinkMode.Held
        flow = ease(flow, if (stops) 0f else 1f, seconds, state / EaseShare)
        advance(sent, input.sent, seconds, input, probes = true)
        advance(received, input.received, seconds, input, probes = false)
        // Indexed, so a frame allocates no iterator.
        for (index in lamps.indices) lamps[index].advance(index, input.stages[index], seconds)
    }

    /** Moves the plug toward where it rests, and with it the socket, the latch, and the cable. */
    private fun movePlug(
        seconds: Float,
        input: LinkInput,
    ) {
        time += seconds
        // A plug that waits for its session presses on the socket and lets go, slowly.
        val hunts = input.mode == RipDpiLinkMode.Seeking && input.drag == null
        val want = target(input) + if (hunts) LinkHunt * sin(time / HuntPeriod * LinkTau) else 0f
        if (input.drag != null) {
            // A plug that a finger lets go keeps the speed of the finger.
            val rate = if (seconds > 0f) (want - pull.at) / seconds else 0f
            pull.snap(want)
            pull.rate = rate.coerceIn(-ThrowMost, ThrowMost)
        } else {
            // The plug overshoots a little on the way in and does not on the way out.
            pull.step(want, PlugStiffness, if (want < pull.at) SeatDamping else PullDamping, seconds)
        }
        // On the way in the plug lines up exactly. On the way out it falls, and bounces once.
        val falls = input.drag == null && pull.rate > 0f
        if (falls) lie.step(loose, LieStiffness, LieDamping, seconds) else lie.snap(loose)
        if (falls) tilt.step(loose, TiltStiffness, TiltDamping, seconds) else tilt.snap(loose)
        recoil.step(0f, RecoilStiffness, RecoilDamping, seconds)
        click.step(0f, ClickStiffness, ClickDamping, seconds)
        rope.step(seconds, pose(input))
    }

    /** The still frame of a state: nothing in flight, packets at their spacing, lamps steady. */
    fun settle(input: LinkInput) {
        mode = input.mode
        pull.snap(target(input))
        recoil.snap(0f)
        click.snap(0f)
        lie.snap(loose)
        tilt.snap(loose)
        rope.settle(pose(input))
        charge = if (input.mode.live()) 1f else 0f
        seated = out < LinkSeatedWithin
        complete = input.stages.completed()
        ring = 1f
        shake = 1f
        flow = 1f
        sent.still(input.sent, input)
        received.still(input.received, input)
        lamps.forEachIndexed { index, lamp ->
            val stage = input.stages[index]
            lamp.stage = stage
            lamp.next = stage
            lamp.before = stage
            lamp.mix = 1f
            lamp.wait = 0f
            lamp.size.snap(1f)
            lamp.glow = stage.glow(breath = 1f)
            lamp.notch = stage.notch()
            lamp.turn = stage.turn()
        }
    }

    /**
     * How long no frame can change the picture, in seconds: a live link at rest with an empty cable waits for its
     * next packet. Zero when a frame is due. The wait is at most one traffic sample long.
     */
    fun quiet(input: LinkInput): Float {
        val calm = input.mode == RipDpiLinkMode.Live && input.drag == null && ring >= 1f && charge >= 1f
        val empty = sent.pulses.isEmpty() && received.pulses.isEmpty()
        val still = partsRest(input) && lampsRest(input)
        if (!(calm && empty && still)) return 0f
        return (minOf(sent.until, received.until) / LinkSpeed).coerceIn(0f, QuietLimit)
    }

    /** Lets [seconds] pass in which nothing moved, after a wait of [quiet]. */
    fun skip(seconds: Float) {
        sent.until -= LinkSpeed * seconds
        received.until -= LinkSpeed * seconds
        sent.wait -= seconds
        received.wait -= seconds
    }

    /** True while a frame can change the picture. A settled tile with no session takes no frames. */
    fun moving(input: LinkInput): Boolean {
        // A press moves the target of a loose plug, so the plug is not at rest while the press changes.
        val pressed = input.mode == RipDpiLinkMode.Open && input.press > Rest
        val active = input.mode == RipDpiLinkMode.Seeking || input.mode.live() || input.drag != null || pressed
        val effects = ring < 1f || shake < 1f || charge > 0f
        return active || !partsRest(input) || !lampsRest(input) || !lanesEmpty() || effects
    }

    /**
     * True while only the packets and the lamps move: the plug, the socket, and the cable are at rest and no
     * finger holds the plug. A steady figure does not need more frames than 60 a second.
     */
    fun steady(input: LinkInput): Boolean = input.drag == null && partsRest(input)

    private fun partsRest(input: LinkInput): Boolean {
        val plug = abs(pull.rate) < Rest && abs(pull.at - target(input)) < Rest
        val socket = abs(recoil.at) < Rest && abs(click.at) < Rest
        val lies = abs(lie.rate) < Rest && abs(lie.at - loose) < Rest
        val turned = abs(tilt.rate) < Rest && abs(tilt.at - loose) < Rest
        return plug && socket && lies && turned && rope.speed < RopeRest
    }

    private fun lampsRest(input: LinkInput): Boolean {
        var rests = true
        for (index in lamps.indices) {
            val stage = input.stages[index]
            val lamp = lamps[index]
            val shape = abs(lamp.notch - stage.notch()) < Rest && abs(lamp.size.at - 1f) < Rest
            val shown = lamp.stage == stage && lamp.mix >= 1f && abs(lamp.size.rate) < Rest
            rests = rests && shape && shown && abs(lamp.glow - stage.glow(1f)) < Rest
        }
        return rests
    }

    private fun lanesEmpty(): Boolean =
        (sent.pulses.isEmpty() && received.pulses.isEmpty()) || maxOf(sent.alpha, received.alpha) < Rest

    private fun enter(input: LinkInput) {
        val before = mode
        if (before == input.mode) return
        mode = input.mode
        if (input.mode == RipDpiLinkMode.Broken) {
            // The socket refuses the plug: it shakes, and it is pushed back a little.
            shake = 0f
            pull.rate += KickSpeed
        }
        if (input.mode == RipDpiLinkMode.Held) click.rate = ClickSpeed
        if (input.mode.live() && before?.live() != true) {
            // The sent lane starts first and the received lane after it, as a request and its reply.
            for (lane in listOf(sent, received)) {
                lane.pulses.clear()
                lane.alpha = 0f
                lane.until = 0f
            }
            sent.wait = 0f
            received.wait = quick
            flow = 1f
        }
    }

    // A contact makes: the socket takes the hit and the latch clicks. A faster plug hits harder.
    private fun tap() {
        val force = (abs(pull.rate) / TapRate).coerceIn(TapLeast, TapMost)
        recoil.rate = -TapSpeed * force
        click.rate = ClickSpeed * force
    }

    /** Where the cable leaves the plug and where it ends, for the plug as it is now. */
    private fun pose(input: LinkInput): LinkRopeEnds {
        val radians = tilt.at * LooseTurn * PI.toFloat() / HalfTurn
        val reach = LeadStart - PlugPivot
        ends.alongX = cos(radians)
        ends.alongY = sin(radians)
        ends.fromX = PlugPivot + reach * ends.alongX + out + shakeBy
        ends.fromY = CableY + reach * ends.alongY + lie.at * LooseDrop
        ends.toX = LeadStart - input.probeFrom + input.length
        ends.toY = CableY
        // A loose plug is nearer the edge of the tile, so more of the cable hangs.
        ends.stretch = 1f + RopeTaut + RopeLoose * (out / LinkGap).coerceIn(0f, 1f)
        return ends
    }

    private fun free(
        lane: LinkLane,
        input: LinkInput,
    ): Boolean = lane.pulses.isEmpty() && abs(pull.at - target(input)) < RestsWithin

    private fun advance(
        lane: LinkLane,
        traffic: Float,
        seconds: Float,
        input: LinkInput,
        probes: Boolean,
    ) {
        val live = input.mode.live()
        lane.level = ease(lane.level, if (live) traffic else 0f, seconds, state)
        val step = LinkSpeed * flow * seconds
        for (index in lane.pulses.indices) lane.pulses[index].at += step
        lane.drop(input, live, probes)
        if (live) {
            lane.wait -= seconds
            lane.until -= step
            if (lane.wait <= 0f && lane.until <= 0f) emit(lane)
        } else if (probes && input.mode == RipDpiLinkMode.Seeking && free(lane, input)) {
            // One probe at a time leaves the plug, once the plug is at the socket.
            lane.pulses += LinkPulse(input.probeFrom)
        }
        val shown =
            when {
                live -> if (lane.wait <= 0f) 1f else lane.alpha
                input.mode == RipDpiLinkMode.Seeking && probes -> 1f
                input.mode == RipDpiLinkMode.Broken || input.mode == RipDpiLinkMode.Held -> 0f
                else -> lane.alpha
            }
        lane.alpha = ease(lane.alpha, shown, seconds, state / EaseShare)
        if (shown == 0f && lane.alpha < Rest) lane.pulses.clear()
    }

    /** Traffic comes in bursts: a few packets close together, then a gap. A busy lane has longer packets. */
    private fun emit(lane: LinkLane) {
        val level = lane.level.coerceIn(0f, 1f)
        val length = PulseShort + (PulseLong - PulseShort) * level * random.nextFloat()
        lane.pulses += LinkPulse(0f, length)
        if (lane.burst > 0) {
            lane.burst--
            lane.until = length + BurstGap
        } else {
            lane.burst = (level * BurstMost * random.nextFloat()).toInt()
            // A keep-alive packet keeps its interval, so the cable is empty between two of them.
            val jitter = if (level > 0f) JitterFrom + JitterSpan * random.nextFloat() else 1f
            lane.until = length + spacing(level) * jitter
        }
    }

    private fun LinkLamp.advance(
        index: Int,
        wanted: RipDpiLinkStage,
        seconds: Float,
    ) {
        follow(index, wanted, seconds)
        phase += seconds
        mix = (mix + seconds / quick).coerceAtMost(1f)
        size.step(1f, PopStiffness, PopDamping, seconds)
        // A lamp breathes slowly while its stage comes up or has a warning. It never flashes fast. The lamps of
        // stages that come up breathe one after the other, so the light travels along the socket.
        val active = stage == RipDpiLinkStage.Active
        val cycle = phase / (if (active) ActivePeriod else WarningPeriod) - if (active) index * ChaseLag else 0f
        glow = ease(glow, stage.glow(breath = 0.5f - 0.5f * cos(cycle * LinkTau)), seconds, quick / 2f)
        notch = ease(notch, stage.notch(), seconds, state / EaseShare)
        turn = ease(turn, stage.turn(), seconds, state / EaseShare)
    }

    /** Lamps change one after the other: they come on from the device end, and go off from the network end. */
    private fun LinkLamp.follow(
        index: Int,
        wanted: RipDpiLinkStage,
        seconds: Float,
    ) {
        if (wanted != next) {
            next = wanted
            wait = LampStagger * if (wanted == RipDpiLinkStage.Pending) LinkStageCount - 1 - index else index
        }
        if (wanted == stage) return
        wait -= seconds
        if (wait > 0f) return
        before = stage
        stage = wanted
        mix = 0f
        if (wanted != RipDpiLinkStage.Pending) size.rate = PopSpeed
    }
}

/**
 * True when a packet is at the end of its way. That is the far end of the cable. With no live session it is also
 * the connector, which a packet does not cross: it ends where the socket or the plug hides it. [forward] is true
 * for a sent packet, which comes to the socket first.
 */
private fun LinkPulse.ends(
    input: LinkInput,
    live: Boolean,
    forward: Boolean,
): Boolean {
    val connector = LeadStart - DeviceEnd
    val plug = if (forward) input.probeFrom else input.length - input.probeFrom
    val hidden = (if (forward) plug - connector else plug) - CableCover + length
    val blocked = !live && at >= hidden && at < if (forward) plug else plug + connector
    return blocked || at >= input.length + length
}

/** Takes out the packets that are at the end of their way. */
private fun LinkLane.drop(
    input: LinkInput,
    live: Boolean,
    forward: Boolean,
) {
    // Indexed from the end, so a frame allocates no predicate and a removal skips no packet.
    for (index in pulses.lastIndex downTo 0) {
        if (pulses[index].ends(input, live, forward)) pulses.removeAt(index)
    }
}

/** The still frame of a lane: packets at the spacing of its traffic. */
private fun LinkLane.still(
    traffic: Float,
    input: LinkInput,
) {
    pulses.clear()
    alpha = 1f
    burst = 0
    // A still frame shows the traffic in a few steps, so it does not shift with each small change.
    level = if (input.mode.live()) ceil(traffic.coerceIn(0f, 1f) * StillSteps) / StillSteps else 0f
    if (!input.mode.live()) return
    val gap = LinkPulseLength + spacing(level)
    var at = gap / 2f
    while (at < input.length) {
        pulses += LinkPulse(at)
        at += gap
    }
}

private fun RipDpiLinkMode.live(): Boolean = this == RipDpiLinkMode.Live || this == RipDpiLinkMode.Unstable

/** How many stages are complete. Indexed, so a frame allocates no iterator. */
private fun List<RipDpiLinkStage>.completed(): Int {
    var done = 0
    for (index in indices) if (this[index] == RipDpiLinkStage.Complete) done++
    return done
}

/** How bright the lamp of a stage is. [breath] is where a breathing lamp is in its cycle, from 0 to 1. */
private fun RipDpiLinkStage.glow(breath: Float): Float =
    when (this) {
        RipDpiLinkStage.Pending, RipDpiLinkStage.Complete, RipDpiLinkStage.Failed -> 1f
        RipDpiLinkStage.Held -> HeldGlow
        RipDpiLinkStage.Active -> ActiveLow + (1f - ActiveLow) * breath
        RipDpiLinkStage.Warning -> WarningLow + (1f - WarningLow) * breath
    }

private fun RipDpiLinkStage.notch(): Float =
    when (this) {
        RipDpiLinkStage.Warning -> WarningNotch
        RipDpiLinkStage.Failed -> FailedNotch
        else -> 0f
    }

private fun RipDpiLinkStage.turn(): Float = if (this == RipDpiLinkStage.Failed) PI.toFloat() else 0f

/** The space between packets, in dp. With no traffic one keep-alive packet passes at a long interval. */
private fun spacing(level: Float): Float {
    val seconds = if (level <= 0f) KeepAliveGap else QuietGap - (QuietGap - BusyGap) * level.coerceAtMost(1f)
    return LinkSpeed * seconds
}

private fun ease(
    value: Float,
    target: Float,
    seconds: Float,
    time: Float,
): Float = value + (target - value) * (1f - exp(-seconds / time))

private fun smooth(t: Float): Float {
    val u = t.coerceIn(0f, 1f)
    return u * u * (SmoothA - SmoothB * u)
}

internal const val LinkStageCount = 5

// The plug, in dp: loose, at the latch, the travel of the five contacts, and the lean under a press.
internal const val LinkGap = 34f
internal const val LinkLatch = 18f
internal const val LinkDetent = 15f
internal const val LinkLean = 5f
internal const val LinkOvershoot = 1.5f
internal const val LinkPulseLength = 9f
internal const val LinkDefaultLength = 396f
internal const val LinkDefaultProbeFrom = 226f
private const val LinkSeatedWithin = 0.6f
private const val LinkAlignAt = 0.4f
private const val LinkShake = 2f
private const val LinkShakeTurns = 2f

// The pose of the plug, in dp of the design: the line of the cable, the point that the plug turns about, where
// the cable leaves the plug, and how far a loose plug drops and turns.
internal const val CableY = 42f
internal const val PlugPivot = 256f
internal const val LeadStart = 192f
internal const val DeviceEnd = 78f
internal const val CableCover = 3f
internal const val LooseDrop = 5f
internal const val LooseTurn = 5f
internal const val HalfTurn = 180f
private const val RopeTaut = 0.001f
private const val RopeLoose = 0.035f
private const val RopeRest = 1f
private const val LinkSeed = 7
private const val LinkHunt = 0.6f
private const val HuntPeriod = 1.6f

private const val MillisPerSecond = 1_000f
private const val RestsWithin = 1f
private const val QuietLimit = 1f
private const val StillSteps = 4f

// An eased value comes within 5% of its target in one bucket.
private const val EaseShare = 3f

private const val SpringSteps = 4
private const val PlugStiffness = 320f
private const val SeatDamping = 24f
private const val PullDamping = 36f
private const val RecoilStiffness = 900f
private const val RecoilDamping = 34f
private const val ClickStiffness = 1200f
private const val ClickDamping = 30f
private const val TiltStiffness = 170f
private const val TiltDamping = 8f
private const val ThrowMost = 300f
private const val TapRate = 80f
private const val TapLeast = 0.6f
private const val TapMost = 1.6f
private const val LieStiffness = 300f
private const val LieDamping = 14f
private const val KickSpeed = 140f
private const val ChargeBuckets = 1.5f
private const val PopStiffness = 700f
private const val PopDamping = 22f
private const val PopSpeed = 9f
private const val LampStagger = 0.06f
private const val ChaseLag = 0.18f
private const val TapSpeed = 26f
private const val ClickSpeed = 30f
private const val Rest = 0.02f

// The packets: speed in dp a second, gaps in seconds.
private const val LinkSpeed = 110f
private const val KeepAliveGap = 4f
private const val QuietGap = 0.9f
private const val BusyGap = 0.16f
private const val JitterFrom = 0.7f
private const val JitterSpan = 0.6f
private const val PulseShort = 5f
private const val PulseLong = 14f
private const val BurstGap = 3f
private const val BurstMost = 3.5f

private const val HeldGlow = 0.45f
private const val ActiveLow = 0.35f
private const val WarningLow = 0.7f
private const val ActivePeriod = 1.1f
private const val WarningPeriod = 2f
private const val WarningNotch = 0.24f
private const val FailedNotch = 0.34f
internal const val LinkTau = (2.0 * PI).toFloat()

// The cubic of a smooth step: 3t^2 - 2t^3.
private const val SmoothA = 3f
private const val SmoothB = 2f
