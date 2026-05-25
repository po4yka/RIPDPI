package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R

/**
 * Spec-card sample data for [ProfileVariantsScreen]. Lives in its own file
 * to keep the screen file's keyword surface scoped to the presentation
 * layer — the architecture-delta gate flags multi-feature keyword spread
 * within a single screen file.
 */
fun sampleProfileVariantsState(): ProfileVariantsState =
    ProfileVariantsState(
        variants =
            listOf(
                ProfileVariantCardState(
                    tone = ProfileVariantTone.Balanced,
                    chipLabelRes = R.string.profile_variants_chip_balanced,
                    nameRes = R.string.profile_variants_name_balanced,
                    descriptionRes = R.string.profile_variants_desc_balanced,
                    metrics =
                        listOf(
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_latency,
                                value = "12 ms",
                                isPositive = true,
                            ),
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_throughput,
                                value = "94 Mbps",
                                isPositive = true,
                            ),
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_detection,
                                value = "LOW",
                                isPositive = true,
                            ),
                        ),
                    isSelected = true,
                    onSelect = {},
                ),
                ProfileVariantCardState(
                    tone = ProfileVariantTone.Aggressive,
                    chipLabelRes = R.string.profile_variants_chip_aggressive,
                    nameRes = R.string.profile_variants_name_aggressive,
                    descriptionRes = R.string.profile_variants_desc_aggressive,
                    metrics =
                        listOf(
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_latency,
                                value = "8 ms",
                                isPositive = true,
                            ),
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_throughput,
                                value = "140 Mbps",
                                isPositive = true,
                            ),
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_detection,
                                value = "MED",
                                isPositive = false,
                            ),
                        ),
                    isSelected = false,
                    onSelect = {},
                ),
                ProfileVariantCardState(
                    tone = ProfileVariantTone.Stealth,
                    chipLabelRes = R.string.profile_variants_chip_stealth,
                    nameRes = R.string.profile_variants_name_stealth,
                    descriptionRes = R.string.profile_variants_desc_stealth,
                    metrics =
                        listOf(
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_latency,
                                value = "28 ms",
                                isPositive = false,
                            ),
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_throughput,
                                value = "61 Mbps",
                                isPositive = false,
                            ),
                            ProfileVariantMetric(
                                labelRes = R.string.profile_variants_metric_detection,
                                value = "VERY LOW",
                                isPositive = true,
                            ),
                        ),
                    isSelected = false,
                    onSelect = {},
                ),
            ),
    )
