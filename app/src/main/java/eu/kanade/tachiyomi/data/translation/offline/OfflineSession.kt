package eu.kanade.tachiyomi.data.translation.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.kanade.tachiyomi.data.translation.TranslationException
import eu.kanade.tachiyomi.data.translation.overlay.BoxKind
import eu.kanade.tachiyomi.data.translation.overlay.TextBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline translation of one page: ML Kit reads the text on the phone, ML Kit translates it on the phone. The result
 * has the same shape as the Gemini overlay answer, so the existing renderer draws it.
 *
 * Only the language packs are downloaded (once). Page images never leave the phone.
 *
 * One session is created per chapter and must be closed.
 */
class OfflineSession private constructor(
    private val sourceTag: String,
    private val recognizer: TextRecognizer,
    private val translator: Translator,
) : AutoCloseable {

    private val translations = HashMap<String, String>()

    /** Downloads the language packs if they are not on the phone yet. */
    suspend fun prepare() {
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.ModelDownloadFailed(e)
        }
    }

    /**
     * Finds, cleans and translates the text of a page.
     *
     * @param original the page file as it is stored. It is decoded in strips, so tall pages are fine.
     */
    suspend fun detectAndTranslate(original: ByteArray): List<TextBox> {
        val decoder = openDecoder(original)
        try {
            val width = decoder.width
            val height = decoder.height
            var sample = 1
            while (width / sample > MAX_RECOGNITION_WIDTH) sample *= 2

            val found = mutableListOf<Found>()
            val tiles = TilePlanner.plan(width, height)
            for (tile in tiles) {
                val bitmap = decodeStrip(decoder, width, tile, sample)
                try {
                    val text = try {
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw TranslationException.CorruptPage("text recognition failed", e)
                    }
                    for (block in text.textBlocks) {
                        val box = block.boundingBox ?: continue
                        val centerY = tile.top + (box.top + box.bottom) / 2 * sample
                        // A block seen by two strips is kept by the strip that owns its centre.
                        if (!tile.owns(centerY)) continue

                        val joined = OcrTextFilter.join(block.lines.map { it.text }, sourceTag)
                        val clean = OcrTextFilter.normalizeCase(joined, sourceTag)
                        if (!OcrTextFilter.isTranslatable(clean, sourceTag)) continue
                        found += Found(
                            left = box.left * sample,
                            top = tile.top + box.top * sample,
                            right = box.right * sample,
                            bottom = tile.top + box.bottom * sample,
                            text = clean,
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            val boxes = mutableListOf<TextBox>()
            for (item in found.sortedWith(compareBy({ it.top }, { it.left }))) {
                val translated = translate(item.text)
                if (translated.isBlank()) continue
                boxes += TextBox.fromPixels(
                    left = item.left.coerceIn(0, width - 1),
                    top = item.top.coerceIn(0, height - 1),
                    right = item.right.coerceIn(1, width),
                    bottom = item.bottom.coerceIn(1, height),
                    imageWidth = width,
                    imageHeight = height,
                    kind = BoxKind.BUBBLE,
                    text = translated,
                )
            }
            return boxes
        } finally {
            decoder.recycle()
        }
    }

    private suspend fun translate(text: String): String {
        translations[text]?.let { return it }
        val result = try {
            translator.translate(text).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.CorruptPage("translation failed", e)
        }
        translations[text] = result
        return result
    }

    override fun close() {
        recognizer.close()
        translator.close()
    }

    private class Found(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String)

    private fun openDecoder(bytes: ByteArray): BitmapRegionDecoder {
        try {
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            }
            if (decoder == null || decoder.width <= 0 || decoder.height <= 0) {
                throw TranslationException.CorruptPage("not a decodable image")
            }
            return decoder
        } catch (e: IOException) {
            throw TranslationException.CorruptPage("not a decodable image", e)
        } catch (e: IllegalArgumentException) {
            throw TranslationException.CorruptPage("not a decodable image", e)
        }
    }

    private fun decodeStrip(decoder: BitmapRegionDecoder, width: Int, tile: TilePlanner.Tile, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        try {
            return decoder.decodeRegion(Rect(0, tile.top, width, tile.bottom), options)
                ?: throw TranslationException.CorruptPage("not a decodable image")
        } catch (e: OutOfMemoryError) {
            throw TranslationException.CorruptPage("page too large to process", e)
        }
    }

    companion object {
        /** Wider pages are read at half size (or less), which the recognizer copes with. */
        private const val MAX_RECOGNITION_WIDTH = 2400

        /**
         * @param sourceTag language of the pages, one of the tags in `TranslationOptions.SOURCE_LANGUAGES`.
         * @param targetTag BCP 47 tag of the target language, or null if the offline translator has none.
         */
        fun create(sourceTag: String, targetTag: String?): OfflineSession {
            val source = TranslateLanguage.fromLanguageTag(sourceTag)
                ?: throw TranslationException.UnsupportedLanguage(sourceTag)
            val target = targetTag?.let { TranslateLanguage.fromLanguageTag(it) }
                ?: throw TranslationException.UnsupportedLanguage(targetTag)

            val recognizer = when (sourceTag) {
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
            return OfflineSession(sourceTag, recognizer, translator)
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
