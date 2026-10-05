package eu.kanade.tachiyomi.data.translation.overlay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The area of a page that belongs to one speech bubble (or caption box).
 *
 * @property window region of the page that [mask] covers.
 * @property mask `window.width * window.height` flags, true for pixels that must be repainted with [background].
 * @property background colour of the bubble (ARGB).
 * @property textRect where the translation is drawn, in page coordinates.
 * @property fromFloodFill false when the bubble could not be traced (open text). Then only the letters are erased.
 */
class BubbleRegion(
    val window: PixelRect,
    val mask: BooleanArray,
    val background: Int,
    val textRect: PixelRect,
    val fromFloodFill: Boolean,
    /** When set, the mask pixels are replaced with these colours (window sized) instead of [background]. */
    val fill: IntArray? = null,
    /** Colour of the original lettering when it was detected (open text), else null. */
    val textColor: Int? = null,
    /** Area that counts as belonging to this region for grouping, when the mask only holds the letters. */
    val extent: PixelRect? = null,
) {
    /** True if the page pixel ([x], [y]) belongs to this bubble. */
    fun contains(x: Int, y: Int): Boolean {
        if (extent != null) return extent.contains(x, y)
        if (!window.contains(x, y)) return false
        return mask[(y - window.top) * window.width + (x - window.left)]
    }
}

/**
 * Pure pixel logic (no Android classes) that finds the real inner area of a bubble around a text box.
 *
 * Steps for one box:
 * 1. Sample the bubble colour just inside and just outside the box edge (median per channel).
 * 2. Flood fill outwards from the box edge through pixels of that colour until the bubble outline stops it.
 * 3. Add the whole padded box and any holes (letters that stick out), so no old text is left.
 * 4. If the fill leaked (open bubble, page background) treat it as open text and erase only the letters.
 * 5. Grow a rectangle from the box centre inside the traced area. The translation is drawn there.
 */
object BubbleFinder {

    class Config(
        /** Padding added around the text box so no letter edges stay visible (fraction of box size). */
        val boxPadding: Float = 0.08f,
        /** How far outside the box the fill may travel, as a multiple of the box's longer side. */
        val windowScale: Float = 1.5f,
        /** Upper limit of the window margin in pixels, to bound memory use on huge pages. */
        val maxWindowMargin: Int = 600,
        /** Max per-channel difference from the bubble colour for a pixel to count as bubble. */
        val colorTolerance: Int = 40,
        /** A traced area touching more than this fraction of the window border is treated as a leak. */
        val maxEdgeContact: Float = 0.25f,
        /** Fraction of the inscribed rectangle kept as margin for the text. */
        val textMargin: Float = 0.05f,
        /** Minimum share of a growth strip that must lie inside the bubble. */
        val growthCoverage: Float = 0.97f,
        /** Open text (no bubble outline): how far the erased area may grow vertically over leftover ink. */
        val inkExtendVertical: Float = 0.4f,
        /** Same, horizontally. Models are usually right about the width and wrong about the height. */
        val inkExtendHorizontal: Float = 0.1f,
        /**
         * Open text: largest height of a connected group of lettering pixels. Bigger groups are artwork (faces,
         * outlines, panel borders) and are never erased. The caller sets it from the page size.
         */
        val maxLetterSide: Int = Int.MAX_VALUE,
    )

    /** The part of the page that [find] needs pixels for. */
    fun windowFor(box: PixelRect, image: PixelRect, config: Config = Config()): PixelRect {
        val margin = (max(box.width, box.height) * config.windowScale).toInt().coerceIn(2, config.maxWindowMargin)
        return box.expandedWithin(margin, margin, image)
    }

