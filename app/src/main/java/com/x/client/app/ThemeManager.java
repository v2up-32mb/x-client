package com.x.client.app;

import android.content.Context;

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
