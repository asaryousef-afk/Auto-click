package com.smarttouch.ai

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Lets the user override the app's own display language (Arabic/English),
 * independently of the device's system language, without needing AppCompat's
 * per-app language APIs (which need API 33+ or an AppCompatActivity - this
 * app uses plain ComponentActivity and supports minSdk 26).
 *
 * "null" language means "follow the system language" (the default).
 */
object LocaleHelper {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LANGUAGE = "app_language"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** "ar", "en", or null for system default. */
    fun getLanguage(context: Context): String? = prefs(context).getString(KEY_LANGUAGE, null)

    fun setLanguage(context: Context, languageCode: String?) {
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /** Call from Activity.attachBaseContext(). Wraps the given context with the
     * saved language override, or returns it unchanged if following the system. */
    fun wrap(context: Context): Context {
        val language = getLanguage(context) ?: return context
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
