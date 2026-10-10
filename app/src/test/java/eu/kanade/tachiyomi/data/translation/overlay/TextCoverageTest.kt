package eu.kanade.tachiyomi.data.translation.overlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextCoverageTest {

    @Test
    fun `reports OCR regions without a matching Gemini box`() {
        val missing = TextCoverage.missingRegions(
            regions = listOf(region("Open the monitor")),
            boxes = emptyList(),
            imageWidth = 100,
            imageHeight = 100,
            translateSfx = true,
        )

        assertEquals(listOf(region("Open the monitor")), missing)
    }

    @Test
    fun `accepts a box whose transcription covers the OCR text`() {
        val missing = TextCoverage.missingRegions(
            regions = listOf(region("Open the monitor")),
            boxes = listOf(box("Open the monitor")),
            imageWidth = 100,
            imageHeight = 100,
            translateSfx = true,
        )

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `reports text omitted from a spatially overlapping translation`() {
        val missing = TextCoverage.missingRegions(
            regions = listOf(region("Open the monitor carefully")),
            boxes = listOf(box("Open the monitor")),
            imageWidth = 100,
            imageHeight = 100,
            translateSfx = true,
        )

        assertEquals(listOf(region("Open the monitor carefully")), missing)
    }

    @Test
    fun `does not recover sound effects when they are already classified and disabled`() {
        val soundEffect = box("BAM", kind = BoxKind.SFX)
        val missing = TextCoverage.missingRegions(
            regions = listOf(region("BAM")),
            boxes = listOf(soundEffect),
            imageWidth = 100,
            imageHeight = 100,
            translateSfx = false,
        )

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `does not let a disabled SFX box hide dialogue in the same OCR region`() {
        val missing = TextCoverage.missingRegions(
            regions = listOf(region("No, BAM")),
            boxes = listOf(box("BAM", kind = BoxKind.SFX)),
            imageWidth = 100,
            imageHeight = 100,
            translateSfx = false,
        )

        assertEquals(listOf(region("No, BAM")), missing)
    }

    @Test
    fun `clamps recovered bounds to OCR region plus small margin before page mapping`() {
        val recovered = TextBox(0, 0, 1000, 1000, BoxKind.BUBBLE, "ترجمة", "missed lettering")
        val mapped = TextCoverage.clampRecoveryBox(
            box = recovered,
            recoveryCrop = PixelRect(20, 20, 80, 80),
            targetRegion = DetectedTextRegion(PixelRect(40, 40, 50, 50), "missed lettering"),
            imageWidth = 100,
            imageHeight = 100,
        )

        requireNotNull(mapped)
        val mappedPixels = mapped.toPixelRect(100, 100)
        assertTrue(mappedPixels.left >= 37)
        assertTrue(mappedPixels.right <= 53)
        assertTrue(mappedPixels.top >= 37)
        assertTrue(mappedPixels.bottom <= 53)
    }

    @Test
    fun `drops recovery boxes that do not intersect the OCR target`() {
        val recovered = TextBox(0, 0, 100, 100, BoxKind.BUBBLE, "ترجمة", "missed lettering")
        val mapped = TextCoverage.clampRecoveryBox(
            box = recovered,
            recoveryCrop = PixelRect(0, 0, 20, 20),
            targetRegion = DetectedTextRegion(PixelRect(70, 70, 80, 80), "missed lettering"),
            imageWidth = 100,
            imageHeight = 100,
        )

        assertNull(mapped)
    }

    private fun region(text: String) = DetectedTextRegion(PixelRect(10, 10, 40, 20), text)

    private fun box(sourceText: String, kind: BoxKind = BoxKind.BUBBLE) =
        TextBox(100, 100, 200, 400, kind, "ترجمة", sourceText)
}
