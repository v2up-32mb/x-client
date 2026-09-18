/*
 ============================================================================
 Name        : SettingsActivity.java
 Author      : Claude Code
 Description : Global Settings Activity
 ============================================================================
 */

package com.x.client.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;

import xclient.Xclient;

public class SettingsActivity extends BaseActivity {
    private Preferences prefs;

    private MaterialToolbar toolbar;
    private CheckBox checkbox_global;
    private LinearLayout button_apps;
    private TextView text_apps_summary;
    private TextInputLayout til_socks_port;
    private EditText edittext_socks_port;
    private CheckBox checkbox_bypass_private;
    private CheckBox checkbox_bypass_geoip_cn;
    private CheckBox checkbox_bypass_geosite_cn;
    private TextInputLayout til_bypass_rules;
    private EditText edittext_bypass_rules;
    private TextInputLayout til_ech_dns;
    private EditText edittext_ech_dns;
    private TextInputLayout til_ech_domain;
    private EditText edittext_ech_domain;
    private CheckBox checkbox_enable_dns_warmup;
    private CheckBox checkbox_show_server_addr;
    private Spinner spinner_log_level;
    private TextView text_log_level_summary;
    private Spinner spinner_theme_mode;
    private TextView text_theme_mode_summary;
    private Spinner spinner_palette;
    private TextView text_palette_summary;
    private Button btn_save;

    // 分组内容容器：VPN 运行中整体置灰（alpha）；"通用"组为实时生效项，不受限
    private View group_network;
    private View group_bypass;
    private View group_ech;

    private static final String[] LOG_LEVEL_VALUES = {
            Preferences.LOG_LEVEL_DEBUG,
            Preferences.LOG_LEVEL_INFO,
            Preferences.LOG_LEVEL_WARN,
            Preferences.LOG_LEVEL_ERROR
    };

    // 明暗模式下拉框顺序：跟随系统/亮色/暗色 → Preferences.THEME_*
    private static final int[] THEME_MODE_VALUES = {
            Preferences.THEME_SYSTEM,
            Preferences.THEME_LIGHT,
            Preferences.THEME_DARK
    };
    private static final int[] THEME_MODE_LABEL_RES = {
            R.string.theme_system,
            R.string.theme_light,
            R.string.theme_dark
    };

    // 色彩方案下拉框顺序：赛博极光/钛金极简/瑞士暗盾 → Preferences.PALETTE_*
    private static final int[] PALETTE_VALUES = {
            Preferences.PALETTE_AURORA,
            Preferences.PALETTE_TITANIUM,
            Preferences.PALETTE_SHIELD
    };
    private static final int[] PALETTE_LABEL_RES = {
            R.string.theme_palette_aurora,
            R.string.theme_palette_titanium,
            R.string.theme_palette_shield
    };

    // 日志等级下拉框显示的本地化文案资源，与 LOG_LEVEL_VALUES 一一对应
    private static final int[] LOG_LEVEL_LABEL_RES = {
            R.string.log_level_debug,
            R.string.log_level_info,
            R.string.log_level_warn,
            R.string.log_level_error
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);
        setContentView(R.layout.activity_settings);

