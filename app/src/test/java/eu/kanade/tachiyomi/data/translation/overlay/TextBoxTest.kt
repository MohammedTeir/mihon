package eu.kanade.tachiyomi.data.translation.overlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextBoxTest {

    @Test
    fun `box built from pixels covers the original pixels after converting back`() {
        val sizes = listOf(800 to 1200, 1000 to 13000, 719 to 4321)
        for ((w, h) in sizes) {
            val rects = listOf(PixelRect(0, 0, 50, 50), PixelRect(123, 457, 311, 902), PixelRect(w - 30, h - 20, w, h))
            for (rect in rects) {
                val box = TextBox.fromPixels(rect.left, rect.top, rect.right, rect.bottom, w, h, BoxKind.BUBBLE, "x")
                val back = box.toPixelRect(w, h)
                assertTrue(back.left <= rect.left && back.top <= rect.top, "$rect in ${w}x$h -> $back")
                assertTrue(back.right >= rect.right && back.bottom >= rect.bottom, "$rect in ${w}x$h -> $back")
            }
        }
    }

    @Test
    fun `degenerate boxes still have a size`() {
        val box = TextBox.fromPixels(10, 10, 10, 10, 100, 100, BoxKind.BUBBLE, "x")
        assertTrue(box.xMax > box.xMin && box.yMax > box.yMin)
        assertEquals("x", box.text)
    }
}
