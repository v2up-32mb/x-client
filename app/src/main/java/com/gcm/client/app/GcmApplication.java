package com.gcm.client.app;

import android.app.Application;

public class GcmApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applySavedMode(this);
    }
}
