package com.poyka.ripdpi.ui.components.inputs

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.status.PassageFamily
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StrategyPassageScene
import com.poyka.ripdpi.ui.screens.status.durationMillis
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal const val PassageAtRest = Float.MAX_VALUE

/**
 * What the panel remembers about playback. It outlives the scene, which leaves composition while
 * saved edits wait for a reconnect, so a scene that returns neither repeats a tap nor misses a change.
 * Leaving the screen discards it: the next opening is a new baseline and stays at rest.
 */
internal class PassagePlaybackMemory {
    var replayRequests by mutableIntStateOf(0)
    var handledReplays = 0
    var seenKey: Pair<StatusConnectionActuatorStatus, StrategyPassageScene>? = null
}

/**
 * Plays one flight when the session locks, the strategy changes, or an expandable panel is tapped, and loops up to
 * the DPI line while connecting. Reduced motion stays at rest.
 */
@Composable
internal fun rememberPassagePlayback(
    scene: StrategyPassageScene,
    status: StatusConnectionActuatorStatus,
    memory: PassagePlaybackMemory,
    motion: RipDpiMotion,
): Animatable<Float, *> {
    val clock = remember { Animatable(PassageAtRest) }
    val canPlay = motion.allowsInfiniteMotion && !LocalInspectionMode.current
    val duration = scene.durationMillis
    val flight = motion.durationTween<Float>(duration, LinearEasing)
    val key = status to scene
    val locked = status == StatusConnectionActuatorStatus.Locked
    LaunchedEffect(key, canPlay) {
        val changed = memory.seenKey != null && memory.seenKey != key
        memory.seenKey = key
        when {
            canPlay && status == StatusConnectionActuatorStatus.Engaging -> {
                clock.snapTo(0f)
                clock.animateTo(duration.toFloat(), infiniteRepeatable(flight, RepeatMode.Restart))
            }

            canPlay && locked && changed -> {
                clock.snapTo(0f)
                clock.animateTo(duration.toFloat(), flight)
            }

            else -> {
                clock.snapTo(PassageAtRest)
            }
        }
    }
    LaunchedEffect(memory.replayRequests) {
        val requested = memory.replayRequests != memory.handledReplays
        memory.handledReplays = memory.replayRequests
        if (requested && canPlay && locked) {
            clock.snapTo(0f)
            clock.animateTo(duration.toFloat(), flight)
        }
    }
    return clock
}

/**
 * The plan of the configured chain as a scheme. It draws the settings only: the packet at the server appears
 * for a locked session and never proves a bypass.
 */
@Composable
internal fun RipDpiStrategyPassage(
    scene: StrategyPassageScene,
    status: StatusConnectionActuatorStatus,
    playback: PassagePlaybackMemory,
    surface: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val tokens = RipDpiThemeTokens.colors
    // The identity color marks a live or connecting path only, so the name is neutral without a session.
    val live = status != StatusConnectionActuatorStatus.Open && status != StatusConnectionActuatorStatus.Fault
    val colors =
        remember(surface, content, tokens, live) {
            SchemeColors(surface, content, if (live) tokens.signal else content, tokens.warning, tokens.destructive)
        }
    val clock = rememberPassagePlayback(scene, status, playback, RipDpiThemeTokens.motion)
    val description = strategyPassageDescription(scene)
    val degraded = status == StatusConnectionActuatorStatus.Degraded
    RipDpiStrategyScheme(
        scene = scene,
        colors = colors,
        frame = {
            SchemeFrame(
                // The clock stops one frame after the session ends, so a dead path is at rest at once.
                timeMillis = if (live) clock.value else PassageAtRest,
                engaging = status == StatusConnectionActuatorStatus.Engaging,
                connected = status == StatusConnectionActuatorStatus.Locked || degraded,
                unconfirmed = degraded,
            )
        },
        modifier = modifier.semantics { contentDescription = description },
    )
}

@StringRes
private fun PassageFamily.nameRes(): Int =
    when (this) {
        PassageFamily.Cut -> R.string.status_strategy_family_cut
        PassageFamily.LaggingPart -> R.string.status_strategy_family_lagging_part
        PassageFamily.Decoy -> R.string.status_strategy_family_decoy
        PassageFamily.HostMask -> R.string.status_strategy_family_host_mask
        PassageFamily.TlsRecords -> R.string.status_strategy_family_tls_records
        PassageFamily.UrgentByte -> R.string.status_strategy_family_urgent_byte
        PassageFamily.IpFragments -> R.string.status_strategy_family_ip_fragments
        PassageFamily.Overlap -> R.string.status_strategy_family_overlap
        PassageFamily.FakeReset -> R.string.status_strategy_family_fake_reset
        PassageFamily.QuicDecoyBurst -> R.string.status_strategy_family_quic_decoys
        PassageFamily.QuicSplitCopy -> R.string.status_strategy_family_quic_split_copy
        PassageFamily.QuicVersion -> R.string.status_strategy_family_quic_version
    }

@StringRes
private fun PassageFamily.descriptionRes(): Int =
    when (this) {
        PassageFamily.Cut -> R.string.status_strategy_family_cut_description
        PassageFamily.LaggingPart -> R.string.status_strategy_family_lagging_part_description
        PassageFamily.Decoy -> R.string.status_strategy_family_decoy_description
        PassageFamily.HostMask -> R.string.status_strategy_family_host_mask_description
        PassageFamily.TlsRecords -> R.string.status_strategy_family_tls_records_description
        PassageFamily.UrgentByte -> R.string.status_strategy_family_urgent_byte_description
        PassageFamily.IpFragments -> R.string.status_strategy_family_ip_fragments_description
        PassageFamily.Overlap -> R.string.status_strategy_family_overlap_description
        PassageFamily.FakeReset -> R.string.status_strategy_family_fake_reset_description
        PassageFamily.QuicDecoyBurst -> R.string.status_strategy_family_quic_decoys_description
        PassageFamily.QuicSplitCopy -> R.string.status_strategy_family_quic_split_copy_description
        PassageFamily.QuicVersion -> R.string.status_strategy_family_quic_version_description
    }

@Composable
internal fun strategyPassageName(scene: StrategyPassageScene): String =
    when (scene.families.size) {
        0 -> {
            stringResource(R.string.status_strategy_family_none)
        }

        1 -> {
            stringResource(scene.families[0].nameRes())
        }

        2 -> {
            stringResource(
                R.string.status_strategy_family_pair,
                stringResource(scene.families[0].nameRes()),
                stringResource(scene.families[1].nameRes()),
            )
        }

        else -> {
            stringResource(R.string.status_strategy_family_steps, scene.stepCount)
        }
    }

@Composable
private fun strategyPassageDescription(scene: StrategyPassageScene): String {
    // The visible panel has no "Bypass strategy" caption, so TalkBack keeps that name here.
    val title = stringResource(R.string.config_local_bypass_strategy_title)
    val details =
        if (scene.families.isEmpty()) {
            listOf(stringResource(R.string.status_strategy_family_none_description))
        } else {
            scene.families.map { stringResource(it.descriptionRes()) }
        }
    return stringResource(R.string.status_strategy_passage_description, title, details.joinToString(separator = " "))
}
