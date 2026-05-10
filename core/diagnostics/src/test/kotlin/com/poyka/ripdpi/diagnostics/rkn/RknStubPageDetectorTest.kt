package com.poyka.ripdpi.diagnostics.rkn

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RknStubPageDetectorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "rkn/stub_markers.txt").delete()
    }

    @Test
    fun `http 451 returns high confidence via 451`() {
        val detection = detector().detect(body = "", statusCode = 451)

        assertTrue(detection.isStub)
        assertEquals(StubDetectionConfidence.HIGH, detection.confidence)
        assertNull(detection.matchedMarker)
        assertTrue(detection.via451)
    }

    @Test
    fun `marker dostup ogranichen matches`() {
        val detection = detector().detect(body = "<html>доступ ограничен</html>", statusCode = 200)

        assertTrue(detection.isStub)
        assertEquals(StubDetectionConfidence.HIGH, detection.confidence)
        assertEquals("доступ ограничен", detection.matchedMarker)
        assertFalse(detection.via451)
    }

    @Test
    fun `marker blocked by rkn matches`() {
        val detection = detector().detect(body = "<title>blocked by rkn</title>", statusCode = 200)

        assertTrue(detection.isStub)
        assertEquals("blocked by rkn", detection.matchedMarker)
    }

    @Test
    fun `case insensitive match`() {
        val detection = detector().detect(body = "ДОСТУП ОГРАНИЧЕН", statusCode = 200)

        assertTrue(detection.isStub)
        assertEquals("доступ ограничен", detection.matchedMarker)
    }

    @Test
    fun `match window limited to first 2000 chars`() {
        val body = "a".repeat(5_000) + "доступ ограничен"

        val detection = detector().detect(body = body, statusCode = 200)

        assertFalse(detection.isStub)
        assertEquals(StubDetectionConfidence.LOW, detection.confidence)
        assertNull(detection.matchedMarker)
        assertFalse(detection.via451)
    }

    @Test
    fun `non matching body returns false`() {
        val body = "<html><body>ordinary successful page</body></html>"

        val detection = detector().detect(body = body, statusCode = 200)

        assertFalse(detection.isStub)
        assertEquals(StubDetectionConfidence.LOW, detection.confidence)
        assertNull(detection.matchedMarker)
        assertFalse(detection.via451)
    }

    @Test
    fun `broad roskomnadzor mention alone does not match`() {
        val body = "Новостная статья упоминает Роскомнадзор без страницы блокировки."

        val detection = detector().detect(body = body, statusCode = 200)

        assertFalse(detection.isStub)
    }

    @Test
    fun `user override extends marker set`() {
        val override = File(context.filesDir, "rkn/stub_markers.txt")
        override.parentFile?.mkdirs()
        override.writeText("новый блок\n")

        val detection =
            RknStubPageDetector(DpiAssetLoader(context)).detect(
                body = "страница провайдера: новый блок",
                statusCode = 200,
            )

        assertTrue(detection.isStub)
        assertEquals("новый блок", detection.matchedMarker)
    }

    @Test
    fun `loader skips comments and caches markers`() {
        val override = File(context.filesDir, "rkn/stub_markers.txt")
        override.parentFile?.mkdirs()
        override.writeText("# comment\n\nновый блок\n")
        val loader = DpiAssetLoader(context)

        assertEquals("новый блок", loader.loadStubMarkers().first())
        override.writeText("другая строка\n")
        assertEquals("новый блок", loader.loadStubMarkers().first())
    }

    private fun detector(): RknStubPageDetector = RknStubPageDetector(DpiAssetLoader(context))
}

@RunWith(Parameterized::class)
class RknStubPageDetectorMarkerCoverageTest(
    private val marker: String,
) {
    @Test
    fun `all shipped markers match independently`() {
        val detector = RknStubPageDetector(markers = StubMarkers)

        val detection = detector.detect(body = "prefix $marker suffix", statusCode = 200)

        assertTrue(detection.isStub)
        assertEquals(StubDetectionConfidence.HIGH, detection.confidence)
        assertEquals(marker, detection.matchedMarker)
        assertFalse(detection.via451)
    }

    companion object {
        private val StubMarkers =
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

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun markers(): List<Array<String>> = StubMarkers.map { marker -> arrayOf(marker) }
    }
}
