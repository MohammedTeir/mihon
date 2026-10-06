package eu.kanade.tachiyomi.data.translation.overlay

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

enum class BoxKind { BUBBLE, CAPTION, SFX }

/**
 * One piece of text found by the model, with its translation.
 * Coordinates are on Gemini's 0-1000 grid: top, left, bottom, right.
 */
data class TextBox(
    val yMin: Int,
    val xMin: Int,
    val yMax: Int,
    val xMax: Int,
    val kind: BoxKind,
    val text: String,
) {
    /** Converts the normalized box to pixels of an image with the given size. */
    fun toPixelRect(imageWidth: Int, imageHeight: Int): PixelRect {
        val left = (xMin / GRID * imageWidth).roundToInt().coerceIn(0, max(0, imageWidth - 1))
        val top = (yMin / GRID * imageHeight).roundToInt().coerceIn(0, max(0, imageHeight - 1))
        val right = (xMax / GRID * imageWidth).roundToInt().coerceIn(left + 1, imageWidth)
        val bottom = (yMax / GRID * imageHeight).roundToInt().coerceIn(top + 1, imageHeight)
        return PixelRect(left, top, right, bottom)
    }

    companion object {
        const val GRID = 1000f

        /**
         * Builds a box from pixel coordinates. The grid has no more precision than 1/1000 of the page, so the
         * box is rounded outwards: converting it back always covers the original pixels.
         */
        fun fromPixels(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            imageWidth: Int,
            imageHeight: Int,
            kind: BoxKind,
            text: String,
        ): TextBox {
            val grid = GRID.toInt()
            val xMin = floor(left.toDouble() / imageWidth * grid).toInt().coerceIn(0, grid - 1)
            val yMin = floor(top.toDouble() / imageHeight * grid).toInt().coerceIn(0, grid - 1)
            val xMax = ceil(right.toDouble() / imageWidth * grid).toInt().coerceIn(xMin + 1, grid)
            val yMax = ceil(bottom.toDouble() / imageHeight * grid).toInt().coerceIn(yMin + 1, grid)
            return TextBox(yMin, xMin, yMax, xMax, kind, text)
        }
    }
}
