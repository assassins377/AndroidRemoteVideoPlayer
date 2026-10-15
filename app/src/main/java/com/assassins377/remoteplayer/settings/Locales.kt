package com.assassins377.remoteplayer.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Per-app language: LocaleManager on Android 13+, configuration override on older versions. */
object Locales {
    fun apply(ctx: Context, lang: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (lang.tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(lang.tag)
        }
    }

    /** For API < 33: wrap the activity base context. */
    fun wrap(base: Context, lang: AppLanguage): Context {
        if (Build.VERSION.SDK_INT >= 33 || lang.tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(lang.tag))
        return base.createConfigurationContext(cfg)
    }
}
