/*
 ============================================================================
 Name        : ProfileEditActivity.java
 Description : Profile Edit Activity (Material 3 Architecture & Decoupled Binders)
 ============================================================================
 */

package com.x.client.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 节点配置编辑页面。
 * 遵循 Material 3 设计规范与 8-point 间距体系，分层解耦表单视图绑定与协议参数存取。
 */
public class ProfileEditActivity extends BaseActivity {
    public static final String EXTRA_PROFILE_ID = "EXTRA_PROFILE_ID";
    public static final String EXTRA_IS_NEW_PROFILE = "EXTRA_IS_NEW_PROFILE";
    private static final int REQUEST_SCAN_QR = 1001;

    private Preferences prefs;
    private String profileId;
    private boolean isNewProfile = false;
    private boolean hasBeenSaved = false;

    // 基础信息与协议选择器
    private EditText edittext_profile_name;
    private MaterialButtonToggleGroup toggleGroupProtocol;

    // 协议专有表单绑定器
    private GcmConfigBinder gcmBinder;
    private XtunnelConfigBinder xtunnelBinder;

    // 优选中转路由状态
    private final ExecutorService preferredWorker = Executors.newSingleThreadExecutor();
    private Spinner preferredMode;
    private boolean preferredConflict, preferredLoaded, preferredResumed, preferredInitialImplicit;
    private boolean preferredDraftDirty, preferredImportedAddresses;
    private boolean preferredGlobalEnabled;
    private int preferredGlobalCount;
    private String restoredXtAdvancedDraft;
    private String pendingPreferredImport;
    private String preferredOriginalMode, restoredPreferredMode;
    private static final String[] PREFERRED_MODES = {"global", "independent", "default"};

