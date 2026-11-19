package com.x.client.app;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * 设备特性与运行环境判定工具类。
 * <p>
 * 提供电视（Android TV / 国内电视盒子 / 带遥控器设备）模式判定与摄像头硬件可用性检查，
 * 协助 UI 实施大屏自适应、遥控器交互适配与优雅降级。
 */
public final class DeviceUtils {

    private DeviceUtils() {
    }

    private static Boolean cachedIsTv = null;

    /**
     * 判断当前设备是否为电视或电视盒子（Android TV / MIUI TV / 鸿蒙智慧屏 / 各类盒子）。
     */
    public static boolean isTv(Context context) {
        if (cachedIsTv != null) {
            return cachedIsTv;
        }
        if (context == null) {
            return false;
        }

        Context appCtx = context.getApplicationContext();

        // 1. 通过 UiModeManager 判定
        UiModeManager uiModeManager = (UiModeManager) appCtx.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            cachedIsTv = true;
            return true;
        }

        // 2. 检查系统 Leanback / Television 特性
        PackageManager pm = appCtx.getPackageManager();
        if (pm != null) {
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature("android.hardware.type.television")) {
                cachedIsTv = true;
                return true;
            }

            // 3. 部分国产电视/盒子没有标准声明，但无触摸屏且为横屏
            boolean hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            boolean isLandscape = appCtx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            if (!hasTouchscreen && isLandscape) {
                cachedIsTv = true;
                return true;
            }
        }

        cachedIsTv = false;
        return false;
    }

    /**
     * 检查设备是否具备可用摄像头（TV 与部分盒子无相机硬件）。
     */
    public static boolean hasCamera(Context context) {
        if (context == null) {
            return false;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return false;
        }
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }
}
