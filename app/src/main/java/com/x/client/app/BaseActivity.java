package com.x.client.app;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 全部页面 Activity 的公共基类。
 * <p>
 * 职责：
 * <ul>
 *   <li>多调色板运行时应用（赛博极光 / 钛金极简 / 瑞士暗盾）；</li>
 *   <li>异形屏全屏占满模式统一配置（消除横屏短边黑色遮罩，API 28 SHORT_EDGES，API 30+ ALWAYS）；</li>
 *   <li>全面屏系统栏透明化与状态栏/导航栏亮暗图标动态适配；</li>
 *   <li>统一通过 WindowInsets 分发处理挖孔屏、瀑布屏曲面、横屏圆角防裁切边距及电视 overscan 安全边距。</li>
 * </ul>
 */
public abstract class BaseActivity extends AppCompatActivity {

    private int appliedPalette = -1;
    private String appliedLanguage = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyPaletteTo(this);
        appliedPalette = new Preferences(this).getPalette();
        appliedLanguage = new Preferences(this).getLanguage();
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
    }

    private void configureEdgeToEdge() {
        // 异形屏全屏占满模式（API 28 SHORT_EDGES，API 30+ ALWAYS）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindow().setAttributes(lp);
        }

        // 开启全面屏延伸与透明系统栏
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        // API 29+ 禁用系统强制对比度蒙层
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        updateSystemBarAppearance();
    }

    private void updateSystemBarAppearance() {
        boolean isDark = ThemeManager.isDark(this);

        // API < 26 不支持深色导航栏图标，浅色模式下设置半透明遮罩保证白色虚拟按键可见；API >= 26 全透明
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        } else {
            getWindow().setNavigationBarColor(isDark ? Color.TRANSPARENT : 0x80000000);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDark);
            controller.setAppearanceLightNavigationBars(!isDark);
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applyWindowInsetsToContent();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        applyWindowInsetsToContent();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        applyWindowInsetsToContent();
    }

    private void applyWindowInsetsToContent() {
        View contentView = findViewById(android.R.id.content);
        if (contentView == null) {
            return;
        }

        boolean isTv = DeviceUtils.isTv(this);
        float density = getResources().getDisplayMetrics().density;
        int tvSafeH = isTv ? (int) (32 * density) : 0;
        int tvSafeV = isTv ? (int) (20 * density) : 0;

        // TV 设备预设 overscan 安全边距
        if (isTv) {
            contentView.setPadding(tvSafeH, tvSafeV, tvSafeH, tvSafeV);
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            DisplayCutoutCompat cutout = insets.getDisplayCutout();

            int cutoutLeft = 0;
            int cutoutTop = 0;
            int cutoutRight = 0;
            int cutoutBottom = 0;
            int wfLeft = 0;
            int wfTop = 0;
            int wfRight = 0;
            int wfBottom = 0;

            if (cutout != null) {
                cutoutLeft = cutout.getSafeInsetLeft();
                cutoutTop = cutout.getSafeInsetTop();
                cutoutRight = cutout.getSafeInsetRight();
                cutoutBottom = cutout.getSafeInsetBottom();

                Insets wf = cutout.getWaterfallInsets();
                if (wf != null) {
                    wfLeft = wf.left;
                    wfTop = wf.top;
                    wfRight = wf.right;
                    wfBottom = wf.bottom;
                }
            }

            boolean isLandscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int cornerSafeH = isLandscape ? (int) (16 * density) : 0;

            int paddingLeft = Math.max(bars.left, cutoutLeft);
            paddingLeft = Math.max(paddingLeft, wfLeft);
            paddingLeft = Math.max(paddingLeft, cornerSafeH);
            paddingLeft = Math.max(paddingLeft, tvSafeH);

            int paddingRight = Math.max(bars.right, cutoutRight);
            paddingRight = Math.max(paddingRight, wfRight);
            paddingRight = Math.max(paddingRight, cornerSafeH);
            paddingRight = Math.max(paddingRight, tvSafeH);

            int paddingTop = Math.max(bars.top, cutoutTop);
            paddingTop = Math.max(paddingTop, wfTop);
            paddingTop = Math.max(paddingTop, tvSafeV);

            int paddingBottom = Math.max(bars.bottom, cutoutBottom);
            paddingBottom = Math.max(paddingBottom, wfBottom);
            paddingBottom = Math.max(paddingBottom, ime.bottom);
            paddingBottom = Math.max(paddingBottom, tvSafeV);

            v.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            return WindowInsetsCompat.CONSUMED;
        });

        ViewCompat.requestApplyInsets(contentView);
        updateSystemBarAppearance();
    }

    @Override
    protected void onResume() {
        super.onResume();
        int savedPalette = new Preferences(this).getPalette();
        String savedLanguage = new Preferences(this).getLanguage();
        if (savedPalette != appliedPalette || !savedLanguage.equals(appliedLanguage)) {
            appliedPalette = savedPalette;
            appliedLanguage = savedLanguage;
            recreate();
        }
    }
}
