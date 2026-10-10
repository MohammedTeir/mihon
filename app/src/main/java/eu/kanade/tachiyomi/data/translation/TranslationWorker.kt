package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.domain.translation.TranslationOptions
import eu.kanade.domain.translation.TranslationPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.context.Glossary
import eu.kanade.tachiyomi.data.translation.context.PromptContext
import eu.kanade.tachiyomi.data.translation.overlay.BoxKind
import eu.kanade.tachiyomi.data.translation.overlay.DetectedTextRegion
import eu.kanade.tachiyomi.data.translation.overlay.OverlayTextDetector
import eu.kanade.tachiyomi.data.translation.overlay.PageOverlayRenderer
import eu.kanade.tachiyomi.data.translation.overlay.PixelRect
import eu.kanade.tachiyomi.data.translation.overlay.RenderStyle
import eu.kanade.tachiyomi.data.translation.overlay.TallPageCropper
import eu.kanade.tachiyomi.data.translation.overlay.TextCoverage
import eu.kanade.tachiyomi.data.translation.overlay.TextOverlayClient
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Translates the pages of one downloaded chapter with a Gemini image model and saves the result as a new chapter
 * in the Local source folder:
 *
 * `<Local source>/<Manga title> (<Language>)/<Chapter name>/001.png, 002.png, ...`
 *
 * Safety properties:
 * - Every page is written to `NNN.<ext>.tmp` and renamed when complete, so a crash never leaves a corrupt page.
 * - Pages are collected in a hidden staging folder (`.translating-<chapter>`, ignored by the Local source) and the
 *   folder is renamed to its final name only when every page is done. A half translated chapter is never shown.
 * - A re-run skips pages that already exist in the staging folder, so retrying only redoes the missing pages.
 * - Fatal errors (bad key, quota, bad model, storage) stop the job right away. Page level errors (blocked content,
 *   no image, rate limit after retries) skip the page and are reported as "X of Y pages translated".
 */
class TranslationWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var translationPreferences: TranslationPreferences

    @Inject private lateinit var client: NanoBananaClient

    @Inject private lateinit var overlayClient: TextOverlayClient

    @Inject private lateinit var overlayTextDetector: OverlayTextDetector

    @Inject private lateinit var notifier: TranslationNotifier

    @Inject private lateinit var getChapter: GetChapter

    @Inject private lateinit var getManga: GetManga

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var downloadProvider: DownloadProvider

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var localSourceFileSystem: LocalSourceFileSystem

    @Inject private lateinit var coverCache: CoverCache

    // Progress state shared with getForegroundInfo() and the error notifications.
    @Volatile private var chapterName: String = ""

    @Volatile private var totalPages: Int = 0
    private val donePages = AtomicInteger(0)

    override suspend fun doWork(): Result {
        graph.inject(this)

        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        if (chapterId == -1L || !translationPreferences.enabled().get()) return Result.failure()

        val chapter = getChapter.await(chapterId) ?: return Result.failure()
        val manga = getManga.await(chapter.mangaId) ?: return Result.failure()
        val source = sourceManager.get(manga.source) ?: return Result.failure()
        chapterName = chapter.name

        val apiKey = translationPreferences.apiKey().get().trim()
        if (apiKey.isEmpty()) {
            notifier.showError(chapter.id, chapter.name, TranslationException.InvalidApiKey(null), 0, 0)
            return Result.failure()
        }

        // One chapter at a time: avoids rate limit bursts and keeps a single progress notification.
        return queueLock.withLock { runTranslation(chapter, manga, source, apiKey) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_TRANSLATION_PROGRESS,
            notifier.progressNotification(id, chapterName, donePages.get(), totalPages),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun runTranslation(chapter: Chapter, manga: Manga, source: Source, apiKey: String): Result {
        setForegroundSafely()

        return withIOContext {
            try {
                when (val outcome = translate(chapter, manga, source, apiKey)) {
                    is Outcome.Completed -> {
                        notifier.showComplete(
                            chapter.id,
                            chapter.name,
                            outcome.seriesName,
                            outcome.alreadyTranslated,
                            outcome.coverageWarningPages,
                        )
                        Result.success()
                    }
                    is Outcome.Partial -> {
                        notifier.showPartial(
                            chapter.id,
                            chapter.name,
                            outcome.translated,
                            outcome.total,
                            outcome.failures,
                            outcome.coverageWarningPages,
                        )
                        Result.failure()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationException) {
                logcat(LogPriority.ERROR, e) { "Chapter translation stopped" }
                notifier.showError(chapter.id, chapter.name, e, donePages.get(), totalPages)
                // Offline is the only fatal error worth waiting for. WorkManager resumes it once connected.
                if (e is TranslationException.Offline && runAttemptCount < MAX_OFFLINE_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Unexpected chapter translation error" }
                notifier.showUnexpectedError(chapter.id, chapter.name, e)
                Result.failure()
            } finally {
                notifier.cancelProgress()
            }
        }
    }

    private suspend fun translate(chapter: Chapter, manga: Manga, source: Source, apiKey: String): Outcome {
        val language = translationPreferences.targetLanguage().get()
        val overlay = translationPreferences.mode().get() == TranslationOptions.MODE_OVERLAY
        val model = if (overlay) translationPreferences.textModel().get() else translationPreferences.model().get()

        val chapterDir = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        ) ?: throw TranslationException.EmptyChapter("downloaded chapter not found")

        val reader = if (chapterDir.isFile) openArchive(chapterDir) else null
        try {
            val pages = listPages(chapterDir, reader)
            return translatePages(chapter, manga, pages, apiKey, model, language, overlay)
        } finally {
            reader?.close()
        }
    }

    private suspend fun translatePages(
        chapter: Chapter,
        manga: Manga,
        pages: List<PageEntry>,
        apiKey: String,
        model: String,
        language: String,
        overlay: Boolean,
    ): Outcome {
        val baseDir = localSourceFileSystem.getBaseDirectory() ?: throw TranslationException.LocalSourceUnavailable()

        val disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get()
        val seriesName = TranslationPaths.seriesName(manga.title, language, disallowNonAscii)
        val chapterFolder = TranslationPaths.chapterFolder(chapter.name, disallowNonAscii)
        val stagingName = TranslationPaths.STAGING_PREFIX + chapterFolder

        val existingSeries = baseDir.findFile(seriesName)?.takeIf { it.isDirectory }

        // Already translated earlier: do not bill the user a second time.
        val finalDir = existingSeries?.findFile(chapterFolder)
        if (finalDir != null && finalDir.isDirectory && finalDir.listFiles().orEmpty().any { it.isPage() }) {
            return Outcome.Completed(seriesName, alreadyTranslated = true)
        }

        // Resume: keep finished pages, drop leftovers of an interrupted write.
        val existingStaging = existingSeries?.findFile(stagingName)?.takeIf { it.isDirectory }
        val finishedPages = existingStaging?.let { collectFinishedPages(it, pages.size) }.orEmpty()

        totalPages = pages.size
        donePages.set(finishedPages.size)

        val remaining = pages.size - finishedPages.size
        val perPage = if (overlay) ESTIMATED_OVERLAY_BYTES_PER_PAGE else ESTIMATED_BYTES_PER_PAGE
        val required = remaining * perPage + STORAGE_HEADROOM_BYTES
        val available = DiskUtil.getAvailableStorageSpace(baseDir)
        // `available` is -1 when it cannot be determined (for example some SAF providers). Write errors still
        // surface as StorageWriteFailed in that case.
        if (available in 0 until required) throw TranslationException.NotEnoughStorage(required, available)

        val seriesDir = existingSeries
            ?: baseDir.createDirectory(seriesName)
            ?: throw TranslationException.StorageWriteFailed(null)
        val stagingDir = existingStaging
            ?: seriesDir.createDirectory(stagingName)
            ?: throw TranslationException.StorageWriteFailed(null)
        if (existingSeries == null) {
            copyCover(seriesDir, manga)
            DiskUtil.createNoMediaFile(seriesDir, context)
        }

        notifier.showProgress(id, chapter.name, donePages.get(), totalPages)

        val indexWidth = max(MIN_INDEX_WIDTH, pages.size.toString().length)
        // Overlay mode is paced by the free tier limits, so pages go one at a time.
        val semaphore = Semaphore(if (overlay) 1 else MAX_PARALLEL_PAGES)
        val translateSfx = translationPreferences.translateSfx().get()
        val style = RenderStyle.from(
            translationPreferences.fontStyle().get(),
            translationPreferences.textSizePercent().get(),
            translationPreferences.placement().get(),
        )
        val delayMillis = translationPreferences.requestDelaySeconds().get().coerceIn(0, 120) * 1000L
        val glossary = Glossary.parse(translationPreferences.glossary(manga.id).get())
        // Overlay mode runs one page at a time, so the text of the page before is known when the next one starts.
        // Redraw mode runs pages in parallel and only sends the glossary.
        var previousTexts: List<String> = emptyList()
        val failures = ConcurrentHashMap<Int, TranslationException>()
        val coverageWarningPages = ConcurrentHashMap.newKeySet<Int>()
        val rateLimitFailures = AtomicInteger(0)
        val stopEarly = AtomicBoolean(false)

        val pendingAtStart = pages.indices.filter { it !in finishedPages }.toSet()

        suspend fun runPass(pending: Set<Int>) {
            coroutineScope {
                pages.mapIndexed { index, page ->
                    async {
                        if (index !in pending) return@async
                        semaphore.withPermit {
                            if (stopEarly.get()) return@withPermit
                            try {
                                var pageCoverageIncomplete = false
                                val bytes = readPage(page)
                                val image = if (overlay) {
                                    val uploads = withContext(Dispatchers.Default) {
                                        prepareOverlayUploads(bytes, page.mimeType)
                                    }
                                    val detections = mutableListOf<TallPageCropper.Detection>()
                                    var recoveryCalls = 0
                                    uploads.forEach { cropUpload ->
                                        val cropBoxes = overlayClient.detectAndTranslate(
                                            cropUpload.bytes,
                                            cropUpload.mimeType,
                                            apiKey,
                                            model,
                                            language,
                                            delayMillis,
                                            PromptContext.build(glossary, previousTexts),
                                        )
                                        detections += cropBoxes.map {
                                            TallPageCropper.Detection(cropUpload.crop, it)
                                        }

                                        val coverageBitmap = try {
                                            withContext(Dispatchers.Default) {
                                                BitmapFactory.decodeByteArray(
                                                    cropUpload.bytes,
                                                    0,
                                                    cropUpload.bytes.size,
                                                )
                                            }
                                        } catch (e: OutOfMemoryError) {
                                            logcat(LogPriority.WARN, e) { "Could not allocate OCR coverage image" }
                                            null
                                        }
                                        if (coverageBitmap == null) {
                                            pageCoverageIncomplete = true
                                            return@forEach
                                        }

                                        try {
                                            val textRegions = overlayTextDetector.detect(coverageBitmap)
                                            val coverageBoxes = cropBoxes.toMutableList()
                                            val missing = TextCoverage.missingRegions(
                                                textRegions,
                                                coverageBoxes,
                                                coverageBitmap.width,
                                                coverageBitmap.height,
                                                translateSfx,
                                            )
                                            val remainingCalls =
                                                (MAX_RECOVERY_CALLS_PER_PAGE - recoveryCalls).coerceAtLeast(0)
                                            missing.take(remainingCalls).forEach { region ->
                                                recoveryCalls++
                                                try {
                                                    val recoveryCrop = recoveryCropFor(
                                                        region,
                                                        coverageBitmap.width,
                                                        coverageBitmap.height,
                                                    )
                                                    val recoveryBytes = encodeCrop(coverageBitmap, recoveryCrop)
                                                    val recoveryContext = buildString {
                                                        append(PromptContext.build(glossary, previousTexts))
                                                        append(
                                                            "\n\nTargeted recovery: the OCR pass independently " +
                                                                "found this source text in the enlarged crop: «",
                                                        )
                                                        append(region.text.take(MAX_RECOVERY_TEXT_LENGTH))
                                                        append("». Translate every readable word of that text only. ")
                                                        append("Return an empty boxes list if it is not readable. ")
                                                        append("Keep each box tight to the lettering; do not include ")
                                                        append("faces, hair, bodies, or other artwork.")
                                                    }
                                                    val recoveryBoxes = overlayClient.detectAndTranslate(
                                                        recoveryBytes,
                                                        "image/jpeg",
                                                        apiKey,
                                                        model,
                                                        language,
                                                        delayMillis,
                                                        recoveryContext,
                                                    ).mapNotNull { box ->
                                                        TextCoverage.clampRecoveryBox(
                                                            box,
                                                            recoveryCrop,
                                                            region,
                                                            coverageBitmap.width,
                                                            coverageBitmap.height,
                                                        )
                                                    }
                                                    if (TextCoverage.missingRegions(
                                                            listOf(region),
                                                            recoveryBoxes,
                                                            coverageBitmap.width,
                                                            coverageBitmap.height,
                                                            translateSfx,
                                                        ).isEmpty()
                                                    ) {
                                                        coverageBoxes += recoveryBoxes
                                                        detections += recoveryBoxes.map {
                                                            TallPageCropper.Detection(cropUpload.crop, it)
                                                        }
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    logcat(LogPriority.WARN, e) {
                                                        "Targeted OCR recovery failed on page ${index + 1}"
                                                    }
                                                }
                                            }
                                            if (TextCoverage.missingRegions(
                                                    textRegions,
                                                    coverageBoxes,
                                                    coverageBitmap.width,
                                                    coverageBitmap.height,
                                                    translateSfx,
                                                ).isNotEmpty()
                                            ) {
                                                pageCoverageIncomplete = true
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            pageCoverageIncomplete = true
                                            logcat(LogPriority.WARN, e) {
                                                "OCR coverage check failed on page ${index + 1}"
                                            }
                                        } catch (e: OutOfMemoryError) {
                                            pageCoverageIncomplete = true
                                            logcat(LogPriority.WARN, e) {
                                                "OCR coverage check ran out of memory on page ${index + 1}"
                                            }
                                        } finally {
                                            coverageBitmap.recycle()
                                        }
                                    }
                                    val firstUpload = uploads.first()
                                    val allBoxes = TallPageCropper.mapAndDeduplicate(
                                        detections,
                                        firstUpload.pageWidth,
                                        firstUpload.pageHeight,
                                    )
                                    previousTexts = allBoxes.map { it.text }
                                    val boxes = allBoxes.filter { translateSfx || it.kind != BoxKind.SFX }
                                    if (boxes.isEmpty()) {
                                        // Nothing to translate: keep the page as it is.
                                        TranslatedImage(bytes, page.mimeType)
                                    } else {
                                        val rendered = withContext(Dispatchers.Default) {
                                            PageOverlayRenderer.render(bytes, boxes, style)
                                        }
                                        TranslatedImage(rendered.bytes, rendered.mimeType)
                                    }
                                } else {
                                    val upload = withContext(Dispatchers.Default) {
                                        prepareUpload(bytes, page.mimeType)
                                    }
                                    client.translatePage(
                                        upload.bytes,
                                        upload.mimeType,
                                        apiKey,
                                        model,
                                        language,
                                        PromptContext.build(glossary),
                                    )
                                }
                                writePage(stagingDir, index, indexWidth, image)
                                if (overlay && pageCoverageIncomplete) {
                                    coverageWarningPages += index
                                } else {
                                    coverageWarningPages -= index
                                }
                                donePages.incrementAndGet()
                                notifier.showProgress(id, chapter.name, donePages.get(), totalPages)
                            } catch (e: TranslationException) {
                                if (e.isFatal) {
                                    // Daily cap of the free tier reached after some pages: keep them, report "X of Y".
                                    if (overlay && e is TranslationException.QuotaExceeded && donePages.get() > 0) {
                                        failures[index] = e
                                        stopEarly.set(true)
                                        return@withPermit
                                    }
                                    throw e
                                }
                                if (e is TranslationException.NetworkError && !context.isOnline()) {
                                    throw TranslationException.Offline(e)
                                }
                                logcat(LogPriority.WARN, e) { "Page ${index + 1} of ${pages.size} failed" }
                                failures[index] = e
                                // Repeated rate limits mean every further page would fail too. Stop and let the
                                // user retry later instead of burning requests.
                                if (e is TranslationException.RateLimited &&
                                    rateLimitFailures.incrementAndGet() >= MAX_RATE_LIMIT_FAILURES
                                ) {
                                    stopEarly.set(true)
                                }
                            }
                            Unit
                        }
                    }
                }.awaitAll()
            }
        }

        // Pages that failed for a reason that usually goes away (unreadable answer, no image, server or network
        // hiccup) are retried automatically a few times before the job gives up and reports "X of Y".
        var pending = pendingAtStart
        var round = 0
        while (true) {
            runPass(pending)
            val retryable = failures.filterValues { isAutoRetryable(it) }.keys
            if (retryable.isEmpty() || stopEarly.get() || round >= MAX_AUTO_RETRY_ROUNDS) break
            round++
            retryable.forEach { failures.remove(it) }
            pending = retryable.toSet()
            logcat(LogPriority.INFO) { "Auto retry $round: ${pending.size} page(s)" }
            delay(AUTO_RETRY_DELAY_MILLIS * round)
        }

        if (failures.isNotEmpty() || donePages.get() < pages.size) {
            return Outcome.Partial(
                donePages.get(),
                pages.size,
                failures.values.toList(),
                coverageWarningPages.size,
            )
        }

        publishChapter(seriesDir, stagingDir, chapterFolder)
        return Outcome.Completed(
            seriesName,
            alreadyTranslated = false,
            coverageWarningPages = coverageWarningPages.size,
        )
    }

    // region Source pages

    private class PageEntry(val name: String, val read: () -> ByteArray) {
        val mimeType: String
            get() = when (name.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
    }

    private fun openArchive(file: UniFile): ArchiveReader {
        return try {
            file.archiveReader(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.CorruptChapter(e)
        }
    }

    /** Image pages of the downloaded chapter, natural sorted. Works for folders and .cbz archives. */
    private fun listPages(chapterDir: UniFile, reader: ArchiveReader?): List<PageEntry> {
        val pages = try {
            if (reader != null) {
                reader.useEntries { entries ->
                    entries.filter { it.isFile && hasPageExtension(it.name) }.map { it.name }.toList()
                }
                    .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                    .map { name ->
                        PageEntry(name) {
                            reader.getInputStream(name)?.use { it.readBytes() }
                                ?: throw TranslationException.CorruptPage("entry missing in archive")
                        }
                    }
            } else {
                chapterDir.listFiles().orEmpty()
                    .filter { it.isPage() }
                    .sortedWith { a, b ->
                        a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty())
                    }
                    .map { file -> PageEntry(file.name.orEmpty()) { file.openInputStream().use { it.readBytes() } } }
            }
        } catch (e: TranslationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.CorruptChapter(e)
        }

        if (pages.isEmpty()) throw TranslationException.EmptyChapter("no image files")
        return pages
    }

    private fun readPage(page: PageEntry): ByteArray {
        val bytes = try {
            page.read()
        } catch (e: TranslationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.CorruptPage("read failed", e)
        }
        if (bytes.isEmpty()) throw TranslationException.CorruptPage("empty file")
        return bytes
    }

    // endregion

    // region Image preparation

    private class Upload(val bytes: ByteArray, val mimeType: String)

    private class OverlayUpload(
        val crop: TallPageCropper.Crop,
        val pageWidth: Int,
        val pageHeight: Int,
        val bytes: ByteArray,
        val mimeType: String,
    )

    /**
     * Downscales pages whose longest side is over [MAX_UPLOAD_SIDE] px (or that are very large files) before upload.
     * Small pages are sent untouched.
     */
    private fun prepareUpload(bytes: ByteArray, mimeType: String): Upload {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw TranslationException.CorruptPage("not a decodable image")
        }

        val longestSide = max(bounds.outWidth, bounds.outHeight)
        if (longestSide <= MAX_UPLOAD_SIDE && bytes.size <= MAX_UPLOAD_BYTES) return Upload(bytes, mimeType)

        try {
            var sampleSize = 1
            while (longestSide / (sampleSize * 2) >= MAX_UPLOAD_SIDE) sampleSize *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw TranslationException.CorruptPage("not a decodable image")

            val scale = MAX_UPLOAD_SIDE.toFloat() / max(bitmap.width, bitmap.height)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)
            bitmap.recycle()
            return Upload(out.toByteArray(), "image/jpeg")
        } catch (e: OutOfMemoryError) {
            throw TranslationException.CorruptPage("page too large to process", e)
        }
    }

    /** Prepares one request image for ordinary pages, or overlapping full-width strips for very tall pages. */
    private fun prepareOverlayUploads(bytes: ByteArray, mimeType: String): List<OverlayUpload> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw TranslationException.CorruptPage("not a decodable image")
        }

        val initialPlan = TallPageCropper.plan(bounds.outWidth, bounds.outHeight)
        if (initialPlan.size == 1) {
            val upload = prepareUpload(bytes, mimeType)
            return listOf(
                OverlayUpload(
                    initialPlan.single(),
                    bounds.outWidth,
                    bounds.outHeight,
                    upload.bytes,
                    upload.mimeType,
                ),
            )
        }

        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw TranslationException.CorruptPage("not a decodable image")
        } catch (e: OutOfMemoryError) {
            throw TranslationException.CorruptPage("page too large to crop", e)
        }
        return try {
            val pageWidth = bitmap.width
            val pageHeight = bitmap.height
            TallPageCropper.plan(pageWidth, pageHeight).map { crop ->
                val cropBitmap = Bitmap.createBitmap(bitmap, 0, crop.top, pageWidth, crop.height)
                try {
                    val output = ByteArrayOutputStream()
                    if (!cropBitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, output)) {
                        throw TranslationException.CorruptPage("could not encode page crop")
                    }
                    val upload = prepareUpload(output.toByteArray(), "image/jpeg")
                    OverlayUpload(crop, pageWidth, pageHeight, upload.bytes, upload.mimeType)
                } finally {
                    if (cropBitmap !== bitmap) cropBitmap.recycle()
                }
            }
        } catch (e: OutOfMemoryError) {
            throw TranslationException.CorruptPage("page too large to crop", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun recoveryCropFor(region: DetectedTextRegion, imageWidth: Int, imageHeight: Int): PixelRect {
        val imageBounds = PixelRect(0, 0, imageWidth, imageHeight)
        val horizontalPadding = max(MIN_RECOVERY_CROP_PADDING, region.bounds.width / 2)
        val verticalPadding = max(MIN_RECOVERY_CROP_PADDING, region.bounds.height)
        return region.bounds.expandedWithin(horizontalPadding, verticalPadding, imageBounds)
    }

    private fun encodeCrop(bitmap: Bitmap, crop: PixelRect): ByteArray {
        val cropBitmap = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        return try {
            val output = ByteArrayOutputStream()
            if (!cropBitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, output)) {
                throw IllegalStateException("Could not encode targeted recovery crop")
            }
            output.toByteArray()
        } finally {
            if (cropBitmap !== bitmap) cropBitmap.recycle()
        }
    }

    // endregion

    // region Output

    private fun UniFile.isPage() = isFile && hasPageExtension(name)

    private fun hasPageExtension(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension in TranslationOptions.PAGE_EXTENSIONS
    }

    /**
     * Returns the indexes of pages that are already fully written in [stagingDir].
     * Leftover `.tmp` files and empty files from an interrupted run are deleted.
     */
    private fun collectFinishedPages(stagingDir: UniFile, pageCount: Int): Set<Int> {
        val finished = mutableSetOf<Int>()
        for (file in stagingDir.listFiles().orEmpty()) {
            val name = file.name ?: continue
            when {
                name.endsWith(TMP_SUFFIX) -> file.delete()
                file.isPage() -> {
                    val index = name.substringBeforeLast('.').toIntOrNull()?.minus(1)
                    if (file.length() > 0 && index != null && index in 0 until pageCount) {
                        finished += index
                    } else {
                        file.delete()
                    }
                }
            }
        }
        return finished
    }

    /** Writes a page to `NNN.<ext>.tmp` and renames it to `NNN.<ext>` only after the write completed. */
    private fun writePage(stagingDir: UniFile, index: Int, indexWidth: Int, image: TranslatedImage) {
        val finalName = "${(index + 1).toString().padStart(indexWidth, '0')}.${image.extension}"
        val tmpName = finalName + TMP_SUFFIX

        try {
            stagingDir.findFile(tmpName)?.delete()
            val tmpFile = stagingDir.createFile(tmpName) ?: throw IOException("Could not create temp file")
            try {
                tmpFile.openOutputStream().use { it.write(image.bytes) }
                if (tmpFile.length() != image.bytes.size.toLong()) throw IOException("Incomplete write")
                stagingDir.findFile(finalName)?.delete()
                if (!tmpFile.renameTo(finalName)) throw IOException("Could not rename temp file")
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            }
        } catch (e: IOException) {
            throw TranslationException.StorageWriteFailed(e)
        }
    }

    /** Renames the staging folder to the final chapter folder, which is when the Local source first sees it. */
    private fun publishChapter(seriesDir: UniFile, stagingDir: UniFile, chapterFolder: String) {
        // An empty or broken folder with the final name would block the rename.
        seriesDir.findFile(chapterFolder)?.delete()
        if (!stagingDir.renameTo(chapterFolder)) {
            throw TranslationException.StorageWriteFailed(IOException("Could not rename chapter folder"))
        }
    }

    /** Copies the manga cover as `cover.jpg` for a newly created series folder. Best effort. */
    private fun copyCover(seriesDir: UniFile, manga: Manga) {
        try {
            val cover = coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() }
                ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
                ?: return
            val target = seriesDir.createFile(COVER_NAME) ?: return
            cover.inputStream().use { input ->
                target.openOutputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not copy cover for translated series" }
        }
    }

    // endregion

    private sealed interface Outcome {
        class Completed(
            val seriesName: String,
            val alreadyTranslated: Boolean,
            val coverageWarningPages: Int = 0,
        ) : Outcome

        class Partial(
            val translated: Int,
            val total: Int,
            val failures: List<TranslationException>,
            val coverageWarningPages: Int = 0,
        ) : Outcome
    }

    companion object {
        private const val TAG = "Translation"
        private const val KEY_CHAPTER_ID = "chapter_id"

        private const val TMP_SUFFIX = ".tmp"
        private const val COVER_NAME = "cover.jpg"
        private const val MIN_INDEX_WIDTH = 3

        private const val MAX_PARALLEL_PAGES = 2
        private const val MAX_RATE_LIMIT_FAILURES = 3
        private const val MAX_AUTO_RETRY_ROUNDS = 3
        private const val AUTO_RETRY_DELAY_MILLIS = 15_000L
        private const val MAX_OFFLINE_ATTEMPTS = 5
        private const val MAX_RECOVERY_CALLS_PER_PAGE = 6
        private const val MAX_RECOVERY_TEXT_LENGTH = 500
        private const val MIN_RECOVERY_CROP_PADDING = 48

        private const val MAX_UPLOAD_SIDE = 2048
        private const val MAX_UPLOAD_BYTES = 6 * 1024 * 1024
        private const val UPLOAD_JPEG_QUALITY = 92

        // Translated pages usually come back as PNG, which is far larger than the typical JPEG source.
        private const val ESTIMATED_BYTES_PER_PAGE = 3L * 1024 * 1024
        private const val ESTIMATED_OVERLAY_BYTES_PER_PAGE = 3L * 1024 * 1024 / 2
        private const val STORAGE_HEADROOM_BYTES = 32L * 1024 * 1024

        private fun isAutoRetryable(e: TranslationException) = e is TranslationException.UnreadableAnswer ||
            e is TranslationException.NoImageReturned ||
            e is TranslationException.ServerError ||
            e is TranslationException.NetworkError

        /** Process wide: translations run one chapter at a time. */
        private val queueLock = Mutex()

        private fun uniqueName(chapterId: Long) = "$TAG-$chapterId"

        /**
         * Queues the translation of a downloaded chapter. Does nothing if the same chapter is already queued or
         * running. Requires a network connection; WorkManager waits for one. With [onlyWhenIdle] it also waits for a
         * charger and an unmetered network, which suits long queues left running overnight.
         */
        fun start(context: Context, chapterId: Long, onlyWhenIdle: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TranslationWorker>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_CHAPTER_ID to chapterId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (onlyWhenIdle) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresCharging(onlyWhenIdle)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            context.workManager.enqueueUniqueWork(uniqueName(chapterId), ExistingWorkPolicy.KEEP, request)
        }

        /** Emits whenever a translation job is queued, runs, finishes or fails. Used to refresh the chapter list. */
        fun workInfos(context: Context) = context.workManager.getWorkInfosByTagFlow(TAG)

        fun stop(context: Context, chapterId: Long) {
            context.workManager.cancelUniqueWork(uniqueName(chapterId))
        }

        fun stopAll(context: Context) {
            context.workManager.cancelAllWorkByTag(TAG)
        }
    }
}
