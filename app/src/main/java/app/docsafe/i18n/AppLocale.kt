package app.docsafe.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Supported UI languages: BCP-47 tag → endonym (name in that language). */
object AppLocales {
    val supported: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ru" to "Русский",
        "fr" to "Français",
        "de" to "Deutsch",
        "zh" to "中文",
        "ja" to "日本語",
        "hy" to "Հայերեն",
        "ka" to "ქართული",
        "fa" to "فارسی",
        "it" to "Italiano",
        "es" to "Español",
        "pt" to "Português",
        "pl" to "Polski",
        "bg" to "Български",
        "sv" to "Svenska",
        "fi" to "Suomi",
        "nb" to "Norsk",
    )

    fun displayName(tag: String?): String? = tag?.let { t -> supported.firstOrNull { it.first == t }?.second }
}

/** Stores the user's chosen UI language (null = follow the system locale). */
class LocalePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("docsafe_settings", Context.MODE_PRIVATE)

    var languageTag: String?
        get() = prefs.getString(KEY, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()

    private companion object {
        const val KEY = "language_tag"
    }
}

/**
 * Returns a context whose resources use the user's chosen language, or the original context if
 * none is set (so the system locale is used). Applied in `MainActivity.attachBaseContext`, this
 * localizes the whole app across all API levels.
 */
fun Context.withAppLocale(): Context {
    val tag = LocalePrefs(this).languageTag ?: return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}
