/*
 ============================================================================
 Name        : SettingsActivity.java
 Description : Global Settings activity with Material 3 card container
               grouping, semantic tokens, Android TV D-pad focus feedback,
               and decoupled architecture.
 ============================================================================
 */

package com.x.client.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import xclient.Xclient;

/**
 * 全局设置页面：
 * 采用 Material 3 规范与 4 个标准 Outlined Card 分组：
 * 1. 常规基础设置 (General Settings)
 * 2. 路由绕过规则 (Routing Bypass Rules)
 * 3. 调色板主题切换预览 (Theme Palette Preview Swatches & Selected Badge)
 * 4. 关于与版本信息 (About & Version Info)
 *
 * 具备 100% 语义颜色 Token、Android TV 遥控器 D-pad 友好导航与草稿状态恢复（防重构状态丢失）。
 */
public class SettingsActivity extends BaseActivity {

    private static final String GITHUB_REPO_URL = "https://github.com/v2up-32mb/x-client";

    // 草稿状态 Bundle Key（Activity 重建 / 调色板切换时保护未提交输入）
    private static final String KEY_DRAFT_SOCKS_PORT = "draft_socks_port";
    private static final String KEY_DRAFT_GLOBAL = "draft_global";
    private static final String KEY_DRAFT_BYPASS_PRIVATE = "draft_bypass_private";
    private static final String KEY_DRAFT_BYPASS_GEOIP_CN = "draft_bypass_geoip_cn";
    private static final String KEY_DRAFT_BYPASS_GEOSITE_CN = "draft_bypass_geosite_cn";
    private static final String KEY_DRAFT_BYPASS_RULES = "draft_bypass_rules";
    private static final String KEY_DRAFT_FORCE_PROXY = "draft_force_proxy";
    private static final String KEY_DRAFT_ECH_DNS = "draft_ech_dns";
    private static final String KEY_DRAFT_ECH_DOMAIN = "draft_ech_domain";
    private static final String KEY_DRAFT_ENABLE_DNS_WARMUP = "draft_enable_dns_warmup";
    private static final String KEY_DRAFT_LOG_LEVEL_POS = "draft_log_level_pos";
    private static final String KEY_DRAFT_LATENCY_FORCE_PROXY = "draft_latency_force_proxy";

    private static final String[] LANGUAGE_VALUES = {"system", "en", "zh-CN", "zh-TW", "ru"};
    private static final int[] LANGUAGE_LABEL_RES = {
            R.string.theme_system,
            R.string.language_en,
            R.string.language_zh_cn,
            R.string.language_zh_tw,
            R.string.language_ru
    };

    private static final int[] THEME_MODE_VALUES = {0, 1, 2};
    private static final int[] THEME_MODE_LABEL_RES = {
            R.string.theme_system,
            R.string.theme_light,
            R.string.theme_dark
    };

    private static final String[] LOG_LEVEL_VALUES = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int[] LOG_LEVEL_LABEL_RES = {
            R.string.log_level_debug,
            R.string.log_level_info,
            R.string.log_level_warn,
            R.string.log_level_error
    };

    private static final int[] LATENCY_INTERVAL_VALUES = {5, 10, 30, 60};
    private static final String[] LATENCY_INTERVAL_LABELS = {"5s", "10s", "30s", "60s"};

    private static final int[] LATENCY_TIMEOUT_VALUES = {3, 5, 10, 15};
    private static final String[] LATENCY_TIMEOUT_LABELS = {"3s", "5s", "10s", "15s"};

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

    private static final int[][] PALETTE_SWATCH_RES = {
            {R.color.x_primary, R.color.x_primary_container, R.color.md_success, R.color.x_surface_container},
            {R.color.ti_primary, R.color.ti_primary_container, R.color.ti_md_success, R.color.ti_x_surface_container},
            {R.color.sh_primary, R.color.sh_primary_container, R.color.sh_md_success, R.color.sh_x_surface_container}
    };

    private Preferences prefs;
    private boolean isLoadingSettings = false;

    // View References
    private MaterialToolbar toolbar;
    private Button btnSave;

