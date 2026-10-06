package eu.kanade.domain.translation

/**
 * Constants for the chapter translation feature.
 *
 * Model ids are taken from the Gemini API "Nano Banana" docs (https://ai.google.dev/gemini-api/docs/nanobanana).
 * They change over time, so keep them all in this one place.
 */
object TranslationOptions {

    const val MODEL_FLASH_LITE_IMAGE = "gemini-3.1-flash-lite-image"
    const val MODEL_FLASH_IMAGE = "gemini-3.1-flash-image"
    const val MODEL_PRO_IMAGE = "gemini-3-pro-image"
    const val MODEL_LEGACY_FLASH_IMAGE = "gemini-2.5-flash-image"

    const val DEFAULT_MODEL = MODEL_FLASH_IMAGE

    /** Model id -> display name. */
    val MODELS: Map<String, String> = linkedMapOf(
        MODEL_FLASH_LITE_IMAGE to "Nano Banana 3.1 Flash Lite ($MODEL_FLASH_LITE_IMAGE)",
        MODEL_FLASH_IMAGE to "Nano Banana 3.1 Flash ($MODEL_FLASH_IMAGE)",
        MODEL_PRO_IMAGE to "Nano Banana 3 Pro ($MODEL_PRO_IMAGE)",
        MODEL_LEGACY_FLASH_IMAGE to "Nano Banana 2.5 Flash ($MODEL_LEGACY_FLASH_IMAGE)",
    )

    const val MODE_REDRAW = "redraw"
    const val MODE_OVERLAY = "overlay"
    const val MODE_OFFLINE = "offline"

    /** Mode id -> string resource is resolved in the settings screen. */
    val MODES: List<String> = listOf(MODE_REDRAW, MODE_OVERLAY, MODE_OFFLINE)

    /** Offline mode: language of the pages (BCP 47 tag -> display name). One text recognizer per script. */
    const val DEFAULT_SOURCE_LANGUAGE = "en"

    val SOURCE_LANGUAGES: Map<String, String> = linkedMapOf(
        "en" to "English",
        "ko" to "Korean",
        "ja" to "Japanese",
        "zh" to "Chinese",
    )

    /** Offline mode has no model for these targets. */
    private val OFFLINE_UNSUPPORTED = setOf("Chinese (Traditional)", "Serbian")

    private val LANGUAGE_TAGS: Map<String, String> = mapOf(
        "English" to "en",
        "Arabic" to "ar",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Portuguese" to "pt",
        "Italian" to "it",
        "Russian" to "ru",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Chinese (Simplified)" to "zh",
        "Indonesian" to "id",
        "Vietnamese" to "vi",
        "Thai" to "th",
        "Turkish" to "tr",
        "Polish" to "pl",
        "Dutch" to "nl",
        "Ukrainian" to "uk",
        "Czech" to "cs",
        "Romanian" to "ro",
        "Hungarian" to "hu",
        "Greek" to "el",
        "Swedish" to "sv",
        "Norwegian" to "no",
        "Danish" to "da",
        "Finnish" to "fi",
        "Hebrew" to "he",
        "Persian" to "fa",
        "Hindi" to "hi",
        "Bengali" to "bn",
        "Urdu" to "ur",
        "Tamil" to "ta",
        "Malay" to "ms",
        "Filipino" to "tl",
        "Bulgarian" to "bg",
        "Croatian" to "hr",
        "Catalan" to "ca",
    )

    /** BCP 47 tag for the offline translator, or null when the language is not available offline. */
    fun offlineTag(language: String): String? =
        if (language in OFFLINE_UNSUPPORTED) null else LANGUAGE_TAGS[language]

    /**
     * Text models for overlay mode. Which of them are free depends on Google's current free tier, so the list
     * is only a suggestion. Verify in Google AI Studio.
     */
    const val DEFAULT_TEXT_MODEL = "gemini-3.5-flash-lite"

    val TEXT_MODELS: Map<String, String> = linkedMapOf(
        "gemini-3.8-flash" to "Gemini 3.8 Flash",
        "gemini-3.6-flash" to "Gemini 3.6 Flash",
        "gemini-3.5-flash-lite" to "Gemini 3.5 Flash Lite",
        "gemini-3.1-flash-lite" to "Gemini 3.1 Flash Lite",
    )

    /** Pause between overlay requests, in seconds. */
    val REQUEST_DELAYS: List<Int> = listOf(0, 3, 6, 12)

    const val DEFAULT_LANGUAGE = "English"

    /** Image file extensions (lowercase) that count as pages of a downloaded chapter. */
    val PAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp")

    /**
     * Languages offered as translation targets. The stored value is the English language name,
     * which is inserted into the model prompt as-is.
     */
    val LANGUAGES: List<String> = listOf(
        "English",
        "Arabic",
        "Spanish",
        "French",
        "German",
        "Portuguese",
        "Italian",
        "Russian",
        "Japanese",
        "Korean",
        "Chinese (Simplified)",
        "Chinese (Traditional)",
        "Indonesian",
        "Vietnamese",
        "Thai",
        "Turkish",
        "Polish",
        "Dutch",
        "Ukrainian",
        "Czech",
        "Romanian",
        "Hungarian",
        "Greek",
        "Swedish",
        "Norwegian",
        "Danish",
        "Finnish",
        "Hebrew",
        "Persian",
        "Hindi",
        "Bengali",
        "Urdu",
        "Tamil",
        "Malay",
        "Filipino",
        "Bulgarian",
        "Serbian",
        "Croatian",
        "Catalan",
    )
}