    private MaterialButton btn_import;
    private MaterialButton btn_save;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            restoredPreferredMode = savedInstanceState.getString("preferred_mode_draft");
            preferredDraftDirty = savedInstanceState.getBoolean("preferred_dirty_draft");
            preferredImportedAddresses = savedInstanceState.getBoolean("preferred_imported_addresses");
            restoredXtAdvancedDraft = savedInstanceState.getString("xt_advanced_draft");
            pendingPreferredImport = savedInstanceState.getString("pending_preferred_import");
        }

        prefs = new Preferences(this);

        // 获取要编辑的配置 ID
        profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        isNewProfile = getIntent().getBooleanExtra(EXTRA_IS_NEW_PROFILE, false);
        if (profileId == null) {
            profileId = prefs.getCurrentProfileId();
        }

        setContentView(R.layout.activity_profile_edit);

        // 电视设备 overscan 安全边距
        if (DeviceUtils.isTv(this)) {
            int safeH = (int) (32 * getResources().getDisplayMetrics().density);
            int safeV = (int) (20 * getResources().getDisplayMetrics().density);
            findViewById(android.R.id.content).setPadding(safeH, safeV, safeH, safeV);
        }

        // 初始化 M3 Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.edit_profile_title, prefs.getProfileName(profileId)));
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        // 基础控件绑定
        edittext_profile_name = findViewById(R.id.profile_name);
        toggleGroupProtocol = findViewById(R.id.toggle_group_protocol);
        preferredMode = findViewById(R.id.preferred_mode_spinner);
        btn_import = findViewById(R.id.btn_import);
        btn_save = findViewById(R.id.btn_save);

        // 协议专属表单解耦绑定
        View root = findViewById(android.R.id.content);
        gcmBinder = new GcmConfigBinder(this);
        gcmBinder.bindViews(root);

        xtunnelBinder = new XtunnelConfigBinder(this);
        xtunnelBinder.bindViews(root);
        if (restoredXtAdvancedDraft != null) {
            xtunnelBinder.setAdvancedDraft(restoredXtAdvancedDraft);
        }

        // 协议分段切换监听
        toggleGroupProtocol.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String protocol = checkedId == R.id.btn_protocol_xtunnel
                        ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM;
                updateProtocolView(protocol);
            }
        });

        // 电视与遥控器焦点高光增强
        TvFocusHelper.applyFocusFeedback(
                btn_import,
                btn_save,
                findViewById(R.id.preferred_manage),
                findViewById(R.id.preferred_clear_conflict),
                findViewById(R.id.btn_protocol_gcm),
                findViewById(R.id.btn_protocol_xtunnel)
        );
        TvFocusHelper.applyParentFocusFeedback(preferredMode);

        // 加载配置数据
        loadProfileData();

        // 初始化优选中转路由
        setupPreferredIp();

        // 按钮事件
        btn_import.setOnClickListener(v -> showImportDialog());
        btn_save.setOnClickListener(v -> onSaveClicked());
    }

    private void updateProtocolView(String protocol) {
        boolean isXtunnel = Preferences.PROTOCOL_X_TUNNEL.equals(protocol);
        gcmBinder.setVisible(!isXtunnel);
        xtunnelBinder.setVisible(isXtunnel);
        if (preferredMode != null) {
            refreshPreferredConflict(protocol);
            if (preferredLoaded) {
                showPreferredSummary();
            }
        }
    }

    private String getSelectedProtocol() {
        return toggleGroupProtocol.getCheckedButtonId() == R.id.btn_protocol_xtunnel
                ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM;
    }

    private void setProtocolSelection(String protocol) {
        int targetId = Preferences.PROTOCOL_X_TUNNEL.equals(protocol)
                ? R.id.btn_protocol_xtunnel : R.id.btn_protocol_gcm;
        toggleGroupProtocol.check(targetId);
    }

    private boolean preferredLocked() {
        return prefs.getEnable() && profileId.equals(prefs.getCurrentProfileId());
    }

    private void loadProfileData() {
        Preferences activePrefs = prefs;
        Preferences profilePrefs = activePrefs.forProfile(profileId);

        // 加载配置名称与协议
        edittext_profile_name.setText(profilePrefs.getProfileName(profileId));
        String protocol = profilePrefs.getProtocol();
        setProtocolSelection(protocol);

        // 委托协议专属绑定器加载
        gcmBinder.loadFromPreferences(profilePrefs);
        xtunnelBinder.loadFromPreferences(profilePrefs);

        updateProtocolView(protocol);

        // 检查 VPN 是否运行于当前配置（运行时全表单锁定）
        boolean isVpnRunning = prefs.getEnable();
        boolean isCurrentProfile = profileId.equals(activePrefs.getCurrentProfileId());

        if (isVpnRunning && isCurrentProfile) {
            FormUtils.setFieldEnabled(edittext_profile_name, false);
            toggleGroupProtocol.setEnabled(false);
            findViewById(R.id.btn_protocol_gcm).setEnabled(false);
            findViewById(R.id.btn_protocol_xtunnel).setEnabled(false);
            gcmBinder.setEnabled(false);
            xtunnelBinder.setEnabled(false);
            btn_save.setEnabled(false);
            btn_import.setEnabled(false);

            Toast.makeText(this, getString(R.string.toast_vpn_running_locked), Toast.LENGTH_LONG).show();
        }
    }

    private void setPreferredModeEnabled(boolean enabled) {
        if (preferredMode == null) return;
        preferredMode.setEnabled(enabled);
        android.view.ViewParent parent = preferredMode.getParent();
        if (parent instanceof View) {
            ((View) parent).setEnabled(enabled);
        }
    }

    private int getPreferredModePosition() {
        if (preferredMode == null) return 0;
        int pos = preferredMode.getSelectedItemPosition();
        return (pos >= 0 && pos < PREFERRED_MODES.length) ? pos : 0;
    }

    private String getSelectedPreferredMode() {
        return PREFERRED_MODES[getPreferredModePosition()];
    }

    private void updateRelayEnabled() {
        boolean independent = (getPreferredModePosition() == 1) && !preferredLocked();
        if (gcmBinder != null) {
            gcmBinder.setRelayEnabled(independent);
        }
        if (xtunnelBinder != null) {
            xtunnelBinder.setRelayEnabled(independent);
        }
    }

    private void setupPreferredIp() {
        if (preferredMode == null) {
            preferredMode = findViewById(R.id.preferred_mode_spinner);
        }
        setPreferredModeEnabled(false);
        updateRelayEnabled();
        btn_save.setEnabled(false);
        btn_import.setEnabled(false);
        preferredMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> a, View v, int position, long id) {
                if (preferredConflict && position != 1) {
                    preferredMode.setSelection(1);
                    return;
                }
                updateRelayEnabled();
                if (preferredLoaded) showPreferredSummary();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });

        findViewById(R.id.preferred_manage).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage("管理页仅使用已保存的节点参数。请先保存未提交的目标/ECH更改；本页原值不会被全局池覆盖。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.preferred_ip_title, (d, w) -> startActivity(new Intent(this, PreferredIpActivity.class)
                        .putExtra(EXTRA_PROFILE_ID, profileId))).show());

        findViewById(R.id.preferred_clear_conflict).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.preferred_clear_conflict_body).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    try {
                        xtunnelBinder.removeRelayNodesFromDraft();
                        preferredDraftDirty = true;
                        refreshPreferredConflict(getSelectedProtocol());
                        preferredMode.setSelection(0);
                        updateRelayEnabled();
                        showPreferredSummary();
                    } catch (JSONException error) {
                        Toast.makeText(this, R.string.error_xt_advanced_invalid, Toast.LENGTH_LONG).show();
                    }
                }).show());

        loadPreferredMode(false);
    }

    private void showPreferredSummary() {
        TextView summary = findViewById(R.id.preferred_mode_summary);
        if (summary == null) return;
        if (preferredConflict) {
            summary.setText(R.string.preferred_conflict);
            return;
        }
        String mode = getSelectedPreferredMode();
        boolean dirty = preferredDraftDirty || (preferredOriginalMode != null && !mode.equals(preferredOriginalMode));
        String text;
        if ("global".equals(mode)) {
            if (preferredGlobalEnabled && preferredGlobalCount > 0) {
                text = "跟随全局 · 已启用 " + preferredGlobalCount + " 个优选 IP";
            } else if (!preferredGlobalEnabled) {
                text = "跟随全局 · 全局池未启用，保留节点原入口";
            } else {
                text = "跟随全局 · 全局池暂无已选 IP，保留节点原入口";
            }
        } else if ("independent".equals(mode)) {
            text = "独立配置 · 使用下方设置的中转 IP";
        } else {
            text = "域名默认 · 直连节点域名，不使用中转 IP";
        }
        if (dirty) {
            text += "（保存后生效）";
        }
        summary.setText(text);
    }

    private void loadPreferredMode(boolean refresh) {
        preferredWorker.execute(() -> {
            try {
                JSONObject pool = new PreferredIpStore(this).read();
                String mode = PreferredIpStore.mode(pool, profileId);
                boolean globalEnabled = pool.optBoolean("enabled");
                JSONArray selectedArr = pool.optJSONArray("selected");
                int globalCount = selectedArr != null ? selectedArr.length() : 0;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    preferredGlobalEnabled = globalEnabled;
                    preferredGlobalCount = globalCount;
                    if (refresh && preferredOriginalMode != null && (preferredDraftDirty
                            || !getSelectedPreferredMode().equals(preferredOriginalMode))) {
                        return;
                    }
                    refreshPreferredConflict(getSelectedProtocol());
                    String displayMode = preferredConflict ? "independent"
                            : restoredPreferredMode != null ? restoredPreferredMode : mode;
                    for (int i = 0; i < PREFERRED_MODES.length; i++) {
                        if (PREFERRED_MODES[i].equals(displayMode)) {
                            preferredMode.setSelection(i);
                        }
                    }
                    updateRelayEnabled();
                    restoredPreferredMode = null;
                    preferredOriginalMode = preferredConflict ? "independent" : mode;
                    JSONObject profileModes = pool.optJSONObject("profile_modes");
                    preferredInitialImplicit = profileModes == null || !profileModes.has(profileId);
                    preferredLoaded = true;
                    setPreferredModeEnabled(!preferredLocked());
                    btn_save.setEnabled(!preferredLocked());
                    btn_import.setEnabled(!preferredLocked());
                    showPreferredSummary();
                    if (pendingPreferredImport != null) {
                        String pending = pendingPreferredImport;
                        pendingPreferredImport = null;
                        importFromProtocol(pending);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    TextView summary = findViewById(R.id.preferred_mode_summary);
                    if (summary != null) summary.setText("优选库读取失败：" + error.getMessage());
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferredResumed && preferredMode != null) {
            loadPreferredMode(true);
        }
        preferredResumed = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (preferredMode != null && preferredLoaded) {
            out.putString("preferred_mode_draft", getSelectedPreferredMode());
        }
        out.putBoolean("preferred_dirty_draft", preferredDraftDirty);
        out.putBoolean("preferred_imported_addresses", preferredImportedAddresses);
        if (xtunnelBinder != null) {
            out.putString("xt_advanced_draft", xtunnelBinder.getAdvancedDraft());
        }
        out.putString("pending_preferred_import", pendingPreferredImport);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onDestroy() {
        preferredWorker.shutdown();
        super.onDestroy();
    }

    private void refreshPreferredConflict(String protocol) {
        boolean hasRelay = false;
        preferredConflict = false;
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol) && xtunnelBinder != null) {
            preferredConflict = hasRelay = xtunnelBinder.hasRelayNodesInDraft();
        }
        if (preferredConflict && preferredMode != null) {
            preferredMode.setSelection(1);
        }
        updateRelayEnabled();
        View clearBtn = findViewById(R.id.preferred_clear_conflict);
        if (clearBtn != null) {
            clearBtn.setVisibility(hasRelay && !preferredLocked() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        if (isNewProfile && !hasBeenSaved) {
            preferredWorker.execute(() -> {
                try {
                    prefs.removeProfile(profileId);
                    runOnUiThread(this::finish);
                } catch (IllegalStateException error) {
                    runOnUiThread(() -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        super.onBackPressed();
    }

    private void onSaveClicked() {
        if (!preferredLoaded) return;
        if (savePrefs()) {
            hasBeenSaved = true;
            String mode = getSelectedPreferredMode();
            boolean saveMode = preferredDraftDirty || !preferredInitialImplicit || !mode.equals(preferredOriginalMode);
            btn_save.setEnabled(false);
            btn_import.setEnabled(false);
            preferredWorker.execute(() -> {
                try {
                    if (saveMode) PreferredIpStore.setMode(this, profileId, mode);
                    runOnUiThread(() -> {
                        hasBeenSaved = true;
                        Toast.makeText(this, getString(R.string.toast_profile_saved), Toast.LENGTH_SHORT).show();
                        finish();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        btn_save.setEnabled(true);
                        btn_import.setEnabled(true);
                        new MaterialAlertDialogBuilder(this)
                                .setMessage("节点已保存，但优选模式写入失败：" + error.getMessage())
                                .setPositiveButton(R.string.ok, null).show();
                    });
                }
            });
        }
    }

    private boolean savePrefs() {
        FormUtils.clearFieldError(edittext_profile_name);
        gcmBinder.clearErrors();
        xtunnelBinder.clearErrors();

        String profileName = edittext_profile_name.getText().toString().trim();
        if (profileName.isEmpty()) {
            FormUtils.showFieldError(edittext_profile_name, getString(R.string.error_profile_name_empty));
            return false;
        }

        String protocol = getSelectedProtocol();
        boolean saveRelay = preferredImportedAddresses || (getPreferredModePosition() == 1);

        Preferences profilePrefs = this.prefs.forProfile(profileId);

        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            if (!xtunnelBinder.validateAndSave(profilePrefs, saveRelay)) {
                return false;
            }
        } else {
            if (!gcmBinder.validateAndSave(profilePrefs, saveRelay)) {
                return false;
            }
        }

        profilePrefs.setProfileName(profileId, profileName);
        profilePrefs.setProtocol(protocol);

        return true;
    }

    // ======================== 导入与扫码功能 ========================

    private void showImportDialog() {
        final CharSequence[] options = {
                getString(R.string.dialog_option_manual), getString(R.string.dialog_option_scan)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_import)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showManualInputDialog();
                    } else if (which == 1) {
                        scanQrCode();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showManualInputDialog() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.manual_import_hint));

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = pad;
        lp.rightMargin = pad;
        lp.topMargin = pad / 2;
        lp.bottomMargin = pad / 4;
        input.setLayoutParams(lp);
        container.addView(input);

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            try {
                if (clipboard.hasPrimaryClip()) {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        if (text != null) {
                            String str = text.toString().trim();
                            if (ProfileUriCodec.isSupportedUri(str)) {
                                input.setText(str);
                                input.setSelection(str.length());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 忽略系统剪贴板受限或安全权限异常
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_import)
                .setView(container)
                .setPositiveButton(R.string.ok, (dialog, whichButton) -> {
                    String protocol = input.getText().toString().trim();
                    if (!protocol.isEmpty()) {
                        importFromProtocol(protocol);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void scanQrCode() {
        ScanOptions options = new ScanOptions();
        options.setPrompt(getString(R.string.scan_qr_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(CustomCaptureActivity.class);
        try {
            Intent intent = options.createScanIntent(this);
            startActivityForResult(intent, REQUEST_SCAN_QR);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_scanner_start_failed), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_SCAN_QR && result == RESULT_OK && data != null) {
            String scannedText = data.getStringExtra(com.google.zxing.client.android.Intents.Scan.RESULT);
            if (scannedText == null) {
                scannedText = data.getStringExtra("SCAN_RESULT");
            }
            if (scannedText != null) {
                importFromProtocol(scannedText);
            }
        }
    }

    private void importFromProtocol(String protocol) {
        if (preferredLocked()) {
            Toast.makeText(this, "当前节点正在运行，不能导入覆盖", Toast.LENGTH_LONG).show();
            return;
        }
        if (!preferredLoaded) {
            pendingPreferredImport = protocol;
            return;
        }
        if (!ProfileUriCodec.isSupportedUri(protocol)) {
            Toast.makeText(this, getString(R.string.toast_invalid_protocol), Toast.LENGTH_SHORT).show();
            return;
        }

        ProfileUriCodec.ParsedProfile p;
        try {
            p = ProfileUriCodec.parse(protocol);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_invalid_protocol), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isXtunnel = p.isXtunnel();
        if (isXtunnel && !confirmXtImportOverrides(protocol)) {
            return;
        }

        boolean implicitImport = preferredInitialImplicit && !preferredDraftDirty
                && getSelectedPreferredMode().equals(preferredOriginalMode);
        preferredImportedAddresses = true;
        if (!implicitImport) {
            preferredDraftDirty = true;
            preferredMode.setSelection(1);
        }
        updateRelayEnabled();

        if (isXtunnel) {
            setProtocolSelection(Preferences.PROTOCOL_X_TUNNEL);
            xtunnelBinder.applyImport(p);
        } else {
            setProtocolSelection(Preferences.PROTOCOL_GCM);
            gcmBinder.applyImport(p);
        }
        if (!p.name.isEmpty()) {
            edittext_profile_name.setText(p.name);
        }

        refreshPreferredConflict(isXtunnel ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM);
        showPreferredSummary();
        Toast.makeText(this, getString(R.string.toast_imported_check), Toast.LENGTH_LONG).show();
    }

    private boolean confirmXtImportOverrides(String protocol) {
        List<String> conflicts = xtunnelBinder.getConflictingKeysInDraft();
        if (conflicts.isEmpty()) return true;

        new MaterialAlertDialogBuilder(this)
                .setMessage("导入将删除高级JSON中的以下覆盖键，使用链接字段及默认值（ECH域名/DNS使用全局设置）：\n"
                        + TextUtils.join(", ", conflicts)
                        + "\n其他高级参数保留；取消不会修改当前编辑内容。确认后仍需保存才生效。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (isFinishing() || isDestroyed() || preferredLocked()) return;
                    xtunnelBinder.removeConflictingKeysFromDraft(conflicts);
                    preferredDraftDirty = true;
                    refreshPreferredConflict(getSelectedProtocol());
                    importFromProtocol(protocol);
                }).show();
        return false;
    }
}