    // Card 1: 常规基础设置
    private MaterialCardView cardGeneral;
    private Spinner spinnerLanguage;
    private TextView textLanguageSummary;
    private CheckBox checkboxShowServerAddr;
    private TextInputLayout tilSocksPort;
    private TextInputEditText edittextSocksPort;
    private Spinner spinnerLogLevel;
    private TextView textLogLevelSummary;
    private TextInputLayout tilEchDns;
    private TextInputEditText edittextEchDns;
    private TextInputLayout tilEchDomain;
    private TextInputEditText edittextEchDomain;
    private CheckBox checkboxEnableDnsWarmup;
    private TextInputLayout tilLatencyUrl;
    private TextInputEditText editLatencyUrl;
    private Spinner spinnerLatencyInterval;
    private Spinner spinnerLatencyTimeout;
    private TextView textGeneralRestartHint;

    // Card 2: 路由绕过规则
    private MaterialCardView cardBypass;
    private CheckBox checkboxGlobal;
    private LinearLayout buttonApps;
    private TextView textAppsSummary;
    private CheckBox checkboxBypassPrivate;
    private CheckBox checkboxBypassGeoIpCn;
    private CheckBox checkboxBypassGeoSiteCn;
    private CheckBox checkboxLatencyForceProxy;
    private TextInputLayout tilBypassRules;
    private TextInputEditText edittextBypassRules;
    private TextInputLayout tilForceProxy;
    private TextInputEditText edittextForceProxy;
    private TextView textBypassRestartHint;

    // Card 3: 调色板主题切换预览
    private MaterialCardView cardPalette;
    private Spinner spinnerThemeMode;
    private TextView textThemeModeSummary;
    private TextView textPaletteSummary;
    private MaterialCardView paletteCardAurora;
    private LinearLayout paletteSwatchesAurora;
    private ImageView badgePaletteAurora;
    private MaterialCardView paletteCardTitanium;
    private LinearLayout paletteSwatchesTitanium;
    private ImageView badgePaletteTitanium;
    private MaterialCardView paletteCardShield;
    private LinearLayout paletteSwatchesShield;
    private ImageView badgePaletteShield;

    // Card 4: 关于与版本信息
    private MaterialCardView cardAbout;
    private TextView textAboutVersion;
    private LinearLayout rowAboutGithub;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Preferences(this);
        setContentView(R.layout.activity_settings);

        initViews();
        setupToolbar();
        setupGeneralCard();
        setupBypassCard();
        setupPaletteCard();
        setupAboutCard();
        setupTvFocus();

        loadSettings();

        // 恢复未提交草稿状态（例如用户换肤重建前输入的自定义规则或端口）
        if (savedInstanceState != null) {
            restoreDraftState(savedInstanceState);
        }

