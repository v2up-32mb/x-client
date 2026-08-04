package com.x.client.app;

import android.app.Application;

public class XclientApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applySavedMode(this);
    }
}
