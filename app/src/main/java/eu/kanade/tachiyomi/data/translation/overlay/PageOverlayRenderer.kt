package eu.kanade.tachiyomi.data.translation.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import eu.kanade.tachiyomi.data.translation.TranslationException
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translations over the original page (overlay mode):
 * erase the old text by repainting the bubble in its own colour, then draw the translation centred with
 * [StaticLayout], which handles right-to-left scripts such as Arabic.
 *
 * All geometry decisions are made by the pure classes in this package; this class only touches bitmaps.
 */
object PageOverlayRenderer {

    class Result(val bytes: ByteArray, val mimeType: String)

    private const val MAX_SIDE = 4096

    /** Lettering is never taller than this share of the page width. Anything bigger is artwork. */
    private const val MAX_LETTER_SIDE_RATIO = 0.07f
    private const val OUTPUT_QUALITY = 93
    private const val LUMINANCE_DARK_BACKGROUND = 110

    /**
     * @param boxes translated text boxes. Must not be empty.
     * @throws TranslationException.CorruptPage when the page cannot be decoded or is too large for memory.
     */
    fun render(original: ByteArray, boxes: List<TextBox>): Result {
        var bitmap: Bitmap? = null
        try {
            bitmap = decodeMutable(original)
            drawTranslations(bitmap, boxes)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, OUTPUT_QUALITY, out)) {
                throw TranslationException.CorruptPage("could not encode the result")
            }
            return Result(out.toByteArray(), "image/jpeg")
        } catch (e: OutOfMemoryError) {
            throw TranslationException.CorruptPage("page too large to process", e)
        } finally {
            bitmap?.recycle()
        }
    }

    private fun decodeMutable(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw TranslationException.CorruptPage("not a decodable image")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw TranslationException.CorruptPage("not a decodable image")
    }

    private fun drawTranslations(bitmap: Bitmap, boxes: List<TextBox>) {
        val imageRect = PixelRect(0, 0, bitmap.width, bitmap.height)
        val pixelBoxes = boxes.map { it.toPixelRect(bitmap.width, bitmap.height) }

        // First pass: trace every bubble on the untouched page, so one erased bubble cannot confuse the next.
        val regions = pixelBoxes.map { regionFor(bitmap, it, imageRect) }
        val groups = BubbleGrouping.group(pixelBoxes, regions)

        // Merge groups into single items (union box, joined text) and trace them again if they changed.
        class Item(val region: BubbleRegion, val text: String)

        val items = groups.map { group ->
            if (group.size == 1) {
                Item(regions[group.first()], boxes[group.first()].text)
            } else {
                val union = group.map { pixelBoxes[it] }.reduce(PixelRect::union)
                val text = group.joinToString(" ") { boxes[it].text.trim() }
                Item(regionFor(bitmap, union, imageRect), text)
            }
        }

        val canvas = Canvas(bitmap)
        val erase = Paint().apply { style = Paint.Style.FILL }
        for (item in items) paintMask(bitmap, canvas, erase, item.region)
        for (item in items) drawText(canvas, item.text, item.region, bitmap.width)
    }

    private fun regionFor(bitmap: Bitmap, box: PixelRect, image: PixelRect): BubbleRegion {
        val window = BubbleFinder.windowFor(box, image)
        val pixels = IntArray(window.width * window.height)
        bitmap.getPixels(pixels, 0, window.width, window.left, window.top, window.width, window.height)
        val config = BubbleFinder.Config(maxLetterSide = (bitmap.width * MAX_LETTER_SIDE_RATIO).toInt())
        return BubbleFinder.find(pixels, window, box, config)
    }

    /** Fills the mask with the bubble colour, one horizontal run at a time. */
    private fun paintMask(bitmap: Bitmap, canvas: Canvas, paint: Paint, region: BubbleRegion) {
        val w = region.window.width
        val h = region.window.height
        val fill = region.fill
        if (fill != null) {
            // Open text: replace the letters only, each pixel with its own colour.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (region.mask[y * w + x]) {
                        bitmap.setPixel(region.window.left + x, region.window.top + y, fill[y * w + x])
                    }
                }
            }
            return
        }
        paint.color = region.background
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!region.mask[y * w + x]) {
                    x++
                    continue
                }
                val start = x
                while (x < w && region.mask[y * w + x]) x++
                canvas.drawRect(
                    (region.window.left + start).toFloat(),
                    (region.window.top + y).toFloat(),
                    (region.window.left + x).toFloat(),
                    (region.window.top + y + 1).toFloat(),
                    paint,
                )
            }
        }
    }

    private fun drawText(canvas: Canvas, text: String, region: BubbleRegion, imageWidth: Int) {
        val clean = text.trim()
        val area = region.textRect
        if (clean.isEmpty() || area.isEmpty) return

        // Open text keeps the colour of the original lettering and gets a thin contrasting outline, so it stays
        // readable on gradients and artwork. Bubbles use black or white depending on their colour.
        val letterColor = region.textColor
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = letterColor
                ?: if (luminance(region.background) < LUMINANCE_DARK_BACKGROUND) Color.WHITE else Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val minSize = max(8f, imageWidth * 0.010f)
        val maxSize = max(minSize, min(area.height * 0.9f, imageWidth * 0.05f))
        val size = FontFit.largestFitting(minSize, maxSize) { candidate ->
            val layout = layoutFor(clean, paint, candidate, area.width)
            layout.height <= area.height && widestLine(layout) <= area.width
        }
        val layout = layoutFor(clean, paint, size, area.width)

        canvas.save()
        canvas.clipRect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat())
        val top = area.top + (area.height - layout.height) / 2f
        canvas.translate(area.left.toFloat(), max(area.top.toFloat(), top))
        if (letterColor != null) {
            val outlinePaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, size * 0.12f)
                strokeJoin = Paint.Join.ROUND
                color = if (luminance(letterColor) < LUMINANCE_DARK_BACKGROUND) Color.WHITE else Color.BLACK
            }
            layoutFor(clean, outlinePaint, size, area.width).draw(canvas)
            // layoutFor() set the size on the paint it was given, keep the fill paint in sync.
            paint.textSize = size
        }
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutFor(text: String, paint: TextPaint, size: Float, width: Int): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /** A single word wider than the area does not wrap, so the height check alone would let it overflow. */
    private fun widestLine(layout: StaticLayout): Float {
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        return widest
    }

    private fun luminance(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }
}