    /**
     * @param windowPixels ARGB pixels of [window], row by row (stride = window.width).
     * @param box the text box in page coordinates, already clipped to the page.
     */
    fun find(
        windowPixels: IntArray,
        window: PixelRect,
        box: PixelRect,
        config: Config = Config(),
    ): BubbleRegion {
        val w = window.width
        val h = window.height
        require(windowPixels.size >= w * h) { "windowPixels is smaller than the window" }

        val windowBounds = PixelRect(0, 0, w, h)
        val boxInWindow = PixelRect(
            box.left - window.left,
            box.top - window.top,
            box.right - window.left,
            box.bottom - window.top,
        ).coerceIn(windowBounds)

        val padX = max(1, (box.width * config.boxPadding).toInt())
        val padY = max(1, (box.height * config.boxPadding).toInt())
        val paddedBox = boxInWindow.expandedWithin(padX, padY, windowBounds)

        val background = sampleBackground(windowPixels, w, h, boxInWindow)

        val mask = BooleanArray(w * h)
        val seeds = collectSeeds(windowPixels, w, boxInWindow, background, config.colorTolerance)
        floodFill(windowPixels, w, h, seeds, background, config.colorTolerance, mask)
        fillRect(mask, w, paddedBox)
        fillHoles(mask, w, h)

        // A real bubble is closed by its outline, so it stays away from the window border. A fill that reaches
        // the border for a large part of its length has escaped into the page background.
        val leaked = edgeContact(mask, w, h) > config.maxEdgeContact

        if (leaked || seeds.isEmpty()) {
            return openText(windowPixels, window, boxInWindow, paddedBox, background, config)
        }

        val inscribed = growInside(mask, w, h, boxInWindow, config.growthCoverage)
        return BubbleRegion(
            window = window,
            mask = mask,
            background = background,
            textRect = shrink(inscribed, config.textMargin).offset(window.left, window.top),
            fromFloodFill = true,
        )
    }

    /**
     * Text on a plain page, a glowing window or artwork: no outline to trace. Only the letters are erased (see
     * [TextEraser]); if no lettering can be told apart from the background the padded box is painted flat.
     */
    private fun openText(
        pixels: IntArray,
        window: PixelRect,
        box: PixelRect,
        paddedBox: PixelRect,
        background: Int,
        config: Config,
    ): BubbleRegion {
        val w = window.width
        val h = window.height
        val textColor = TextEraser.detectTextColor(pixels, w, paddedBox, background)

        if (textColor == null) {
            // Nothing stands out from the background, so there is nothing to erase. Better to leave the page
            // alone than to paint a flat block over artwork.
            return BubbleRegion(
                window = window,
                mask = BooleanArray(w * h),
                background = background,
                textRect = shrink(paddedBox, config.textMargin).offset(window.left, window.top),
                fromFloodFill = false,
                extent = paddedBox.offset(window.left, window.top),
            )
        }

        val cleared = extendOverLetters(pixels, w, h, paddedBox, background, textColor, config)
        val erased = TextEraser.erase(
            pixels,
            w,
            h,
            cleared,
            background,
            textColor,
            TextEraser.dilationFor(box),
            config.maxLetterSide,
        )
        return BubbleRegion(
            window = window,
            mask = erased.mask,
            background = background,
            textRect = shrink(cleared, config.textMargin).offset(window.left, window.top),
            fromFloodFill = false,
            fill = erased.fill,
            textColor = textColor,
            extent = cleared.offset(window.left, window.top),
        )
    }

    // region Background colour

    /** Median colour of a thin ring just inside and just outside [box]. */
    internal fun sampleBackground(pixels: IntArray, w: Int, h: Int, box: PixelRect): Int {
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()

        fun add(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val p = pixels[y * w + x]
            reds += (p shr 16) and 0xFF
            greens += (p shr 8) and 0xFF
            blues += p and 0xFF
        }

        val ring = max(1, min(3, min(box.width, box.height) / 10))
        for (r in 0 until ring) {
            // Inside edge
            for (x in box.left until box.right) {
                add(x, box.top + r)
                add(x, box.bottom - 1 - r)
            }
            for (y in box.top until box.bottom) {
                add(box.left + r, y)
                add(box.right - 1 - r, y)
            }
            // Outside edge
            for (x in box.left - 1 - r until box.right + 1 + r) {
                add(x, box.top - 1 - r)
                add(x, box.bottom + r)
            }
            for (y in box.top - 1 - r until box.bottom + 1 + r) {
                add(box.left - 1 - r, y)
                add(box.right + r, y)
            }
        }
        if (reds.isEmpty()) return WHITE

        fun median(values: MutableList<Int>): Int {
            values.sort()
            return values[values.size / 2]
        }
        return (0xFF shl 24) or (median(reds) shl 16) or (median(greens) shl 8) or median(blues)
    }

