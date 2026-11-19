package com.x.client.app;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * 全局应用语言管理器。
 * <p>
 * 职责：
 * <ul>
 *   <li>封装基于 AndroidX {@link AppCompatDelegate#setApplicationLocales} 的多语言切换能力；</li>
 *   <li>支持跟随系统（未命中则兜底英文）、英语、简体中文、繁体中文、俄语；</li>
 *   <li>持久化语言选项至 {@link Preferences}，并在应用启动和切换时即时生效。</li>
 * </ul>
 */
public final class LanguageManager {
    private LanguageManager() {
    }

    public static final String LANGUAGE_SYSTEM = Preferences.LANGUAGE_SYSTEM;
    public static final String LANGUAGE_EN = Preferences.LANGUAGE_EN;
    public static final String LANGUAGE_ZH_CN = Preferences.LANGUAGE_ZH_CN;
    public static final String LANGUAGE_ZH_TW = Preferences.LANGUAGE_ZH_TW;
    public static final String LANGUAGE_RU = Preferences.LANGUAGE_RU;

    public static final String[] SUPPORTED_LANGUAGES = {
            LANGUAGE_SYSTEM,
            LANGUAGE_EN,
            LANGUAGE_ZH_CN,
            LANGUAGE_ZH_TW,
            LANGUAGE_RU
    };

    /**
     * 在 Application.onCreate 中调用，恢复用户上次选择的语言配置。
     */
    public static void applySavedLanguage(Context context) {
        String lang = new Preferences(context).getLanguage();
        applyLanguage(lang);
    }

    /**
     * 设置并即时应用新语言。
     */
    public static void setLanguage(Context context, String language) {
        new Preferences(context).setLanguage(language);
        applyLanguage(language);
    }

    public static void applyLanguage(String language) {
        if (LANGUAGE_SYSTEM.equals(language) || language == null || language.isEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
        }
    }
}
