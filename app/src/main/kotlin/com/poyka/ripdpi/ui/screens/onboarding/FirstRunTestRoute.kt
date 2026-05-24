package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.persistentListOf

@Composable
fun FirstRunTestRoute(
    onSkip: () -> Unit,
    onApplyRecommendation: () -> Unit,
) {
    val targets =
        remember {
            persistentListOf(
                FirstRunTarget(
                    name = "cloudflare-dns.com",
                    status = FirstRunTargetStatus.Ok,
                    detail = "OK · 12 ms",
                ),
                FirstRunTarget(
                    name = "discord.com",
                    status = FirstRunTargetStatus.Warn,
                    detail = "QUIC blocked",
                ),
                FirstRunTarget(
                    name = "youtube.com",
                    status = FirstRunTargetStatus.Running,
                    detail = "checking…",
                ),
                FirstRunTarget(
                    name = "github.com",
                    status = FirstRunTargetStatus.Queued,
                    detail = "",
                ),
                FirstRunTarget(
                    name = "raw.githubusercontent.com",
                    status = FirstRunTargetStatus.Queued,
                    detail = "",
                ),
            )
        }
    FirstRunTestScreen(
        targets = targets,
        onSkip = onSkip,
        onApplyRecommendation = onApplyRecommendation,
        applyEnabled = false,
    )
}