        btnSave.setOnClickListener(v -> {
            if (saveSettings()) {
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (edittextSocksPort != null) {
            outState.putString(KEY_DRAFT_SOCKS_PORT, edittextSocksPort.getText().toString());
        }
        if (checkboxGlobal != null) {
            outState.putBoolean(KEY_DRAFT_GLOBAL, checkboxGlobal.isChecked());
        }
        if (checkboxBypassPrivate != null) {
            outState.putBoolean(KEY_DRAFT_BYPASS_PRIVATE, checkboxBypassPrivate.isChecked());
        }
        if (checkboxBypassGeoIpCn != null) {
            outState.putBoolean(KEY_DRAFT_BYPASS_GEOIP_CN, checkboxBypassGeoIpCn.isChecked());
        }
        if (checkboxBypassGeoSiteCn != null) {
            outState.putBoolean(KEY_DRAFT_BYPASS_GEOSITE_CN, checkboxBypassGeoSiteCn.isChecked());
        }
        if (edittextBypassRules != null) {
            outState.putString(KEY_DRAFT_BYPASS_RULES, edittextBypassRules.getText().toString());
        }
        if (edittextForceProxy != null) {
            outState.putString(KEY_DRAFT_FORCE_PROXY, edittextForceProxy.getText().toString());
        }
        if (edittextEchDns != null) {
            outState.putString(KEY_DRAFT_ECH_DNS, edittextEchDns.getText().toString());
        }
        if (edittextEchDomain != null) {
            outState.putString(KEY_DRAFT_ECH_DOMAIN, edittextEchDomain.getText().toString());
        }
        if (checkboxEnableDnsWarmup != null) {
            outState.putBoolean(KEY_DRAFT_ENABLE_DNS_WARMUP, checkboxEnableDnsWarmup.isChecked());
        }
        if (spinnerLogLevel != null) {
            outState.putInt(KEY_DRAFT_LOG_LEVEL_POS, spinnerLogLevel.getSelectedItemPosition());
        }
        if (checkboxLatencyForceProxy != null) {
            outState.putBoolean(KEY_DRAFT_LATENCY_FORCE_PROXY, checkboxLatencyForceProxy.isChecked());
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        restoreDraftState(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从分应用代理界面返回时刷新已选数量
        int selectedApps = prefs.getApps().size();
        textAppsSummary.setText(selectedApps > 0
                ? getString(R.string.settings_apps_row_summary, selectedApps)
                : getString(R.string.settings_apps_row_summary_none));
    }

    @Override
    public void onBackPressed() {
        if (prefs.getEnable() || saveSettings()) {
            super.onBackPressed();
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        btnSave = findViewById(R.id.btn_save);

        // Card 1
        cardGeneral = findViewById(R.id.card_general);
        spinnerLanguage = findViewById(R.id.spinner_language);
        textLanguageSummary = findViewById(R.id.text_language_summary);
        checkboxShowServerAddr = findViewById(R.id.checkbox_show_server_addr);
        tilSocksPort = findViewById(R.id.til_socks_port);
        edittextSocksPort = findViewById(R.id.edittext_socks_port);
        spinnerLogLevel = findViewById(R.id.spinner_log_level);
        textLogLevelSummary = findViewById(R.id.text_log_level_summary);
        tilEchDns = findViewById(R.id.til_ech_dns);
        edittextEchDns = findViewById(R.id.edittext_ech_dns);
        tilEchDomain = findViewById(R.id.til_ech_domain);
        edittextEchDomain = findViewById(R.id.edittext_ech_domain);
        checkboxEnableDnsWarmup = findViewById(R.id.checkbox_enable_dns_warmup);
        tilLatencyUrl = findViewById(R.id.til_latency_url);
        editLatencyUrl = findViewById(R.id.edit_latency_url);
        spinnerLatencyInterval = findViewById(R.id.spinner_latency_interval);
        spinnerLatencyTimeout = findViewById(R.id.spinner_latency_timeout);
        textGeneralRestartHint = findViewById(R.id.text_general_restart_hint);

        // Card 2
        cardBypass = findViewById(R.id.card_bypass);
        checkboxGlobal = findViewById(R.id.checkbox_global);
        buttonApps = findViewById(R.id.button_apps);
        textAppsSummary = findViewById(R.id.text_apps_summary);
        checkboxBypassPrivate = findViewById(R.id.checkbox_bypass_private);
        checkboxBypassGeoIpCn = findViewById(R.id.checkbox_bypass_geoip_cn);
        checkboxBypassGeoSiteCn = findViewById(R.id.checkbox_bypass_geosite_cn);
        checkboxLatencyForceProxy = findViewById(R.id.checkbox_latency_force_proxy);
        tilBypassRules = findViewById(R.id.til_bypass_rules);
        edittextBypassRules = findViewById(R.id.edittext_bypass_rules);
        tilForceProxy = findViewById(R.id.til_force_proxy);
        edittextForceProxy = findViewById(R.id.edittext_force_proxy);
        textBypassRestartHint = findViewById(R.id.text_bypass_restart_hint);

        // Card 3
        cardPalette = findViewById(R.id.card_palette);
        spinnerThemeMode = findViewById(R.id.spinner_theme_mode);
        textThemeModeSummary = findViewById(R.id.text_theme_mode_summary);
        textPaletteSummary = findViewById(R.id.text_palette_summary);
        paletteCardAurora = findViewById(R.id.palette_card_aurora);
        paletteSwatchesAurora = findViewById(R.id.palette_swatches_aurora);
        badgePaletteAurora = findViewById(R.id.badge_palette_aurora);
        paletteCardTitanium = findViewById(R.id.palette_card_titanium);
        paletteSwatchesTitanium = findViewById(R.id.palette_swatches_titanium);
        badgePaletteTitanium = findViewById(R.id.badge_palette_titanium);
        paletteCardShield = findViewById(R.id.palette_card_shield);
        paletteSwatchesShield = findViewById(R.id.palette_swatches_shield);
        badgePaletteShield = findViewById(R.id.badge_palette_shield);

        // Card 4
        cardAbout = findViewById(R.id.card_about);
        textAboutVersion = findViewById(R.id.text_about_version);
        rowAboutGithub = findViewById(R.id.row_about_github);
    }

    private void setupToolbar() {
        toolbar.setTitle(getString(R.string.settings_title));
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupGeneralCard() {
        // 语言适配器绑定
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, languageLabels());
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languageAdapter);
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isLoadingSettings && position >= 0 && position < LANGUAGE_VALUES.length) {
                    String selected = LANGUAGE_VALUES[position];
                    if (!selected.equals(prefs.getLanguage())) {
                        LanguageManager.setLanguage(SettingsActivity.this, selected);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 服务器地址显示开关（一次性绑定，避免 loadSettings 中重复注册）
        checkboxShowServerAddr.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!isLoadingSettings) {
                prefs.setShowServerAddr(isChecked);
            }
        });

        // 端口输入动态清除错误
        edittextSocksPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                FormUtils.clearFieldError(edittextSocksPort);
            }
        });

