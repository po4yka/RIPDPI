package com.poyka.ripdpi.core.detection.ui

enum class DetectionColorVisionMode(
    val wireValue: String,
    val displayName: String,
) {
    OFF("off", "Standard"),
    RED_GREEN("red_green", "Red/green safe"),
    BLUE_YELLOW("blue_yellow", "Blue/yellow safe"),
    ACHROMATOPSIA("achromatopsia", "Achromatopsia"),
    ;

    companion object {
        fun fromWire(value: String?): DetectionColorVisionMode =
            when (value) {
                "protanomaly",
                "protanopia",
                "deuteranomaly",
                "deuteranopia",
                -> RED_GREEN

                "tritanomaly",
                "tritanopia",
                -> BLUE_YELLOW

                else -> entries.firstOrNull { it.wireValue == value } ?: OFF
            }
    }
}

enum class DetectionVisualState(
    val displayName: String,
) {
    CLEAN("Clean"),
    REVIEW("Review"),
    DETECTED("Detected"),
    ERROR("Error"),
    NEUTRAL("Neutral"),
}

enum class StatusShapeVariant {
    CIRCLE,
    TRIANGLE,
    DIAMOND,
    SQUARE,
    LINE,
}

data class StatusVisual(
    val state: DetectionVisualState,
    val mode: DetectionColorVisionMode,
    val colorHex: String,
    val shape: StatusShapeVariant,
    val label: String = state.displayName,
)

object StatusVisualResolver {
    fun resolve(
        state: DetectionVisualState,
        mode: DetectionColorVisionMode,
    ): StatusVisual =
        StatusVisual(
            state = state,
            mode = mode,
            colorHex = colorHex(state, mode),
            shape = shape(state),
        )

    private fun shape(state: DetectionVisualState): StatusShapeVariant =
        when (state) {
            DetectionVisualState.CLEAN -> StatusShapeVariant.CIRCLE
            DetectionVisualState.REVIEW -> StatusShapeVariant.TRIANGLE
            DetectionVisualState.DETECTED -> StatusShapeVariant.DIAMOND
            DetectionVisualState.ERROR -> StatusShapeVariant.SQUARE
            DetectionVisualState.NEUTRAL -> StatusShapeVariant.LINE
        }

    private fun colorHex(
        state: DetectionVisualState,
        mode: DetectionColorVisionMode,
    ): String =
        when (mode) {
            DetectionColorVisionMode.OFF -> standardColor(state)
            DetectionColorVisionMode.RED_GREEN -> redGreenSafeColor(state)
            DetectionColorVisionMode.BLUE_YELLOW -> tritanSafeColor(state)
            DetectionColorVisionMode.ACHROMATOPSIA -> achromatopsiaColor(state)
        }

    private fun standardColor(state: DetectionVisualState): String =
        when (state) {
            DetectionVisualState.CLEAN -> "#047857"
            DetectionVisualState.REVIEW -> "#B45309"
            DetectionVisualState.DETECTED -> "#B91C1C"
            DetectionVisualState.ERROR -> "#7F1D1D"
            DetectionVisualState.NEUTRAL -> "#6B7280"
        }

    private fun redGreenSafeColor(state: DetectionVisualState): String =
        when (state) {
            DetectionVisualState.CLEAN -> "#0072B2"
            DetectionVisualState.REVIEW -> "#E69F00"
            DetectionVisualState.DETECTED -> "#D55E00"
            DetectionVisualState.ERROR -> "#CC79A7"
            DetectionVisualState.NEUTRAL -> "#6B7280"
        }

    private fun tritanSafeColor(state: DetectionVisualState): String =
        when (state) {
            DetectionVisualState.CLEAN -> "#009E73"
            DetectionVisualState.REVIEW -> "#CC79A7"
            DetectionVisualState.DETECTED -> "#D55E00"
            DetectionVisualState.ERROR -> "#000000"
            DetectionVisualState.NEUTRAL -> "#6B7280"
        }

    private fun achromatopsiaColor(state: DetectionVisualState): String =
        when (state) {
            DetectionVisualState.CLEAN -> "#111827"
            DetectionVisualState.REVIEW -> "#4B5563"
            DetectionVisualState.DETECTED -> "#000000"
            DetectionVisualState.ERROR -> "#374151"
            DetectionVisualState.NEUTRAL -> "#9CA3AF"
        }
}
