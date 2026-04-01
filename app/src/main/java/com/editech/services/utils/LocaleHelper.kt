package com.editech.services.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Helper to persist and apply the user's chosen language across the app.
 *
 * Usage:
 *   - Call [applyLocale] in every Activity's [attachBaseContext].
 *   - Call [setLocale] to change the language and restart the activity.
 *   - Call [getSavedLocale] to know which language is currently active.
 */
object LocaleHelper {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "language"
    const val LANG_EN = "en"
    const val LANG_ES = "es"
    const val LANG_SYSTEM = ""   // empty = follow system

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Wraps the base context with the saved locale — call from attachBaseContext(). */
    fun applyLocale(context: Context): Context {
        val lang = getSavedLocale(context)
        return if (lang.isEmpty()) context else wrap(context, Locale(lang))
    }

    /** Persists the chosen language code ("en", "es", or "" for system). */
    fun setLocale(context: Context, langCode: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, langCode).apply()
    }

    /** Returns the persisted language code ("en", "es", or ""). */
    fun getSavedLocale(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wrap(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
