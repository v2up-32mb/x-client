package com.x.client.app;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 全部页面 Activity 的公共基类。
 * <p>
 * 职责：多调色板（赛博极光 / 钛金极简 / 瑞士暗盾）的运行时应用。
 * <ul>
 *   <li>onCreate：在 super 之前调用 ThemeManager.applyPaletteTo 经 setTheme 选定主题样式，
 *       使 3 套配色在全部页面即时生效；</li>
 *   <li>onResume：检测调色板是否已在设置页被更改（返回栈中被覆盖的 Activity 不会收到
 *       重建信号），不一致则自动 recreate 应用新配色。</li>
 * </ul>
 * 明暗模式（跟随系统/亮色/暗色）由 AppCompatDelegate 全局承接，
 * 设置页切换后 AppCompatActivity 会自动重建，此处无需处理。
 */
public abstract class BaseActivity extends AppCompatActivity {

    private int appliedPalette = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyPaletteTo(this);
        appliedPalette = new Preferences(this).getPalette();
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int saved = new Preferences(this).getPalette();
        if (saved != appliedPalette) {
            appliedPalette = saved;
            recreate();
        }
    }
}