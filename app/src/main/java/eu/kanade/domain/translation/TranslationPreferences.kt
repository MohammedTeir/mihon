package eu.kanade.domain.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun enabled() = preferenceStore.getBoolean("pref_translation_enabled", false)

    // Private key: filtered out of backups by PreferenceBackupCreator.
    fun apiKey() = preferenceStore.getString(Preference.privateKey("pref_nano_banana_api_key"), "")

    // Language name sent to the model, e.g. "English", "Arabic".
    fun targetLanguage() = preferenceStore.getString(
        "pref_translation_target_lang",
        TranslationOptions.DEFAULT_LANGUAGE,
    )

    fun model() = preferenceStore.getString("pref_translation_model", TranslationOptions.DEFAULT_MODEL)
}