        // M3 Toolbar：标题 + 返回导航（替代旧的屏内自绘标题）
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.settings_title));
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        // 初始化控件
        checkbox_global = findViewById(R.id.checkbox_global);
        button_apps = findViewById(R.id.button_apps);
        text_apps_summary = findViewById(R.id.text_apps_summary);
        til_socks_port = findViewById(R.id.til_socks_port);
        edittext_socks_port = findViewById(R.id.edittext_socks_port);
        checkbox_bypass_private = findViewById(R.id.checkbox_bypass_private);
        checkbox_bypass_geoip_cn = findViewById(R.id.checkbox_bypass_geoip_cn);
        checkbox_bypass_geosite_cn = findViewById(R.id.checkbox_bypass_geosite_cn);
        til_bypass_rules = findViewById(R.id.til_bypass_rules);
        edittext_bypass_rules = findViewById(R.id.edittext_bypass_rules);
        til_ech_dns = findViewById(R.id.til_ech_dns);
        edittext_ech_dns = findViewById(R.id.edittext_ech_dns);
        til_ech_domain = findViewById(R.id.til_ech_domain);
        edittext_ech_domain = findViewById(R.id.edittext_ech_domain);
        checkbox_enable_dns_warmup = findViewById(R.id.checkbox_enable_dns_warmup);
        checkbox_show_server_addr = findViewById(R.id.checkbox_show_server_addr);
        spinner_log_level = findViewById(R.id.spinner_log_level);
        text_log_level_summary = findViewById(R.id.text_log_level_summary);
        spinner_theme_mode = findViewById(R.id.spinner_theme_mode);
        text_theme_mode_summary = findViewById(R.id.text_theme_mode_summary);
        spinner_palette = findViewById(R.id.spinner_palette);
        text_palette_summary = findViewById(R.id.text_palette_summary);
        btn_save = findViewById(R.id.btn_save);
        group_network = findViewById(R.id.group_network);
        group_bypass = findViewById(R.id.group_bypass);
        group_ech = findViewById(R.id.group_ech);

        // 日志等级选择器：显示本地化文案，值与 Preferences LOG_LEVEL_* 一一对应
        ArrayAdapter<String> logLevelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, logLevelLabels());
        logLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_log_level.setAdapter(logLevelAdapter);

        // 明暗模式 / 色彩方案：实时生效（选择即应用，无需保存按钮），VPN 运行时同样可调
        ArrayAdapter<String> themeModeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, themeLabels());
        themeModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_theme_mode.setAdapter(themeModeAdapter);

        ArrayAdapter<String> paletteAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, paletteLabels());
        paletteAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_palette.setAdapter(paletteAdapter);

        spinner_theme_mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 初始定位回调与当前值一致时跳过，避免误触发实时应用
                int mode = THEME_MODE_VALUES[position];
                if (mode == prefs.getThemeMode()) {
                    return;
                }
                ThemeManager.setMode(SettingsActivity.this, mode);
                text_theme_mode_summary.setText(getString(R.string.settings_theme_mode_summary,
                        getString(THEME_MODE_LABEL_RES[position])));
                // 明暗切换由 AppCompatDelegate 自动重建全部 Activity，无需手动 recreate
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinner_palette.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int palette = PALETTE_VALUES[position];
                if (palette == prefs.getPalette()) {
                    return;
                }
                ThemeManager.setPalette(SettingsActivity.this, palette);
                text_palette_summary.setText(getString(R.string.settings_theme_palette_summary,
                        getString(PALETTE_LABEL_RES[position])));
                recreate(); // 立即以新配色重建当前页面
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 加载当前设置
        loadSettings();

        // 分应用代理入口行：跳转应用选择页（VPN 运行中会被禁用，见 loadSettings）
        button_apps.setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });

        // 设置保存按钮点击事件
        btn_save.setOnClickListener(v -> {
            if (saveSettings()) {
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private String[] logLevelLabels() {
        return labelRes(LOG_LEVEL_LABEL_RES);
    }

    private String[] themeLabels() {
        return labelRes(THEME_MODE_LABEL_RES);
    }

    private String[] paletteLabels() {
        return labelRes(PALETTE_LABEL_RES);
    }

    private String[] labelRes(int[] resIds) {
        String[] labels = new String[resIds.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(resIds[i]);
        }
        return labels;
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) {
                return i;
            }
        }
        return 0;
    }

    private void loadSettings() {
        // 加载全局设置
        checkbox_global.setChecked(prefs.getGlobal());
        int currentPort = prefs.getSocksPort();
        edittext_socks_port.setText(String.valueOf(currentPort));
        // 当前值 summary（M3 helper text 展示）
        til_socks_port.setHelperText(getString(R.string.settings_socks_port_summary, currentPort));
        checkbox_bypass_private.setChecked(prefs.getBypassPrivate());
        checkbox_bypass_geoip_cn.setChecked(prefs.getBypassGeoIpCn());
        checkbox_bypass_geosite_cn.setChecked(prefs.getBypassGeoSiteCn());
        edittext_bypass_rules.setText(prefs.getBypassRules());
        edittext_ech_dns.setText(prefs.getEchDns());
        edittext_ech_domain.setText(prefs.getEchDomain());
        checkbox_enable_dns_warmup.setChecked(prefs.getEnableDnsWarmup());

        // 分应用代理入口行 summary：当前已选应用数
        int selectedApps = prefs.getApps().size();
        text_apps_summary.setText(selectedApps > 0
                ? getString(R.string.settings_apps_row_summary, selectedApps)
                : getString(R.string.settings_apps_row_summary_none));

        // 服务器地址显示开关：仅控制列表展示，无需重启 VPN，即时生效；
        // VPN 运行时也可切换（截图时 VPN 往往处于连接状态），故不随其他全局项禁用。
        checkbox_show_server_addr.setChecked(prefs.getShowServerAddr());
        checkbox_show_server_addr.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setShowServerAddr(isChecked);
        });

        // 加载日志等级
        String logLevel = prefs.getLogLevel();
        int logLevelPosition = 1; // 默认 INFO
        for (int i = 0; i < LOG_LEVEL_VALUES.length; i++) {
            if (LOG_LEVEL_VALUES[i].equals(logLevel)) {
                logLevelPosition = i;
                break;
            }
        }
        spinner_log_level.setSelection(logLevelPosition);
        // 当前值 summary：本地化等级文案
        text_log_level_summary.setText(getString(R.string.settings_log_level_summary,
                getString(LOG_LEVEL_LABEL_RES[logLevelPosition])));

        // 明暗模式与色彩方案：定位当前值（初始 onItemSelected 回调因值与 prefs 一致被跳过）
        int themeModePosition = indexOf(THEME_MODE_VALUES, prefs.getThemeMode());
        spinner_theme_mode.setSelection(themeModePosition);
        int palettePosition = indexOf(PALETTE_VALUES, prefs.getPalette());
        spinner_palette.setSelection(palettePosition);
        text_theme_mode_summary.setText(getString(R.string.settings_theme_mode_summary,
                getString(THEME_MODE_LABEL_RES[themeModePosition])));
        text_palette_summary.setText(getString(R.string.settings_theme_palette_summary,
                getString(PALETTE_LABEL_RES[palettePosition])));

        // 检查 VPN 是否正在运行
        boolean isVpnRunning = prefs.getEnable();
        // VPN 运行时禁用所有全局设置的修改（判定规则不变），并对分组容器补置灰视觉
        applyVpnRunningState(isVpnRunning);
        if (isVpnRunning) {
            Toast.makeText(this, getString(R.string.settings_vpn_running_toast), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * VPN 运行状态对应的可用性 UI：除 setEnabled 外，对分组容器与保存按钮
     * 补 alpha 置灰视觉；VPN 未运行时恢复为 1.0f，保证可复原。
     * 外观分组（明暗模式/色彩方案）为实时生效项，不受 VPN 运行限制。
     */
    private void applyVpnRunningState(boolean vpnRunning) {
        View[] controls = {
                checkbox_global, button_apps, edittext_socks_port,
                checkbox_bypass_private, checkbox_bypass_geoip_cn, checkbox_bypass_geosite_cn,
                edittext_bypass_rules, edittext_ech_dns, edittext_ech_domain,
                checkbox_enable_dns_warmup, spinner_log_level, btn_save
        };
        for (View control : controls) {
            control.setEnabled(!vpnRunning);
        }
        float alpha = vpnRunning ? 0.5f : 1.0f;
        group_network.setAlpha(alpha);
        group_bypass.setAlpha(alpha);
        group_ech.setAlpha(alpha);
        btn_save.setAlpha(alpha);
    }

    @Override
    public void onBackPressed() {
        if (prefs.getEnable() || saveSettings()) {
            super.onBackPressed();
        }
    }

    private boolean saveSettings() {
        // 清除上一次的 inline 校验错误
        til_socks_port.setError(null);
        til_bypass_rules.setError(null);

        // 验证并保存端口
        int port = 1080;
        try {
            port = Integer.parseInt(edittext_socks_port.getText().toString().trim());
        } catch (Exception e) {
            String msg = getString(R.string.settings_error_port_format);
            til_socks_port.setError(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (port < 1024) {
            String msg = getString(R.string.settings_error_port_min);
            til_socks_port.setError(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return false;
        }

        String bypassRules = edittext_bypass_rules.getText().toString().trim();
        try {
            Xclient.validateBypassRules(bypassRules);
        } catch (Exception e) {
            String msg = getString(R.string.settings_error_bypass_rules, e.getMessage());
            til_bypass_rules.setError(msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return false;
        }

        // 保存全局设置
        prefs.setGlobal(checkbox_global.isChecked());
        prefs.setSocksPort(port);
        prefs.setBypassPrivate(checkbox_bypass_private.isChecked());
        prefs.setBypassGeoIpCn(checkbox_bypass_geoip_cn.isChecked());
        prefs.setBypassGeoSiteCn(checkbox_bypass_geosite_cn.isChecked());
        prefs.setBypassRules(bypassRules);
        prefs.setEchDns(edittext_ech_dns.getText().toString().trim());
        prefs.setEchDomain(edittext_ech_domain.getText().toString().trim());
        prefs.setEnableDnsWarmup(checkbox_enable_dns_warmup.isChecked());
        prefs.setLogLevel(LOG_LEVEL_VALUES[spinner_log_level.getSelectedItemPosition()]);

        return true;
    }
}
