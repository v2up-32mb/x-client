package com.x.client.app;

import android.app.Application;

public class XclientApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applySavedMode(this);
        // 将 Go 运行时本地时区与 Android 系统时区对齐（gomobile 默认 UTC），
        // 使运行日志时间戳显示为系统时区。
        try {
            xclient.Xclient.setTimeZone(java.util.TimeZone.getDefault().getID());
        } catch (Throwable error) {
            android.util.Log.w("XclientApplication", "Failed to sync system timezone", error);
        }
    }
}
