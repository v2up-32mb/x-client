/*
 ============================================================================
 Name        : TvFocusHelper.java
 Description : Focus highlight and D-pad interaction utilities for Android TV
 ============================================================================
 */

package com.x.client.app;

import android.view.View;

/**
 * 电视大屏与遥控器 D-pad 焦点高亮及交互增强辅助类。
 */
public final class TvFocusHelper {

    private TvFocusHelper() {}

    /**
     * 为交互控件附加平滑微缩放与高光阴影焦点动效。
     *
     * @param view 需要响应焦点反馈的视图
     */
    public static void applyFocusFeedback(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(4f).setDuration(100).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(100).start();
            }
        });
    }

    /**
     * 批量为交互控件附加焦点动效。
     *
     * @param views 视图数组
     */
    public static void applyFocusFeedback(View... views) {
        if (views == null) return;
        for (View v : views) {
            applyFocusFeedback(v);
        }
    }
}
