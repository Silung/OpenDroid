package dev.opendroid.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocale {
    private const val PREFS_NAME = "opendroid_app_prefs"
    private const val KEY_LOCALE_TAG = "app_locale_tag"

    const val TAG_ZH = "zh"
    const val TAG_EN = "en"

    fun getStoredTag(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_LOCALE_TAG, null) ?: return TAG_ZH
        return raw.takeIf { it == TAG_ZH || it == TAG_EN } ?: TAG_ZH
    }

    private fun setStoredTag(context: Context, tag: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE_TAG, tag)
            .apply()
    }

    /** 在 [Application.onCreate] 中尽早调用，以恢复用户所选语言。 */
    fun applyPersisted(applicationContext: Context) {
        val tag = getStoredTag(applicationContext)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun setLocale(context: Context, tag: String) {
        require(tag == TAG_ZH || tag == TAG_EN)
        setStoredTag(context, tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
