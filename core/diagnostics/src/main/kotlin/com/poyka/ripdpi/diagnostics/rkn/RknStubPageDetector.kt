package com.poyka.ripdpi.diagnostics.rkn

import android.content.Context
import java.io.File
import java.util.Locale

enum class StubDetectionConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

data class StubDetection(
    val isStub: Boolean,
    val confidence: StubDetectionConfidence,
    val matchedMarker: String?,
    val via451: Boolean,
)

class DpiAssetLoader(
    private val context: Context,
) {
    private var cachedStubMarkers: List<String>? = null

    fun loadStubMarkers(): List<String> = cachedStubMarkers ?: loadStubMarkersUncached().also { cachedStubMarkers = it }

    private fun loadStubMarkersUncached(): List<String> {
        val overrideFile = File(context.filesDir, "rkn/stub_markers.txt")
        if (overrideFile.isFile) {
            return overrideFile.readText().parseMarkers()
        }
        return runCatching {
            context.assets
                .open(StubMarkersAssetPath)
                .bufferedReader()
                .use { it.readText() }
                .parseMarkers()
        }.getOrElse {
            DefaultStubMarkers
        }
    }

    private fun String.parseMarkers(): List<String> =
        lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .map { marker -> marker.lowercase(Locale.ROOT) }
            .toList()

    private companion object {
        private const val StubMarkersAssetPath = "rkn/stub_markers.txt"
    }
}

class RknStubPageDetector(
    private val markers: List<String>,
    private val matchWindowSize: Int = DefaultMatchWindowSize,
) {
    constructor(
        assetLoader: DpiAssetLoader,
        matchWindowSize: Int = DefaultMatchWindowSize,
    ) : this(assetLoader.loadStubMarkers(), matchWindowSize)

    fun detect(
        body: String,
        statusCode: Int,
    ): StubDetection {
        if (statusCode == HttpUnavailableForLegalReasons) {
            return StubDetection(
                isStub = true,
                confidence = StubDetectionConfidence.HIGH,
                matchedMarker = null,
                via451 = true,
            )
        }

        val normalizedBody = body.take(matchWindowSize).lowercase(Locale.ROOT)
        val matchedMarker = markers.firstOrNull { marker -> normalizedBody.contains(marker.lowercase(Locale.ROOT)) }
        return if (matchedMarker != null) {
            StubDetection(
                isStub = true,
                confidence = StubDetectionConfidence.HIGH,
                matchedMarker = matchedMarker,
                via451 = false,
            )
        } else {
            StubDetection(
                isStub = false,
                confidence = StubDetectionConfidence.LOW,
                matchedMarker = null,
                via451 = false,
            )
        }
    }

    private companion object {
        private const val HttpUnavailableForLegalReasons = 451
        private const val DefaultMatchWindowSize = 2_000
    }
}

val DefaultStubMarkers: List<String> =
    listOf(
        "доступ ограничен",
        "доступ к запрашиваемому ресурсу",
        "решению роскомнадзора",
        "решением суда",
        "заблокирован",
        "blocked by roskomnadzor",
        "blocked by rkn",
        "rkn.gov.ru/org/register",
        "единый реестр",
        "запрещен",
    )
