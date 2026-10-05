package eu.kanade.tachiyomi.data.translation.overlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BubbleFinderTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private class Page(val width: Int, val height: Int, background: Int) {
        val pixels = IntArray(width * height) { background }
        val bounds = PixelRect(0, 0, width, height)

        fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, fill: Int, outline: Int, outlineWidth: Int = 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val outer = sq((x - cx).toDouble() / rx) + sq((y - cy).toDouble() / ry)
                    val inner = sq((x - cx).toDouble() / (rx - outlineWidth)) +
                        sq((y - cy).toDouble() / (ry - outlineWidth))
                    if (inner <= 1.0) {
                        pixels[y * width + x] = fill
                    } else if (outer <= 1.0) {
                        pixels[y * width + x] = outline
                    }
                }
            }
        }

        /** Draws a checkerboard of dark pixels inside [rect], like text strokes. */
        fun text(rect: PixelRect, color: Int) {
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    if ((x + y) % 2 == 0) pixels[y * width + x] = color
                }
            }
        }

        fun window(rect: PixelRect): IntArray {
            val out = IntArray(rect.width * rect.height)
            for (y in 0 until rect.height) {
                for (x in 0 until rect.width) out[y * rect.width + x] = pixels[(rect.top + y) * width + rect.left + x]
            }
            return out
        }

        private fun sq(v: Double) = v * v
    }

    private fun find(page: Page, box: PixelRect): BubbleRegion {
        val window = BubbleFinder.windowFor(box, page.bounds)
        return BubbleFinder.find(page.window(window), window, box)
    }

    @Test
    fun `traces a white bubble up to its outline`() {
        val page = Page(200, 200, 0xFF808080.toInt())
        page.ellipse(100, 100, 60, 45, white, black)
        val box = PixelRect(65, 78, 135, 122)
        page.text(PixelRect(70, 82, 130, 118), black)

        val region = find(page, box)

        assertTrue(region.fromFloodFill)
        assertEquals(white, region.background)
        // Inside the bubble but away from the text
        assertTrue(region.contains(100, 60))
        assertTrue(region.contains(48, 100))
        // Outline and artwork are untouched
        assertFalse(region.contains(40, 100))
        assertFalse(region.contains(10, 100))
        assertFalse(region.contains(100, 52))
    }

    @Test
    fun `old text is fully covered including letters that stick out of the box`() {
        val page = Page(200, 200, 0xFF808080.toInt())
        page.ellipse(100, 100, 60, 45, white, black)
        val box = PixelRect(65, 78, 135, 122)
        page.text(PixelRect(62, 76, 138, 124), black) // 3px bigger than the box on every side

        val region = find(page, box)

        for (y in 76 until 124) {
            for (x in 62 until 138) {
                if ((x + y) % 2 == 0) assertTrue(region.contains(x, y), "text pixel $x,$y left uncovered")
            }
        }
    }

    @Test
    fun `text rectangle is larger than the box and stays inside the bubble`() {
        val page = Page(200, 200, 0xFF808080.toInt())
        page.ellipse(100, 100, 60, 45, white, black)
        val box = PixelRect(80, 90, 120, 110)
        page.text(PixelRect(82, 92, 118, 108), black)

        val region = find(page, box)
        val r = region.textRect

        assertTrue(region.fromFloodFill)
        assertTrue(r.width > box.width, "expected wider than the box, got ${r.width}")
        assertTrue(r.height > box.height, "expected taller than the box, got ${r.height}")
        assertTrue(region.contains(r.left, r.top))
        assertTrue(region.contains(r.right - 1, r.top))
        assertTrue(region.contains(r.left, r.bottom - 1))
        assertTrue(region.contains(r.right - 1, r.bottom - 1))
    }

    @Test
    fun `gray bubble keeps its colour`() {
        val gray = 0xFFC8C8C8.toInt()
        val page = Page(200, 200, white)
        page.ellipse(100, 100, 60, 45, gray, black)
        val box = PixelRect(65, 78, 135, 122)
        page.text(PixelRect(70, 82, 130, 118), black)

        val region = find(page, box)

        assertTrue(region.fromFloodFill)
        assertEquals(gray, region.background)
    }

    @Test
    fun `leaking fill falls back to the padded box`() {
        // No outline: the white fill would flood the whole page
        val page = Page(200, 200, white)
        val box = PixelRect(80, 90, 120, 110)
        page.text(PixelRect(82, 92, 118, 108), black)

        val region = find(page, box)

        assertFalse(region.fromFloodFill)
        assertTrue(region.contains(100, 100))
        assertFalse(region.contains(20, 20))
        assertFalse(region.contains(100, 60))
        // The text still has room to be drawn inside the padded box
        assertTrue(region.textRect.width in 30..60)
    }

    @Test
    fun `colour tolerance ignores jpeg style noise`() {
        val page = Page(200, 200, 0xFF808080.toInt())
        page.ellipse(100, 100, 60, 45, white, black)
        for (i in page.pixels.indices) {
            if (page.pixels[i] == white && i % 7 == 0) page.pixels[i] = 0xFFF2F2F2.toInt()
        }
        val box = PixelRect(65, 78, 135, 122)

        val region = find(page, box)

        assertTrue(region.fromFloodFill)
        assertTrue(region.contains(48, 100))
        assertFalse(region.contains(40, 100))
    }

    @Test
    fun `boxes in the same bubble are grouped and boxes in other bubbles are not`() {
        val page = Page(400, 200, 0xFF808080.toInt())
        page.ellipse(100, 100, 70, 60, white, black)
        page.ellipse(300, 100, 70, 60, white, black)
        val boxes = listOf(
            PixelRect(80, 70, 120, 90), // bubble A, first line
            PixelRect(80, 100, 120, 125), // bubble A, second line
            PixelRect(280, 85, 320, 115), // bubble B
        )
        for (box in boxes) page.text(box, black)

        val regions = boxes.map { find(page, it) }
        val groups = BubbleGrouping.group(boxes, regions)

        assertEquals(listOf(listOf(0, 1), listOf(2)), groups)
    }

    @Test
    fun `font fit finds the largest fitting size`() {
        val size = FontFit.largestFitting(8f, 80f) { it <= 23.3f }
        assertTrue(abs(size - 23.3f) <= 0.5f, "got $size")
        assertTrue(size <= 23.3f)
    }

    @Test
    fun `font fit returns the bounds when everything or nothing fits`() {
        assertEquals(80f, FontFit.largestFitting(8f, 80f) { true })
        assertEquals(8f, FontFit.largestFitting(8f, 80f) { false })
        assertEquals(10f, FontFit.largestFitting(10f, 10f) { true })
    }
}
