package com.x.client.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

final class ThemeManager {
    private ThemeManager() {
    }

    static void applySavedMode(Context context) {
        applyMode(new Preferences(context).getThemeMode());
    }

    static void setMode(Context context, int mode) {
        new Preferences(context).setThemeMode(mode);
        applyMode(mode);
    }

    /** 判断当前上下文是否为暗色模式（夜间模式）。单一真理源委派给 Preferences.isNightMode()。 */
    static boolean isDark(Context context) {
        return context != null && new Preferences(context).isNightMode();
    }

    /** 当前调色板对应的主题样式资源。如果未显式设置，则暗色返回赛博极光，亮色返回钛金极简。 */
    static int themeRes(Context context) {
        boolean dark = isDark(context);
        Preferences prefs = new Preferences(context);
        switch (prefs.getPalette(dark)) {
            case Preferences.PALETTE_TITANIUM:
                return R.style.Theme_XClient_Titanium;
            case Preferences.PALETTE_SHIELD:
                return R.style.Theme_XClient_Shield;
            default:
                return R.style.Theme_XClient;
        }
    }

    /** 在 Activity 的 setContentView 之前应用当前调色板主题。 */
    static void applyPaletteTo(Activity activity) {
        activity.setTheme(themeRes(activity));
    }

    static void setPalette(Context context, int palette) {
        new Preferences(context).setPalette(palette, isDark(context));
    }

    private static void applyMode(int mode) {
        switch (mode) {
            case Preferences.THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case Preferences.THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