    // endregion

    // region Flood fill

    private fun isBackground(pixel: Int, background: Int, tolerance: Int): Boolean {
        return abs(((pixel shr 16) and 0xFF) - ((background shr 16) and 0xFF)) <= tolerance &&
            abs(((pixel shr 8) and 0xFF) - ((background shr 8) and 0xFF)) <= tolerance &&
            abs((pixel and 0xFF) - (background and 0xFF)) <= tolerance
    }

    /** Pixels along the inner edge of the box that already have the bubble colour. */
    private fun collectSeeds(pixels: IntArray, w: Int, box: PixelRect, background: Int, tolerance: Int): IntArray {
        val seeds = ArrayList<Int>()
        fun check(x: Int, y: Int) {
            if (isBackground(pixels[y * w + x], background, tolerance)) seeds += y * w + x
        }
        if (box.isEmpty) return IntArray(0)
        for (x in box.left until box.right) {
            check(x, box.top)
            check(x, box.bottom - 1)
        }
        for (y in box.top until box.bottom) {
            check(box.left, y)
            check(box.right - 1, y)
        }
        return seeds.toIntArray()
    }

    private fun floodFill(
        pixels: IntArray,
        w: Int,
        h: Int,
        seeds: IntArray,
        background: Int,
        tolerance: Int,
        mask: BooleanArray,
    ) {
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (seed in seeds) {
            if (!mask[seed]) {
                mask[seed] = true
                queue[tail++] = seed
            }
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % w
            val y = index / w
            if (x > 0) tail = visit(index - 1, pixels, background, tolerance, mask, queue, tail)
            if (x < w - 1) tail = visit(index + 1, pixels, background, tolerance, mask, queue, tail)
            if (y > 0) tail = visit(index - w, pixels, background, tolerance, mask, queue, tail)
            if (y < h - 1) tail = visit(index + w, pixels, background, tolerance, mask, queue, tail)
        }
    }

    private fun visit(
        index: Int,
        pixels: IntArray,
        background: Int,
        tolerance: Int,
        mask: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (mask[index] || !isBackground(pixels[index], background, tolerance)) return tail
        mask[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun fillRect(mask: BooleanArray, w: Int, rect: PixelRect) {
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) mask[y * w + x] = true
        }
    }

