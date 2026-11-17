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
)

/** A lane of the cable: sent packets travel away from the device, received packets toward it. */
internal class LinkLane {
    val pulses = ArrayList<LinkPulse>()

    /** How visible the lane is, from 0 to 1. */
    var alpha = 0f
    var level = 0f
    var until = 0f
    var wait = 0f
}

/** One lamp of the socket. A warning gives it four points, and a failure turns the points to a cross. */
internal class LinkLamp {
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

    /** The slack of the cable behind the plug. */
    val sag = LinkSpring(LinkSlack)

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
                LinkDetent * (LinkStageCount - input.stages.count { it == RipDpiLinkStage.Complete }) / LinkStageCount
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
        val want = target(input)
        if (input.drag != null) {
            pull.snap(want)
        } else {
            // The plug overshoots a little on the way in and does not on the way out.
            pull.step(want, PlugStiffness, if (want < pull.at) SeatDamping else PullDamping, seconds)
        }
        recoil.step(0f, RecoilStiffness, RecoilDamping, seconds)
        click.step(0f, ClickStiffness, ClickDamping, seconds)
        // The cable swings when the plug moves.
        sag.rate += pull.rate * SagCoupling * seconds
        sag.step(slack(), SagStiffness, SagDamping, seconds)

        val nowSeated = out < LinkSeatedWithin
        val byItself = input.mode != RipDpiLinkMode.Open && input.drag == null
        if (nowSeated && !seated && byItself) {
            ring = 0f
            tap()
        }
        seated = nowSeated
        val done = input.stages.count { it == RipDpiLinkStage.Complete }
        if (input.mode == RipDpiLinkMode.Seeking && done > complete) tap()
        complete = done
        ring = (ring + seconds / emphasized).coerceAtMost(1f)
        shake = (shake + seconds / emphasized).coerceAtMost(1f)

        // On a failure or a pause the packets slow to a stop; otherwise the ones in the cable run out to its ends.
        val stops = input.mode == RipDpiLinkMode.Broken || input.mode == RipDpiLinkMode.Held
        flow = ease(flow, if (stops) 0f else 1f, seconds, state / EaseShare)
        advance(sent, input.sent, seconds, input, probes = true)
        advance(received, input.received, seconds, input, probes = false)
        // Indexed, so a frame allocates no iterator.
        for (index in lamps.indices) lamps[index].advance(input.stages[index], seconds)
    }

    /** The still frame of a state: nothing in flight, packets at their spacing, lamps steady. */
    fun settle(input: LinkInput) {
        mode = input.mode
        pull.snap(target(input))
        recoil.snap(0f)
        click.snap(0f)
        sag.snap(slack())
        seated = out < LinkSeatedWithin
        complete = input.stages.count { it == RipDpiLinkStage.Complete }
        ring = 1f
        shake = 1f
        flow = 1f
        still(sent, input.sent, input)
        still(received, input.received, input)
        lamps.forEachIndexed { index, lamp ->
            val stage = input.stages[index]
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
        val calm = input.mode == RipDpiLinkMode.Live && input.drag == null && ring >= 1f
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
        return active || !partsRest(input) || !lampsRest(input) || !lanesEmpty() || ring < 1f || shake < 1f
    }

    private fun partsRest(input: LinkInput): Boolean {
        val plug = abs(pull.rate) < Rest && abs(pull.at - target(input)) < Rest
        val socket = abs(recoil.at) < Rest && abs(click.at) < Rest
        return plug && socket && abs(sag.rate) < SagRest && abs(sag.at - slack()) < SagRest
    }

    private fun lampsRest(input: LinkInput): Boolean {
        var rests = true
        for (index in lamps.indices) {
            val stage = input.stages[index]
            val shape = abs(lamps[index].notch - stage.notch()) < Rest
            rests = rests && shape && abs(lamps[index].glow - stage.glow(1f)) < Rest
        }
        return rests
    }

    private fun lanesEmpty(): Boolean =
        (sent.pulses.isEmpty() && received.pulses.isEmpty()) || maxOf(sent.alpha, received.alpha) < Rest

    private fun enter(input: LinkInput) {
        val before = mode
        if (before == input.mode) return
        mode = input.mode
        if (input.mode == RipDpiLinkMode.Broken) shake = 0f
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

    // A contact makes: the socket takes the hit and the latch clicks.
    private fun tap() {
        recoil.rate = -TapSpeed
        click.rate = ClickSpeed
    }

    private fun free(
        lane: LinkLane,
        input: LinkInput,
    ): Boolean = lane.pulses.isEmpty() && abs(pull.at - target(input)) < RestsWithin

    private fun slack(): Float = LinkSlack + LinkSlackLoose * (out / LinkGap).coerceIn(0f, 1f)

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
        lane.pulses.removeAll { it.at >= input.length + LinkPulseLength }
        if (live) {
            lane.wait -= seconds
            lane.until -= step
            if (lane.wait <= 0f && lane.until <= 0f) {
                lane.pulses += LinkPulse(0f)
                lane.until = LinkPulseLength + spacing(lane.level) * (JitterFrom + JitterSpan * random.nextFloat())
            }
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

    private fun still(
        lane: LinkLane,
        traffic: Float,
        input: LinkInput,
    ) {
        lane.pulses.clear()
        lane.alpha = 1f
        // A still frame shows the traffic in a few steps, so it does not shift with each small change.
        lane.level = if (input.mode.live()) ceil(traffic.coerceIn(0f, 1f) * StillSteps) / StillSteps else 0f
        if (!input.mode.live()) return
        val gap = LinkPulseLength + spacing(lane.level)
        var at = gap / 2f
        while (at < input.length) {
            lane.pulses += LinkPulse(at)
            at += gap
        }
    }

    private fun LinkLamp.advance(
        stage: RipDpiLinkStage,
        seconds: Float,
    ) {
        phase += seconds
        // A lamp breathes slowly while its stage comes up or has a warning. It never flashes fast.
        val period = if (stage == RipDpiLinkStage.Active) ActivePeriod else WarningPeriod
        glow = ease(glow, stage.glow(breath = 0.5f - 0.5f * cos(phase / period * LinkTau)), seconds, quick / 2f)
        notch = ease(notch, stage.notch(), seconds, state / EaseShare)
        turn = ease(turn, stage.turn(), seconds, state / EaseShare)
    }
}

private fun RipDpiLinkMode.live(): Boolean = this == RipDpiLinkMode.Live || this == RipDpiLinkMode.Unstable

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
private const val LinkSlack = 3f
private const val LinkSlackLoose = 15f
private const val LinkSeed = 7

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
private const val SagStiffness = 90f
private const val SagDamping = 7f
private const val SagCoupling = 3f
private const val TapSpeed = 26f
private const val ClickSpeed = 30f
private const val Rest = 0.02f
private const val SagRest = 0.2f

// The packets: speed in dp a second, gaps in seconds.
private const val LinkSpeed = 110f
private const val KeepAliveGap = 4f
private const val QuietGap = 0.9f
private const val BusyGap = 0.16f
private const val JitterFrom = 0.7f
private const val JitterSpan = 0.6f

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