        // 日志等级适配器绑定
        ArrayAdapter<String> logLevelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, logLevelLabels());
        logLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLogLevel.setAdapter(logLevelAdapter);

        // 延迟测试配置适配器绑定
        ArrayAdapter<String> latencyIntervalAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LATENCY_INTERVAL_LABELS);
        latencyIntervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLatencyInterval.setAdapter(latencyIntervalAdapter);
        spinnerLatencyInterval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isLoadingSettings && position >= 0 && position < LATENCY_INTERVAL_VALUES.length) {
                    prefs.setLatencyTestInterval(LATENCY_INTERVAL_VALUES[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> latencyTimeoutAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LATENCY_TIMEOUT_LABELS);
        latencyTimeoutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLatencyTimeout.setAdapter(latencyTimeoutAdapter);
        spinnerLatencyTimeout.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isLoadingSettings && position >= 0 && position < LATENCY_TIMEOUT_VALUES.length) {
                    prefs.setLatencyTestTimeout(LATENCY_TIMEOUT_VALUES[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        editLatencyUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (!isLoadingSettings) {
                    String url = s.toString().trim();
                    if (!url.isEmpty()) {
                        prefs.setLatencyTestUrl(url);
                    }
                }
            }
        });
    }

    private void setupBypassCard() {
        buttonApps.setOnClickListener(v -> startActivity(new Intent(this, AppListActivity.class)));

        checkboxLatencyForceProxy.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!isLoadingSettings) {
                prefs.setLatencyForceProxy(isChecked);
            }
        });

        // 动态清除校验错误
        edittextBypassRules.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                FormUtils.clearFieldError(edittextBypassRules);
            }
        });

        edittextForceProxy.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                FormUtils.clearFieldError(edittextForceProxy);
            }
        });

        // 多行输入框防 D-pad 焦点卡死拦截器
        View.OnKeyListener multilineKeyListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                EditText et = (EditText) v;
                int lineCount = et.getLineCount();
                int currentLine = et.getLayout() != null
                        ? et.getLayout().getLineForOffset(et.getSelectionStart())
                        : 0;

                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (currentLine >= lineCount - 1 || et.getText().length() == 0)) {
                    View next = v.focusSearch(View.FOCUS_DOWN);
                    if (next != null) {
                        next.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && currentLine == 0) {
                    View prev = v.focusSearch(View.FOCUS_UP);
                    if (prev != null) {
                        prev.requestFocus();
                        return true;
                    }
                }
            }
            return false;
        };
        edittextBypassRules.setOnKeyListener(multilineKeyListener);
        edittextForceProxy.setOnKeyListener(multilineKeyListener);
    }

    private void setupPaletteCard() {
        // 明暗模式适配器绑定
        ArrayAdapter<String> themeModeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, themeLabels());
        themeModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerThemeMode.setAdapter(themeModeAdapter);
        spinnerThemeMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isLoadingSettings && position >= 0 && position < THEME_MODE_VALUES.length) {
                    int mode = THEME_MODE_VALUES[position];
                    if (mode != prefs.getThemeMode()) {
                        ThemeManager.setMode(SettingsActivity.this, mode);
                        textThemeModeSummary.setText(getString(R.string.settings_theme_mode_summary,
                                getString(THEME_MODE_LABEL_RES[position])));
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 填充 3 套内联色卡圆圈
        fillPaletteSwatches(paletteSwatchesAurora, PALETTE_SWATCH_RES[0]);
        fillPaletteSwatches(paletteSwatchesTitanium, PALETTE_SWATCH_RES[1]);
        fillPaletteSwatches(paletteSwatchesShield, PALETTE_SWATCH_RES[2]);

        // 点击切换调色板并触发重建（重建前自动保存未提交草稿）
        setupPaletteItemClick(paletteCardAurora, Preferences.PALETTE_AURORA);
        setupPaletteItemClick(paletteCardTitanium, Preferences.PALETTE_TITANIUM);
        setupPaletteItemClick(paletteCardShield, Preferences.PALETTE_SHIELD);

        // TV 遥控器焦点高光边框联动
        setupPaletteItemFocus(paletteCardAurora, Preferences.PALETTE_AURORA);
        setupPaletteItemFocus(paletteCardTitanium, Preferences.PALETTE_TITANIUM);
        setupPaletteItemFocus(paletteCardShield, Preferences.PALETTE_SHIELD);
    }

    private void fillPaletteSwatches(LinearLayout container, int[] colorResArray) {
        container.removeAllViews();
        for (int colorRes : colorResArray) {
            container.addView(createSwatch(ContextCompat.getColor(this, colorRes)));
        }
    }

    private void setupPaletteItemClick(MaterialCardView card, int paletteValue) {
        card.setOnClickListener(v -> {
            if (paletteValue != prefs.getPalette()) {
                ThemeManager.setPalette(SettingsActivity.this, paletteValue);
                recreate();
            }
        });
    }

    private void setupPaletteItemFocus(MaterialCardView card, int paletteValue) {
        card.setOnFocusChangeListener((v, hasFocus) -> {
            MaterialCardView cv = (MaterialCardView) v;
            boolean isSelected = (paletteValue == prefs.getPalette());
            if (hasFocus) {
                cv.setStrokeColor(MaterialColors.getColor(cv, R.attr.xPrimary));
                cv.setStrokeWidth(dp(2));
                v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(4f).setDuration(100).start();
            } else {
                cv.setStrokeColor(MaterialColors.getColor(cv, isSelected ? R.attr.xPrimary : R.attr.xOutlineVariant));
                cv.setStrokeWidth(dp(isSelected ? 2 : 1));
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(100).start();
            }
        });
    }

    private void updatePaletteCardsVisual() {
        int activePalette = prefs.getPalette();
        updatePaletteItemVisual(paletteCardAurora, badgePaletteAurora, activePalette == Preferences.PALETTE_AURORA);
        updatePaletteItemVisual(paletteCardTitanium, badgePaletteTitanium, activePalette == Preferences.PALETTE_TITANIUM);
        updatePaletteItemVisual(paletteCardShield, badgePaletteShield, activePalette == Preferences.PALETTE_SHIELD);
    }

    private void updatePaletteItemVisual(MaterialCardView card, ImageView badge, boolean selected) {
        badge.setVisibility(selected ? View.VISIBLE : View.GONE);
        int strokeColor = MaterialColors.getColor(card, selected ? R.attr.xPrimary : R.attr.xOutlineVariant);
        int strokeWidth = dp(selected ? 2 : 1);
        int bgColor = MaterialColors.getColor(card, selected ? R.attr.xSurfaceContainerHigh : R.attr.xSurfaceContainerLowest);
        card.setStrokeColor(strokeColor);
        card.setStrokeWidth(strokeWidth);
        card.setCardBackgroundColor(bgColor);
    }

    private View createSwatch(int color) {
        View swatch = new View(this);
        int size = dp(20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(6));
        swatch.setLayoutParams(lp);

        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(1), 0x22000000);
        swatch.setBackground(d);
        return swatch;
    }

    private void setupAboutCard() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pInfo.getLongVersionCode()
                    : pInfo.versionCode;
            textAboutVersion.setText("v" + pInfo.versionName + " (" + versionCode + ")");
        } catch (Exception e) {
            textAboutVersion.setText("v1.5.0");
        }

        rowAboutGithub.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("GitHub Repository", GITHUB_REPO_URL);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
            }
            Toast.makeText(this, getString(R.string.settings_about_github_copied), Toast.LENGTH_SHORT).show();
        });
    }

    private void setupTvFocus() {
        TvFocusHelper.applyFocusFeedback(
                checkboxShowServerAddr,
                checkboxGlobal,
                buttonApps,
                checkboxBypassPrivate,
                checkboxBypassGeoIpCn,
                checkboxBypassGeoSiteCn,
                checkboxEnableDnsWarmup,
                checkboxLatencyForceProxy,
                rowAboutGithub,
                btnSave
        );

        TvFocusHelper.applyParentFocusFeedback(spinnerLanguage);
        TvFocusHelper.applyParentFocusFeedback(spinnerLogLevel);
        TvFocusHelper.applyParentFocusFeedback(spinnerThemeMode);
        TvFocusHelper.applyParentFocusFeedback(spinnerLatencyInterval);
        TvFocusHelper.applyParentFocusFeedback(spinnerLatencyTimeout);
    }

    private void loadSettings() {
        isLoadingSettings = true;
        try {
            // 加载常规基础设置
            checkboxShowServerAddr.setChecked(prefs.getShowServerAddr());

            int currentPort = prefs.getSocksPort();
            edittextSocksPort.setText(String.valueOf(currentPort));
            tilSocksPort.setHelperText(getString(R.string.settings_socks_port_summary, currentPort));

            int langPosition = indexOf(LANGUAGE_VALUES, prefs.getLanguage());
            spinnerLanguage.setSelection(langPosition);
            textLanguageSummary.setText(getString(R.string.settings_language_summary,
                    getString(LANGUAGE_LABEL_RES[langPosition])));

            String logLevel = prefs.getLogLevel();
            int logLevelPosition = indexOf(LOG_LEVEL_VALUES, logLevel);
            spinnerLogLevel.setSelection(logLevelPosition);
            textLogLevelSummary.setText(getString(R.string.settings_log_level_summary,
                    getString(LOG_LEVEL_LABEL_RES[logLevelPosition])));

            edittextEchDns.setText(prefs.getEchDns());
            edittextEchDomain.setText(prefs.getEchDomain());
            checkboxEnableDnsWarmup.setChecked(prefs.getEnableDnsWarmup());

            editLatencyUrl.setText(prefs.getLatencyTestUrl());
            int intervalPosition = indexOf(LATENCY_INTERVAL_VALUES, prefs.getLatencyTestInterval());
            spinnerLatencyInterval.setSelection(intervalPosition);
            int timeoutPosition = indexOf(LATENCY_TIMEOUT_VALUES, prefs.getLatencyTestTimeout());
            spinnerLatencyTimeout.setSelection(timeoutPosition);

            // 加载路由绕过设置
            checkboxGlobal.setChecked(prefs.getGlobal());
            int selectedApps = prefs.getApps().size();
            textAppsSummary.setText(selectedApps > 0
                    ? getString(R.string.settings_apps_row_summary, selectedApps)
                    : getString(R.string.settings_apps_row_summary_none));

            checkboxBypassPrivate.setChecked(prefs.getBypassPrivate());
            checkboxBypassGeoIpCn.setChecked(prefs.getBypassGeoIpCn());
            checkboxBypassGeoSiteCn.setChecked(prefs.getBypassGeoSiteCn());
            checkboxLatencyForceProxy.setChecked(prefs.getLatencyForceProxy());
            edittextBypassRules.setText(prefs.getBypassRules());
            edittextForceProxy.setText(prefs.getBypassForceProxy());

            // 加载外观与调色板设置
            int themeModePosition = indexOf(THEME_MODE_VALUES, prefs.getThemeMode());
            spinnerThemeMode.setSelection(themeModePosition);
            textThemeModeSummary.setText(getString(R.string.settings_theme_mode_summary,
                    getString(THEME_MODE_LABEL_RES[themeModePosition])));

            int palettePosition = indexOf(PALETTE_VALUES, prefs.getPalette());
            textPaletteSummary.setText(getString(R.string.settings_theme_palette_summary,
                    getString(PALETTE_LABEL_RES[palettePosition])));
            updatePaletteCardsVisual();

            // 检查 VPN 是否正在运行
            boolean isVpnRunning = prefs.getEnable();
            applyVpnRunningState(isVpnRunning);
            if (isVpnRunning) {
                Toast.makeText(this, getString(R.string.settings_vpn_running_toast), Toast.LENGTH_LONG).show();
            }
        } finally {
            isLoadingSettings = false;
        }
    }

    private void restoreDraftState(Bundle state) {
        if (state == null) return;
        if (state.containsKey(KEY_DRAFT_SOCKS_PORT)) {
            edittextSocksPort.setText(state.getString(KEY_DRAFT_SOCKS_PORT));
        }
        if (state.containsKey(KEY_DRAFT_GLOBAL)) {
            checkboxGlobal.setChecked(state.getBoolean(KEY_DRAFT_GLOBAL));
        }
        if (state.containsKey(KEY_DRAFT_BYPASS_PRIVATE)) {
            checkboxBypassPrivate.setChecked(state.getBoolean(KEY_DRAFT_BYPASS_PRIVATE));
        }
        if (state.containsKey(KEY_DRAFT_BYPASS_GEOIP_CN)) {
            checkboxBypassGeoIpCn.setChecked(state.getBoolean(KEY_DRAFT_BYPASS_GEOIP_CN));
        }
        if (state.containsKey(KEY_DRAFT_BYPASS_GEOSITE_CN)) {
            checkboxBypassGeoSiteCn.setChecked(state.getBoolean(KEY_DRAFT_BYPASS_GEOSITE_CN));
        }
        if (state.containsKey(KEY_DRAFT_BYPASS_RULES)) {
            edittextBypassRules.setText(state.getString(KEY_DRAFT_BYPASS_RULES));
        }
        if (state.containsKey(KEY_DRAFT_FORCE_PROXY)) {
            edittextForceProxy.setText(state.getString(KEY_DRAFT_FORCE_PROXY));
        }
        if (state.containsKey(KEY_DRAFT_ECH_DNS)) {
            edittextEchDns.setText(state.getString(KEY_DRAFT_ECH_DNS));
        }
        if (state.containsKey(KEY_DRAFT_ECH_DOMAIN)) {
            edittextEchDomain.setText(state.getString(KEY_DRAFT_ECH_DOMAIN));
        }
        if (state.containsKey(KEY_DRAFT_ENABLE_DNS_WARMUP)) {
            checkboxEnableDnsWarmup.setChecked(state.getBoolean(KEY_DRAFT_ENABLE_DNS_WARMUP));
        }
        if (state.containsKey(KEY_DRAFT_LOG_LEVEL_POS)) {
            spinnerLogLevel.setSelection(state.getInt(KEY_DRAFT_LOG_LEVEL_POS));
        }
        if (state.containsKey(KEY_DRAFT_LATENCY_FORCE_PROXY)) {
            checkboxLatencyForceProxy.setChecked(state.getBoolean(KEY_DRAFT_LATENCY_FORCE_PROXY));
        }
    }

    /**
     * VPN 运行状态对应的可用性与透明度置灰控制：
     * 需重启生效的项目与整个 Card 2（路由绕过）禁用并置灰 0.5f；
     * 实时生效项（语言、服务器地址显示、明暗模式、调色板预览、延迟测试、关于）始终可用。
     */
    private void applyVpnRunningState(boolean vpnRunning) {
        float alpha = vpnRunning ? 0.5f : 1.0f;
        boolean enabled = !vpnRunning;

        // Card 2: 路由绕过规则全部受限
        cardBypass.setAlpha(alpha);
        checkboxGlobal.setEnabled(enabled);
        buttonApps.setEnabled(enabled);
        checkboxBypassPrivate.setEnabled(enabled);
        checkboxBypassGeoIpCn.setEnabled(enabled);
        checkboxBypassGeoSiteCn.setEnabled(enabled);
        checkboxLatencyForceProxy.setEnabled(enabled);
        FormUtils.setFieldEnabled(edittextBypassRules, enabled);
        FormUtils.setFieldEnabled(edittextForceProxy, enabled);

        // Card 1: 需重启生效的设置受限
        FormUtils.setFieldEnabled(edittextSocksPort, enabled);
        tilSocksPort.setAlpha(alpha);
        spinnerLogLevel.setEnabled(enabled);
        FormUtils.setFieldEnabled(edittextEchDns, enabled);
        tilEchDns.setAlpha(alpha);
        FormUtils.setFieldEnabled(edittextEchDomain, enabled);
        tilEchDomain.setAlpha(alpha);
        checkboxEnableDnsWarmup.setEnabled(enabled);
        checkboxEnableDnsWarmup.setAlpha(alpha);

        // 保存按钮
        btnSave.setEnabled(enabled);
        btnSave.setAlpha(alpha);
    }

    private boolean saveSettings() {
        FormUtils.clearFieldError(edittextSocksPort);
        FormUtils.clearFieldError(edittextBypassRules);
        FormUtils.clearFieldError(edittextForceProxy);

        int port;
        try {
            port = Integer.parseInt(edittextSocksPort.getText().toString().trim());
        } catch (Exception e) {
            String msg = getString(R.string.settings_error_port_format);
            FormUtils.showFieldError(edittextSocksPort, msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (port < 1024 || port > 65535) {
            String msg = getString(R.string.settings_error_port_range);
            FormUtils.showFieldError(edittextSocksPort, msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return false;
        }

        String bypassRules = edittextBypassRules.getText().toString().trim();
        try {
            Xclient.validateBypassRules(bypassRules);
        } catch (Exception e) {
            String msg = getString(R.string.settings_error_bypass_rules, e.getMessage());
            FormUtils.showFieldError(edittextBypassRules, msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return false;
        }

        String forceProxyRules = edittextForceProxy.getText().toString().trim();
        try {
            Xclient.validateForceProxyRules(forceProxyRules);
        } catch (Exception e) {
            String msg = getString(R.string.settings_error_force_proxy_rules, e.getMessage());
            FormUtils.showFieldError(edittextForceProxy, msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return false;
        }

        // 持久化保存
        prefs.setGlobal(checkboxGlobal.isChecked());
        prefs.setSocksPort(port);
        prefs.setBypassPrivate(checkboxBypassPrivate.isChecked());
        prefs.setBypassGeoIpCn(checkboxBypassGeoIpCn.isChecked());
        prefs.setBypassGeoSiteCn(checkboxBypassGeoSiteCn.isChecked());
        prefs.setBypassRules(bypassRules);
        prefs.setBypassForceProxy(forceProxyRules);
        prefs.setEchDns(edittextEchDns.getText().toString().trim());
        prefs.setEchDomain(edittextEchDomain.getText().toString().trim());
        prefs.setEnableDnsWarmup(checkboxEnableDnsWarmup.isChecked());
        prefs.setLogLevel(LOG_LEVEL_VALUES[spinnerLogLevel.getSelectedItemPosition()]);

        return true;
    }

    private static int indexOf(String[] array, String value) {
        if (value == null) return 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private String[] languageLabels() {
        String[] labels = new String[LANGUAGE_LABEL_RES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(LANGUAGE_LABEL_RES[i]);
        }
        return labels;
    }

    private String[] themeLabels() {
        String[] labels = new String[THEME_MODE_LABEL_RES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(THEME_MODE_LABEL_RES[i]);
        }
        return labels;
    }

    private String[] logLevelLabels() {
        String[] labels = new String[LOG_LEVEL_LABEL_RES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(LOG_LEVEL_LABEL_RES[i]);
        }
        return labels;
    }
}