    /** Marks unreachable non-mask pixels (enclosed by the mask) as part of the mask. */
    private fun fillHoles(mask: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun push(index: Int) {
            if (!mask[index] && !outside[index]) {
                outside[index] = true
                queue[tail++] = index
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (head < queue.size && head < tail) {
            val index = queue[head++]
            val x = index % w
            val y = index / w
            if (x > 0) push(index - 1)
            if (x < w - 1) push(index + 1)
            if (y > 0) push(index - w)
            if (y < h - 1) push(index + w)
        }
        for (i in mask.indices) if (!mask[i] && !outside[i]) mask[i] = true
    }

    private fun edgeContact(mask: BooleanArray, w: Int, h: Int): Float {
        var touching = 0
        for (x in 0 until w) {
            if (mask[x]) touching++
            if (mask[(h - 1) * w + x]) touching++
        }
        for (y in 1 until h - 1) {
            if (mask[y * w]) touching++
            if (mask[y * w + w - 1]) touching++
        }
        val perimeter = 2 * (w + h) - 4
        return if (perimeter <= 0) 0f else touching.toFloat() / perimeter
    }

    // endregion

    // region Leftover ink

    /**
     * Grows [start] one row or column at a time while the strip next to it still holds lettering (pixels close to
     * [textColor]), up to a limit. Stops at the first clean strip, so neighbouring lines separated by a gap, and
     * artwork farther away, are left alone. Two rounds, because growing sideways can reveal more rows.
     */
    internal fun extendOverLetters(
        pixels: IntArray,
        w: Int,
        h: Int,
        start: PixelRect,
        background: Int,
        textColor: Int,
        config: Config,
    ): PixelRect {
        val maxV = (start.height * config.inkExtendVertical).toInt() + 4
        val maxH = (start.width * config.inkExtendHorizontal).toInt() + 4
        var rect = start

        fun lettersIn(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            if (x0 < 0 || y0 < 0 || x1 > w || y1 > h || x0 >= x1 || y0 >= y1) return false
            var count = 0
            val needed = max(2, ((x1 - x0) * (y1 - y0)) / 400)
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    if (TextEraser.isLetter(pixels[y * w + x], textColor, background) && ++count >= needed) {
                        return true
                    }
                }
            }
            return false
        }

        repeat(2) {
            var up = 0
            while (up < maxV && lettersIn(rect.left, rect.top - 1, rect.right, rect.top)) {
                rect = rect.copy(top = rect.top - 1)
                up++
            }
            var down = 0
            while (down < maxV && lettersIn(rect.left, rect.bottom, rect.right, rect.bottom + 1)) {
                rect = rect.copy(bottom = rect.bottom + 1)
                down++
            }
            var left = 0
            while (left < maxH && lettersIn(rect.left - 1, rect.top, rect.left, rect.bottom)) {
                rect = rect.copy(left = rect.left - 1)
                left++
            }
            var right = 0
            while (right < maxH && lettersIn(rect.right, rect.top, rect.right + 1, rect.bottom)) {
                rect = rect.copy(right = rect.right + 1)
                right++
            }
        }
        return rect
    }

    // endregion

    // region Text rectangle

    /**
     * Grows [start] side by side (round robin) while the new strip is mostly inside the mask.
     * The result is roughly the largest rectangle around the box centre that fits the bubble.
     */
    private fun growInside(mask: BooleanArray, w: Int, h: Int, start: PixelRect, coverage: Float): PixelRect {
        var rect = start
        val step = max(1, min(start.width, start.height) / 20)

        fun stripInside(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            if (x0 < 0 || y0 < 0 || x1 > w || y1 > h || x0 >= x1 || y0 >= y1) return false
            var inside = 0
            var total = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    total++
                    if (mask[y * w + x]) inside++
                }
            }
            return inside >= total * coverage
        }

        var grew = true
        var guard = 0
        while (grew && guard++ < 400) {
            grew = false
            if (stripInside(rect.left - step, rect.top, rect.left, rect.bottom)) {
                rect = rect.copy(left = rect.left - step)
                grew = true
            }
            if (stripInside(rect.right, rect.top, rect.right + step, rect.bottom)) {
                rect = rect.copy(right = rect.right + step)
                grew = true
            }
            if (stripInside(rect.left, rect.top - step, rect.right, rect.top)) {
                rect = rect.copy(top = rect.top - step)
                grew = true
            }
            if (stripInside(rect.left, rect.bottom, rect.right, rect.bottom + step)) {
                rect = rect.copy(bottom = rect.bottom + step)
                grew = true
            }
        }
        return rect
    }

    private fun shrink(rect: PixelRect, fraction: Float): PixelRect {
        val dx = (rect.width * fraction).toInt()
        val dy = (rect.height * fraction).toInt()
        val shrunk = PixelRect(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy)
        return if (shrunk.isEmpty) rect else shrunk
    }

    private fun PixelRect.offset(dx: Int, dy: Int) = PixelRect(left + dx, top + dy, right + dx, bottom + dy)

    // endregion

    private const val WHITE = -1 // 0xFFFFFFFF
}
