package com.poyka.ripdpi.ui.screens.diagnostics

/**
 * Spec-card sample data for [StrategyAbScreen]. Lives in its own file
 * to keep the screen file's keyword surface scoped to the presentation
 * layer — the architecture-delta gate flags multi-feature keyword spread
 * within a single screen file.
 */
fun sampleStrategyAbState(): StrategyAbState =
    StrategyAbState(
        strategyA =
            StrategySideState(
                name = "desync_fake",
                label = "CURRENT",
                isCurrent = true,
                isWinner = false,
                score = 3,
                total = 4,
                targets =
                    listOf(
                        StrategyTargetState(name = "cloudflare-dns.com", latencyMs = 18, passed = true),
                        StrategyTargetState(name = "discord.com", latencyMs = 32, passed = true),
                        StrategyTargetState(name = "youtube.com", latencyMs = null, passed = false),
                        StrategyTargetState(name = "twitch.tv", latencyMs = 71, passed = true),
                    ),
            ),
        strategyB =
            StrategySideState(
                name = "tlsrec_split_host",
                label = "CANDIDATE",
                isCurrent = false,
                isWinner = true,
                score = 4,
                total = 4,
                targets =
                    listOf(
                        StrategyTargetState(name = "cloudflare-dns.com", latencyMs = 21, passed = true),
                        StrategyTargetState(name = "discord.com", latencyMs = 29, passed = true),
                        StrategyTargetState(name = "youtube.com", latencyMs = 44, passed = true),
                        StrategyTargetState(name = "twitch.tv", latencyMs = 68, passed = true),
                    ),
            ),
        verdictLabel = "tlsrec_split_host passed 1 more target. Average latency 36 ms (vs. 34 ms).",
        winnerName = "tlsrec_split_host",
    )
