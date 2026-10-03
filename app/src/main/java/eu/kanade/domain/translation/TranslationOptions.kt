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
