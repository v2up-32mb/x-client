/*
 ============================================================================
 Name        : ProfileEditActivity.java
 Author      : Claude Code
 Description : Profile Edit Activity (Edit Single Profile)
 ============================================================================
 */

package com.x.client.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanOptions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ProfileEditActivity extends BaseActivity {
    public static final String EXTRA_PROFILE_ID = "EXTRA_PROFILE_ID";
    public static final String EXTRA_IS_NEW_PROFILE = "EXTRA_IS_NEW_PROFILE";
    private static final int REQUEST_SCAN_QR = 1001;

    private Preferences prefs;
    private String profileId;
    private boolean isNewProfile = false;
    private boolean hasBeenSaved = false;

    // 协议选择
    private Spinner spinner_protocol;
    private LinearLayout gcm_fields;
    private LinearLayout xtunnel_fields;

    // GCM 字段
    private EditText edittext_profile_name;
    private EditText edittext_worker_host;
    private EditText edittext_pref_ip;
    private EditText edittext_user_id;
    private EditText edittext_fallback_ip;
    private CheckBox checkbox_disable_ech;
    private CheckBox checkbox_disable_ipv6_route;
    private EditText edittext_ws_conn;
    private CheckBox checkbox_enable_dynamic_pool;
    private EditText edittext_dynamic_pool_max;

    // X-Tunnel 字段
    private EditText edittext_xt_server_addr;
    private EditText edittext_xt_token;
    private EditText edittext_xt_relay_nodes;
    private EditText edittext_xt_connections;
    private CheckBox checkbox_xt_disable_ech;
    private CheckBox checkbox_xt_insecure;
    private Spinner spinner_xt_ip_strategy;

    // IP 策略下拉选项对应的真实值（与 CLI -ips 及 Go 库 protocol.ParseIPStrategy 对齐；默认 4,6）
    private static final String[] XT_IP_STRATEGY_VALUES = {"4,6", "6,4", "4", "6"};
    private CheckBox checkbox_xt_enable_hot_pair;
    private EditText edittext_xt_hot_pair_count;
    private TextView xt_advanced_header;
    private LinearLayout xt_advanced_container;
    private EditText edittext_xt_adv_backpressure;
    private EditText edittext_xt_adv_write_queue_wait;
    private EditText edittext_xt_adv_dial_timeout;
    private EditText edittext_xt_adv_handshake_timeout;
    private EditText edittext_xt_adv_read_timeout;
    private EditText edittext_xt_adv_write_timeout;
    private EditText edittext_xt_adv_ping_interval;
    private EditText edittext_xt_adv_reconnect_delay;
    private EditText edittext_xt_adv_connect_timeout;
    private EditText edittext_xt_adv_max_socks5;
    private EditText edittext_xt_adv_udp_ports;

    private final java.util.concurrent.ExecutorService preferredWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Spinner preferredMode;
    private boolean preferredConflict, preferredLoaded, preferredResumed, preferredInitialImplicit;
    private boolean preferredDraftDirty, preferredImportedAddresses;
    private boolean preferredGlobalEnabled;
    private int preferredGlobalCount;
    // Unknown keys and confirmed deletions live here, never reloaded from saved prefs on save.
    private String xtAdvancedDraft;
    private String pendingPreferredImport;
    private String preferredOriginalMode, restoredPreferredMode;
    private static final String[] PREFERRED_MODES = {"global", "independent", "default"};

    private Button btn_import;
    private Button btn_save;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            restoredPreferredMode = savedInstanceState.getString("preferred_mode_draft");
            preferredDraftDirty = savedInstanceState.getBoolean("preferred_dirty_draft");
            preferredImportedAddresses = savedInstanceState.getBoolean("preferred_imported_addresses");
            xtAdvancedDraft = savedInstanceState.getString("xt_advanced_draft");
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

        // 设置标题
        setTitle(getString(R.string.edit_profile_title, prefs.getProfileName(profileId)));

        // 初始化控件
        spinner_protocol = findViewById(R.id.protocol_spinner);
        gcm_fields = findViewById(R.id.gcm_fields);
        xtunnel_fields = findViewById(R.id.xtunnel_fields);

        edittext_profile_name = findViewById(R.id.profile_name);
        edittext_worker_host = findViewById(R.id.worker_host);
        edittext_pref_ip = findViewById(R.id.pref_ip);
        edittext_user_id = findViewById(R.id.user_id);
        edittext_fallback_ip = findViewById(R.id.fallback_ip);
        checkbox_disable_ech = findViewById(R.id.disable_ech);
        checkbox_disable_ipv6_route = findViewById(R.id.disable_ipv6_route);
        edittext_ws_conn = findViewById(R.id.ws_conn);
        checkbox_enable_dynamic_pool = findViewById(R.id.enable_dynamic_pool);
        edittext_dynamic_pool_max = findViewById(R.id.dynamic_pool_max);

        edittext_xt_server_addr = findViewById(R.id.xt_server_addr);
        edittext_xt_token = findViewById(R.id.xt_token);
        edittext_xt_relay_nodes = findViewById(R.id.xt_relay_nodes);
        edittext_xt_connections = findViewById(R.id.xt_connections);
        checkbox_xt_disable_ech = findViewById(R.id.xt_disable_ech);
        checkbox_xt_insecure = findViewById(R.id.xt_insecure);
        spinner_xt_ip_strategy = findViewById(R.id.xt_ip_strategy_spinner);
        checkbox_xt_enable_hot_pair = findViewById(R.id.xt_enable_hot_pair);
        edittext_xt_hot_pair_count = findViewById(R.id.xt_hot_pair_count);
        xt_advanced_header = findViewById(R.id.xt_advanced_header);
        xt_advanced_container = findViewById(R.id.xt_advanced_container);
        edittext_xt_adv_backpressure = findViewById(R.id.xt_adv_backpressure);
        edittext_xt_adv_write_queue_wait = findViewById(R.id.xt_adv_write_queue_wait);
        edittext_xt_adv_dial_timeout = findViewById(R.id.xt_adv_dial_timeout);
        edittext_xt_adv_handshake_timeout = findViewById(R.id.xt_adv_handshake_timeout);
        edittext_xt_adv_read_timeout = findViewById(R.id.xt_adv_read_timeout);
        edittext_xt_adv_write_timeout = findViewById(R.id.xt_adv_write_timeout);
        edittext_xt_adv_ping_interval = findViewById(R.id.xt_adv_ping_interval);
        edittext_xt_adv_reconnect_delay = findViewById(R.id.xt_adv_reconnect_delay);
        edittext_xt_adv_connect_timeout = findViewById(R.id.xt_adv_connect_timeout);
        edittext_xt_adv_max_socks5 = findViewById(R.id.xt_adv_max_socks5);
        edittext_xt_adv_udp_ports = findViewById(R.id.xt_adv_udp_ports);
        // 高级参数默认折叠，点击标题展开/收起
        xt_advanced_header.setText(getString(R.string.xt_advanced_params) + " ▸");
        xt_advanced_header.setOnClickListener(v -> {
            boolean visible = xt_advanced_container.getVisibility() == View.VISIBLE;
            xt_advanced_container.setVisibility(visible ? View.GONE : View.VISIBLE);
            xt_advanced_header.setText(getString(R.string.xt_advanced_params) + (visible ? " ▸" : " ▾"));
        });
        // Hot Pair 关闭时数量输入框禁用（连同外层输入容器一起置灰）
        checkbox_xt_enable_hot_pair.setOnCheckedChangeListener((buttonView, isChecked) ->
                setFieldEnabled(edittext_xt_hot_pair_count, isChecked));

        btn_import = findViewById(R.id.btn_import);
        btn_save = findViewById(R.id.btn_save);

        // 协议选择器
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.protocol_gcm), getString(R.string.protocol_x_tunnel)});
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_protocol.setAdapter(protocolAdapter);

        // IP 策略下拉：主文本为选项值，展开态附语义说明（折叠态只显示主文本保持紧凑）
        String[] ipStrategyOptions = getResources().getStringArray(R.array.xt_ip_strategy_options);
        String[] ipStrategyDescriptions = getResources().getStringArray(R.array.xt_ip_strategy_descriptions);
        android.widget.ArrayAdapter<String> ipStrategyAdapter =
                new android.widget.ArrayAdapter<String>(this, R.layout.item_spinner_ip_strategy, ipStrategyOptions) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return bindIpStrategyEntry(position, convertView, parent, false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return bindIpStrategyEntry(position, convertView, parent, true);
            }

            private View bindIpStrategyEntry(int position, View convertView, ViewGroup parent, boolean expanded) {
                View v = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_spinner_ip_strategy, parent, false);
                ((TextView) v.findViewById(R.id.ip_strategy_title)).setText(ipStrategyOptions[position]);
                TextView desc = v.findViewById(R.id.ip_strategy_desc);
                if (expanded) {
                    desc.setText(ipStrategyDescriptions[position]);
                    desc.setVisibility(View.VISIBLE);
                } else {
                    desc.setVisibility(View.GONE);
                }
                return v;
            }
        };
        ipStrategyAdapter.setDropDownViewResource(R.layout.item_spinner_ip_strategy);
        spinner_xt_ip_strategy.setAdapter(ipStrategyAdapter);
        spinner_protocol.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateFieldVisibility(position == 1 ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // 加载配置数据
        loadProfileData();

        setupPreferredIp();

        // 设置导入按钮点击事件
        btn_import.setOnClickListener(v -> showImportDialog());

        // 设置保存按钮点击事件
        btn_save.setOnClickListener(v -> {
            if (!preferredLoaded) return;
            if (savePrefs()) {
                hasBeenSaved = true;
                String mode = PREFERRED_MODES[preferredMode.getSelectedItemPosition()];
                boolean saveMode = preferredDraftDirty || !preferredInitialImplicit || !mode.equals(preferredOriginalMode);
                btn_save.setEnabled(false);
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
                            btn_save.setEnabled(true);
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                    .setMessage("节点已保存，但优选模式写入失败：" + error.getMessage())
                                    .setPositiveButton(R.string.ok, null).show();
                        });
                    }
                });
            }
        });
    }

    private boolean preferredLocked() {
        return prefs.getEnable() && profileId.equals(prefs.getCurrentProfileId());
    }

    private void setupPreferredIp() {
        preferredMode = findViewById(R.id.preferred_mode_spinner);
        preferredMode.setEnabled(false);
        btn_save.setEnabled(false);
        btn_import.setEnabled(false);
        preferredMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int position, long id) {
                if (preferredConflict && position != 1) {
                    preferredMode.setSelection(1);
                    return;
                }
                boolean independent = position == 1 && !preferredLocked();
                setFieldEnabled(edittext_pref_ip, independent);
                setFieldEnabled(edittext_xt_relay_nodes, independent);
                if (preferredLoaded) showPreferredSummary();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });
        findViewById(R.id.preferred_manage).setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage("管理页仅使用已保存的节点参数。请先保存未提交的目标/ECH更改；本页原值不会被全局池覆盖。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.preferred_ip_title, (d, w) -> startActivity(new Intent(this, PreferredIpActivity.class)
                        .putExtra(EXTRA_PROFILE_ID, profileId))).show());
        findViewById(R.id.preferred_clear_conflict).setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(R.string.preferred_clear_conflict_body).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    try {
                        JSONObject draft = parseXtAdvancedDraft();
                        draft.remove("relay_nodes");
                        xtAdvancedDraft = draft.toString();
                        preferredDraftDirty = true;
                        refreshPreferredConflict(getSelectedProtocol());
                        preferredMode.setSelection(0);
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
        int pos = preferredMode != null ? preferredMode.getSelectedItemPosition() : 0;
        String mode = PREFERRED_MODES[Math.max(0, pos)];
        boolean dirty = preferredOriginalMode != null && !mode.equals(preferredOriginalMode);
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
                            || !PREFERRED_MODES[preferredMode.getSelectedItemPosition()].equals(preferredOriginalMode))) return;
                    refreshPreferredConflict(getSelectedProtocol());
                    String displayMode = preferredConflict ? "independent"
                            : restoredPreferredMode != null ? restoredPreferredMode : mode;
                    for (int i = 0; i < PREFERRED_MODES.length; i++) if (PREFERRED_MODES[i].equals(displayMode)) preferredMode.setSelection(i);
                    restoredPreferredMode = null;
                    preferredOriginalMode = preferredConflict ? "independent" : mode;
                    preferredInitialImplicit = !pool.optJSONObject("profile_modes").has(profileId);
                    preferredLoaded = true;
                    preferredMode.setEnabled(!preferredLocked());
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
                runOnUiThread(() -> ((TextView) findViewById(R.id.preferred_mode_summary)).setText("优选库读取失败：" + error.getMessage()));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (preferredResumed && preferredMode != null) loadPreferredMode(true);
        preferredResumed = true;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (preferredMode != null && preferredLoaded) out.putString("preferred_mode_draft", PREFERRED_MODES[preferredMode.getSelectedItemPosition()]);
        out.putBoolean("preferred_dirty_draft", preferredDraftDirty);
        out.putBoolean("preferred_imported_addresses", preferredImportedAddresses);
        out.putString("xt_advanced_draft", xtAdvancedDraft);
        out.putString("pending_preferred_import", pendingPreferredImport);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        preferredWorker.shutdown();
        super.onDestroy();
    }

    private void updateFieldVisibility(String protocol) {
        boolean isXtunnel = Preferences.PROTOCOL_X_TUNNEL.equals(protocol);
        gcm_fields.setVisibility(isXtunnel ? View.GONE : View.VISIBLE);
        xtunnel_fields.setVisibility(isXtunnel ? View.VISIBLE : View.GONE);
        if (preferredMode != null) refreshPreferredConflict(protocol);
    }

    private JSONObject parseXtAdvancedDraft() throws JSONException {
        return xtAdvancedDraft == null || xtAdvancedDraft.trim().isEmpty()
                ? new JSONObject() : new JSONObject(xtAdvancedDraft);
    }

    private void refreshPreferredConflict(String protocol) {
        boolean hasRelay = false;
        preferredConflict = false;
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            try { preferredConflict = hasRelay = parseXtAdvancedDraft().has("relay_nodes"); }
            catch (JSONException error) { preferredConflict = true; } // do not silently discard bad JSON
        }
        if (preferredConflict) preferredMode.setSelection(1);
        findViewById(R.id.preferred_clear_conflict).setVisibility(hasRelay && !preferredLocked() ? View.VISIBLE : View.GONE);
    }

    // IP 策略真实值 → 下拉选项索引；未知值（含存量 "default"）回落首项（4,6，IPv4 优先）
    private static int xtIpStrategyIndex(String value) {
        for (int i = 0; i < XT_IP_STRATEGY_VALUES.length; i++) {
            if (XT_IP_STRATEGY_VALUES[i].equals(value)) return i;
        }
        return 0;
    }

    private void setProtocolSelection(String protocol) {
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            spinner_protocol.setSelection(1);
        } else {
            spinner_protocol.setSelection(0);
        }
    }

    private String getSelectedProtocol() {
        return spinner_protocol.getSelectedItemPosition() == 1
                ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM;
    }

    private void loadProfileData() {
        Preferences activePrefs = prefs;
        Preferences prefs = activePrefs.forProfile(profileId);

        // 加载配置名称
        edittext_profile_name.setText(prefs.getProfileName(profileId));

        // 加载协议
        setProtocolSelection(prefs.getProtocol());

        // 加载配置级参数
        edittext_worker_host.setText(prefs.getWorkerHost());
        edittext_pref_ip.setText(prefs.getPrefIp());
        edittext_user_id.setText(prefs.getUserID());
        edittext_fallback_ip.setText(prefs.getFallbackIp());
        checkbox_disable_ech.setChecked(prefs.getDisableEch());
        checkbox_disable_ipv6_route.setChecked(prefs.getDisableIpv6Route());
        edittext_ws_conn.setText(String.valueOf(prefs.getWsConn()));
        checkbox_enable_dynamic_pool.setChecked(prefs.getEnableDynamicPool());
        edittext_dynamic_pool_max.setText(String.valueOf(prefs.getDynamicPoolMax()));

        // 加载 X-Tunnel 参数
        edittext_xt_server_addr.setText(prefs.getXtServerAddr());
        edittext_xt_token.setText(prefs.getXtToken());
        edittext_xt_relay_nodes.setText(prefs.getXtRelayNodes());
        edittext_xt_connections.setText(String.valueOf(prefs.getXtConnections()));
        checkbox_xt_disable_ech.setChecked(prefs.getXtDisableEch());
        checkbox_xt_insecure.setChecked(prefs.getXtInsecure());
        spinner_xt_ip_strategy.setSelection(xtIpStrategyIndex(prefs.getXtIpStrategy()));
        checkbox_xt_enable_hot_pair.setChecked(prefs.getXtEnableHotPair());
        edittext_xt_hot_pair_count.setText(String.valueOf(prefs.getXtHotPairCount()));
        setFieldEnabled(edittext_xt_hot_pair_count, prefs.getXtEnableHotPair());
        if (xtAdvancedDraft == null) xtAdvancedDraft = prefs.getXtAdvancedParams();
        loadXtAdvancedParams(xtAdvancedDraft);

        // 检查是否是当前激活的配置且 VPN 正在运行
        boolean isVpnRunning = prefs.getEnable();
        boolean isCurrentProfile = profileId.equals(activePrefs.getCurrentProfileId());

        if (isVpnRunning && isCurrentProfile) {
            // VPN 运行时禁用当前配置的修改（输入框连同外层 TextInputLayout 一起置灰）
            setFieldEnabled(edittext_profile_name, false);
            setFieldEnabled(edittext_worker_host, false);
            setFieldEnabled(edittext_pref_ip, false);
            setFieldEnabled(edittext_user_id, false);
            setFieldEnabled(edittext_fallback_ip, false);
            checkbox_disable_ech.setEnabled(false);
            checkbox_disable_ipv6_route.setEnabled(false);
            setFieldEnabled(edittext_ws_conn, false);
            checkbox_enable_dynamic_pool.setEnabled(false);
            setFieldEnabled(edittext_dynamic_pool_max, false);
            setFieldEnabled(edittext_xt_server_addr, false);
            setFieldEnabled(edittext_xt_token, false);
            setFieldEnabled(edittext_xt_relay_nodes, false);
            setFieldEnabled(edittext_xt_connections, false);
            checkbox_xt_disable_ech.setEnabled(false);
            checkbox_xt_insecure.setEnabled(false);
            spinner_xt_ip_strategy.setEnabled(false);
            checkbox_xt_enable_hot_pair.setEnabled(false);
            setFieldEnabled(edittext_xt_hot_pair_count, false);
            setFieldEnabled(edittext_xt_adv_backpressure, false);
            setFieldEnabled(edittext_xt_adv_write_queue_wait, false);
            setFieldEnabled(edittext_xt_adv_dial_timeout, false);
            setFieldEnabled(edittext_xt_adv_handshake_timeout, false);
            setFieldEnabled(edittext_xt_adv_read_timeout, false);
            setFieldEnabled(edittext_xt_adv_write_timeout, false);
            setFieldEnabled(edittext_xt_adv_ping_interval, false);
            setFieldEnabled(edittext_xt_adv_reconnect_delay, false);
            setFieldEnabled(edittext_xt_adv_connect_timeout, false);
            setFieldEnabled(edittext_xt_adv_max_socks5, false);
            setFieldEnabled(edittext_xt_adv_udp_ports, false);
            spinner_protocol.setEnabled(false);
            btn_save.setEnabled(false);

            Toast.makeText(this, getString(R.string.toast_vpn_running_locked), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        // 如果是新配置且未保存，删除该配置
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

    private boolean savePrefs() {
        clearFieldErrors();

        // 验证配置名称不为空
        String profileName = edittext_profile_name.getText().toString().trim();
        if (profileName.isEmpty()) {
            showFieldError(edittext_profile_name, getString(R.string.error_profile_name_empty));
            return false;
        }

        String protocol = getSelectedProtocol();

        // 按协议验证必填字段
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            String serverAddr = edittext_xt_server_addr.getText().toString().trim();
            if (serverAddr.isEmpty()) {
                showFieldError(edittext_xt_server_addr, getString(R.string.error_server_addr_empty));
                return false;
            }
            if (!serverAddr.startsWith("wss://") && !serverAddr.startsWith("ws://")) {
                showFieldError(edittext_xt_server_addr, getString(R.string.error_server_addr_scheme));
                return false;
            }
        } else {
            String workerHost = edittext_worker_host.getText().toString().trim();
            if (workerHost.isEmpty()) {
                showFieldError(edittext_worker_host, getString(R.string.error_server_addr_empty));
                return false;
            }
        }

        // 解析 GCM 连接池参数（仅 GCM 协议使用）
        int wsConn = Preferences.DEFAULT_WS_CONN;
        if (Preferences.PROTOCOL_GCM.equals(protocol)) {
            String wsConnText = edittext_ws_conn.getText().toString().trim();
            try {
                wsConn = wsConnText.isEmpty() ? Preferences.DEFAULT_WS_CONN : Integer.parseInt(wsConnText);
            } catch (NumberFormatException e) {
                showFieldError(edittext_ws_conn, getString(R.string.error_ws_conn_invalid));
                return false;
            }
            if (wsConn < 1 || wsConn > Preferences.MAX_DYNAMIC_POOL_LIMIT) {
                showFieldError(edittext_ws_conn,
                        getString(R.string.error_ws_conn_range, Preferences.MAX_DYNAMIC_POOL_LIMIT));
                return false;
            }
        }
        int dynamicPoolMax = Preferences.DEFAULT_DYNAMIC_POOL_MAX;
        if (Preferences.PROTOCOL_GCM.equals(protocol)) {
            String limitText = edittext_dynamic_pool_max.getText().toString().trim();
            try {
                dynamicPoolMax = limitText.isEmpty() ? Preferences.DEFAULT_DYNAMIC_POOL_MAX : Integer.parseInt(limitText);
            } catch (NumberFormatException e) {
                showFieldError(edittext_dynamic_pool_max, getString(R.string.error_pool_max_invalid));
                return false;
            }
            if (dynamicPoolMax > Preferences.MAX_DYNAMIC_POOL_LIMIT
                    || (checkbox_enable_dynamic_pool.isChecked() && dynamicPoolMax < wsConn)) {
                showFieldError(edittext_dynamic_pool_max,
                        getString(R.string.error_pool_max_range, Preferences.MAX_DYNAMIC_POOL_LIMIT));
                return false;
            }
        }

        // 解析连接数（X-Tunnel）
        int xtConnections = Preferences.DEFAULT_XT_CONNECTIONS;
        String xtConnectionsText = edittext_xt_connections.getText().toString().trim();
        if (!xtConnectionsText.isEmpty()) {
            try {
                xtConnections = Integer.parseInt(xtConnectionsText);
            } catch (NumberFormatException e) {
                showFieldError(edittext_xt_connections, getString(R.string.error_xt_connections_invalid));
                return false;
            }
        }
        if (xtConnections <= 0 || xtConnections > 16) {
            showFieldError(edittext_xt_connections, getString(R.string.error_xt_connections_range));
            return false;
        }

        // 校验并解析 Hot Pair 数量（仅 X-Tunnel 且启用时有效）
        int xtHotPairCount = Preferences.DEFAULT_XT_HOT_PAIR_COUNT;
        String xtHotPairCountText = edittext_xt_hot_pair_count.getText().toString().trim();
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol) && checkbox_xt_enable_hot_pair.isChecked()) {
            if (xtHotPairCountText.isEmpty()) {
                xtHotPairCountText = "1";
            }
            try {
                xtHotPairCount = Integer.parseInt(xtHotPairCountText);
            } catch (NumberFormatException e) {
                showFieldError(edittext_xt_hot_pair_count, getString(R.string.error_hot_pair_invalid));
                return false;
            }
            if (xtHotPairCount < 1 || xtHotPairCount > Preferences.MAX_XT_HOT_PAIR_COUNT) {
                showFieldError(edittext_xt_hot_pair_count,
                        getString(R.string.error_hot_pair_range, Preferences.MAX_XT_HOT_PAIR_COUNT));
                return false;
            }
        }

        // IP 策略：下拉框选项天然合法，直接取真实值（无需校验；非 X-Tunnel 用默认）
        String xtIpStrategy = Preferences.DEFAULT_XT_IP_STRATEGY;
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            xtIpStrategy = XT_IP_STRATEGY_VALUES[spinner_xt_ip_strategy.getSelectedItemPosition()];
        }

        // 收集并校验 X-Tunnel 高级参数（每项留空表示使用默认值）
        String xtAdvancedParams = xtAdvancedDraft;
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            // 就近报错：先定位格式非法的高级参数输入框（判定规则与 collectXtAdvancedParams 一致）
            EditText invalidAdvField = findInvalidXtAdvField();
            if (invalidAdvField != null) {
                // 折叠的高级参数容器包含该字段时先展开，确保 inline 错误可见
                if (xt_advanced_container.getVisibility() != View.VISIBLE) {
                    xt_advanced_container.setVisibility(View.VISIBLE);
                    xt_advanced_header.setText(getString(R.string.xt_advanced_params) + " ▾");
                }
                showFieldError(invalidAdvField, getString(R.string.error_xt_advanced_invalid));
                return false;
            }
            xtAdvancedParams = collectXtAdvancedParams();
            if (xtAdvancedParams == null) {
                // 兕底：预校验未覆盖到的异常情况，保持原有整体提示
                Toast.makeText(this, getString(R.string.error_xt_advanced_invalid), Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        // Scoped accessor writes only this profile; the selected profile never changes.
        Preferences prefs = this.prefs.forProfile(profileId);

        // 保存配置名称
        prefs.setProfileName(profileId, profileName);

        // 保存协议
        prefs.setProtocol(protocol);

        // 保存 GCM 配置级参数（保留，切换协议时不丢失）
        prefs.setWorkerHost(edittext_worker_host.getText().toString().trim());
        if (preferredImportedAddresses || preferredMode.getSelectedItemPosition() == 1) prefs.setPrefIp(edittext_pref_ip.getText().toString().trim());
        prefs.setUserID(edittext_user_id.getText().toString().trim());
        prefs.setFallbackIp(edittext_fallback_ip.getText().toString().trim());
        prefs.setDisableEch(checkbox_disable_ech.isChecked());
        prefs.setDisableIpv6Route(checkbox_disable_ipv6_route.isChecked());
        if (Preferences.PROTOCOL_GCM.equals(protocol)) {
            // 连接池参数仅在 GCM 协议下写入，避免切换协议时覆写该配置的 GCM 值
            prefs.setWsConn(wsConn);
            prefs.setEnableDynamicPool(checkbox_enable_dynamic_pool.isChecked());
            prefs.setDynamicPoolMax(dynamicPoolMax);
        }

        // 保存 X-Tunnel 参数
        prefs.setXtServerAddr(edittext_xt_server_addr.getText().toString().trim());
        prefs.setXtToken(edittext_xt_token.getText().toString().trim());
        if (preferredImportedAddresses || preferredMode.getSelectedItemPosition() == 1) prefs.setXtRelayNodes(edittext_xt_relay_nodes.getText().toString().trim());
        prefs.setXtConnections(xtConnections);
        prefs.setXtDisableEch(checkbox_xt_disable_ech.isChecked());
        prefs.setXtInsecure(checkbox_xt_insecure.isChecked());
        prefs.setXtEnableHotPair(checkbox_xt_enable_hot_pair.isChecked());
        prefs.setXtHotPairCount(xtHotPairCount);
        prefs.setXtIpStrategy(xtIpStrategy);
        prefs.setXtAdvancedParams(xtAdvancedParams);

        return true;
    }

    // ======================== UI 辅助（inline 校验反馈 / 禁用置灰） ========================

    // 取包裹该输入框的 TextInputLayout（未包裹时返回 null）
    private TextInputLayout tilOf(EditText edit) {
        android.view.ViewParent parent = edit.getParent();
        return parent instanceof TextInputLayout ? (TextInputLayout) parent : null;
    }

    // 就近报错：在输入框所属的 TextInputLayout 上显示 inline 错误
    private void showFieldError(EditText edit, String message) {
        TextInputLayout til = tilOf(edit);
        if (til != null) {
            til.setError(message);
        }
    }

    // 清空所有输入容器的 inline 错误
    private void clearFieldErrors() {
        EditText[] fields = {
                edittext_profile_name, edittext_worker_host, edittext_pref_ip, edittext_user_id,
                edittext_fallback_ip, edittext_ws_conn, edittext_dynamic_pool_max,
                edittext_xt_server_addr, edittext_xt_token, edittext_xt_relay_nodes,
                edittext_xt_connections, edittext_xt_hot_pair_count,
                edittext_xt_adv_backpressure, edittext_xt_adv_write_queue_wait,
                edittext_xt_adv_dial_timeout, edittext_xt_adv_handshake_timeout,
                edittext_xt_adv_read_timeout, edittext_xt_adv_write_timeout,
                edittext_xt_adv_ping_interval, edittext_xt_adv_reconnect_delay,
                edittext_xt_adv_connect_timeout, edittext_xt_adv_max_socks5,
                edittext_xt_adv_udp_ports};
        for (EditText edit : fields) {
            TextInputLayout til = tilOf(edit);
            if (til != null) {
                til.setError(null);
            }
        }
    }

    // 同步禁用/启用输入框与其外层 TextInputLayout（VPN 运行中整组置灰）
    private void setFieldEnabled(EditText edit, boolean enabled) {
        edit.setEnabled(enabled);
        TextInputLayout til = tilOf(edit);
        if (til != null) {
            til.setEnabled(enabled);
        }
    }

    // 就近定位第一个格式非法的 X-Tunnel 高级参数输入框（全部合法返回 null）
    // 注意：判定规则与 collectXtAdvancedParams 保持一致，仅用于 inline 报错
    private EditText findInvalidXtAdvField() {
        if (!isEmpty(edittext_xt_adv_backpressure) && !isValidPositiveInt(edittext_xt_adv_backpressure)) {
            return edittext_xt_adv_backpressure;
        }
        if (!isEmpty(edittext_xt_adv_write_queue_wait) && !isValidPositiveInt(edittext_xt_adv_write_queue_wait)) {
            return edittext_xt_adv_write_queue_wait;
        }
        EditText[] timeoutFields = {
                edittext_xt_adv_dial_timeout, edittext_xt_adv_handshake_timeout,
                edittext_xt_adv_read_timeout, edittext_xt_adv_write_timeout,
                edittext_xt_adv_ping_interval, edittext_xt_adv_reconnect_delay,
                edittext_xt_adv_connect_timeout};
        for (EditText field : timeoutFields) {
            if (!isEmpty(field) && !isValidPositiveDecimal(field)) {
                return field;
            }
        }
        if (!isEmpty(edittext_xt_adv_max_socks5) && !isValidNonNegativeInt(edittext_xt_adv_max_socks5)) {
            return edittext_xt_adv_max_socks5;
        }
        if (!isEmpty(edittext_xt_adv_udp_ports) && !isValidUdpPorts(edittext_xt_adv_udp_ports)) {
            return edittext_xt_adv_udp_ports;
        }
        return null;
    }

    private boolean isEmpty(EditText edit) {
        return edit.getText().toString().trim().isEmpty();
    }

    // 正整数（≥1）
    private boolean isValidPositiveInt(EditText edit) {
        try {
            return Integer.parseInt(edit.getText().toString().trim()) >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 非负整数（≥0）
    private boolean isValidNonNegativeInt(EditText edit) {
        try {
            return Integer.parseInt(edit.getText().toString().trim()) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 正数（>0，允许小数）
    private boolean isValidPositiveDecimal(EditText edit) {
        try {
            return Double.parseDouble(edit.getText().toString().trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 逗号分隔端口，每个在 1-65535 之间
    private boolean isValidUdpPorts(EditText edit) {
        try {
            for (String item : edit.getText().toString().trim().split(",")) {
                int port = Integer.parseInt(item.trim());
                if (port < 1 || port > 65535) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ======================== X-Tunnel 高级参数 ========================

    // 从各输入框收集高级参数并组装 JSON；数值非法时返回 null（留空项不写入，使用默认值）
    private String collectXtAdvancedParams() {
        try {
            JSONObject adv = parseXtAdvancedDraft();
            for (String key : new String[]{"backpressure_limit", "write_queue_wait_timeout", "dial_timeout",
                    "handshake_timeout", "read_timeout", "write_timeout", "ping_interval", "reconnect_delay",
                    "connect_timeout", "max_socks5_connections", "udp_blocked_ports"}) adv.remove(key);
            String mb = edittext_xt_adv_backpressure.getText().toString().trim();
            if (!mb.isEmpty()) {
                int n = Integer.parseInt(mb);
                if (n < 1) {
                    return null;
                }
                adv.put("backpressure_limit", n * 1048576L);
            }
            String wq = edittext_xt_adv_write_queue_wait.getText().toString().trim();
            if (!wq.isEmpty()) {
                int n = Integer.parseInt(wq);
                if (n < 1) {
                    return null;
                }
                adv.put("write_queue_wait_timeout", n);
            }
            putTimeoutSeconds(adv, "dial_timeout", edittext_xt_adv_dial_timeout);
            putTimeoutSeconds(adv, "handshake_timeout", edittext_xt_adv_handshake_timeout);
            putTimeoutSeconds(adv, "read_timeout", edittext_xt_adv_read_timeout);
            putTimeoutSeconds(adv, "write_timeout", edittext_xt_adv_write_timeout);
            putTimeoutSeconds(adv, "ping_interval", edittext_xt_adv_ping_interval);
            putTimeoutSeconds(adv, "reconnect_delay", edittext_xt_adv_reconnect_delay);
            putTimeoutSeconds(adv, "connect_timeout", edittext_xt_adv_connect_timeout);
            String max = edittext_xt_adv_max_socks5.getText().toString().trim();
            if (!max.isEmpty()) {
                int n = Integer.parseInt(max);
                if (n < 0) {
                    return null;
                }
                adv.put("max_socks5_connections", n);
            }
            String ports = edittext_xt_adv_udp_ports.getText().toString().trim();
            if (!ports.isEmpty()) {
                for (String item : ports.split(",")) {
                    int port = Integer.parseInt(item.trim());
                    if (port < 1 || port > 65535) {
                        return null;
                    }
                }
                adv.put("udp_blocked_ports", ports);
            }
            xtAdvancedDraft = adv.length() > 0 ? adv.toString() : "";
            return xtAdvancedDraft;
        } catch (JSONException e) {
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 秒（可小数）-> 毫秒整数写入 JSON；留空不写入
    private void putTimeoutSeconds(JSONObject adv, String key, EditText input) throws JSONException {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        double seconds = Double.parseDouble(text);
        if (seconds <= 0) {
            throw new NumberFormatException("timeout must be positive");
        }
        adv.put(key, Math.round(seconds * 1000));
    }

    // 从已保存的 JSON 填充各输入框（损坏数据忽略，使用默认值）
    private void loadXtAdvancedParams(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            long mb = o.optLong("backpressure_limit", 0);
            if (mb > 0) {
                edittext_xt_adv_backpressure.setText(String.valueOf(mb / 1048576));
            }
            int wq = o.optInt("write_queue_wait_timeout", 0);
            if (wq > 0) {
                edittext_xt_adv_write_queue_wait.setText(String.valueOf(wq));
            }
            fillTimeoutSeconds(o, "dial_timeout", edittext_xt_adv_dial_timeout);
            fillTimeoutSeconds(o, "handshake_timeout", edittext_xt_adv_handshake_timeout);
            fillTimeoutSeconds(o, "read_timeout", edittext_xt_adv_read_timeout);
            fillTimeoutSeconds(o, "write_timeout", edittext_xt_adv_write_timeout);
            fillTimeoutSeconds(o, "ping_interval", edittext_xt_adv_ping_interval);
            fillTimeoutSeconds(o, "reconnect_delay", edittext_xt_adv_reconnect_delay);
            fillTimeoutSeconds(o, "connect_timeout", edittext_xt_adv_connect_timeout);
            int max = o.optInt("max_socks5_connections", -1);
            if (max >= 0) {
                edittext_xt_adv_max_socks5.setText(String.valueOf(max));
            }
            String ports = o.optString("udp_blocked_ports", "");
            if (!ports.isEmpty()) {
                edittext_xt_adv_udp_ports.setText(ports);
            }
        } catch (JSONException ignored) {
            // 损坏数据忽略，使用默认值
        }
    }

    // 毫秒 -> 秒填充输入框
    private void fillTimeoutSeconds(JSONObject o, String key, EditText input) {
        long ms = o.optLong(key, 0);
        if (ms > 0) {
            input.setText(String.valueOf(ms / 1000));
        }
    }

    // ======================== 导入功能 ========================

    private void showImportDialog() {
        // 显示两个选项的对话框：手动输入和扫描 QR
        final CharSequence[] options = {
                getString(R.string.dialog_option_manual), getString(R.string.dialog_option_scan)};
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_import)
                .setView(input)
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
        if (request == REQUEST_SCAN_QR) {
            if (result == RESULT_OK && data != null) {
                String scannedText = data.getStringExtra(com.google.zxing.client.android.Intents.Scan.RESULT);
                if (scannedText == null) {
                    scannedText = data.getStringExtra("SCAN_RESULT");
                }
                if (scannedText != null) {
                    importFromProtocol(scannedText);
                }
            }
        }
    }

    private void importFromProtocol(String protocol) {
        if (preferredLocked()) {
            Toast.makeText(this, "当前节点正在运行，不能导入覆盖", Toast.LENGTH_LONG).show();
            return;
        }
        if (!preferredLoaded) {
            // A scan result can arrive before asynchronous pool loading after recreation.
            pendingPreferredImport = protocol;
            return;
        }
        // 支持 gcm:// / ech://（兼容）和 xtunnel:// 前缀
        boolean isXtunnel = protocol.startsWith("xtunnel://");
        if (!isXtunnel && !protocol.startsWith("gcm://") && !protocol.startsWith("ech://")) {
            Toast.makeText(this, getString(R.string.toast_invalid_protocol), Toast.LENGTH_SHORT).show();
            return;
        }
        int schemeLen = isXtunnel ? 10 : 6; // "xtunnel://" 为 10 个字符
        String rest = protocol.substring(schemeLen); // 去掉 scheme 前缀
        // 分离 fragment
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        String wssAddr;
        String query = "";
        int qmark = rest.indexOf('?');
        if (qmark >= 0) {
            wssAddr = rest.substring(0, qmark);
            query = rest.substring(qmark + 1);
        } else {
            wssAddr = rest;
        }
        // 容错：剥离 host 前的多余斜杠（旧版分享链接可能多一个 /）
        while (wssAddr.startsWith("/")) {
            wssAddr = wssAddr.substring(1);
        }
        // 确保 wss:// 前缀
        if (!wssAddr.startsWith("wss://") && !wssAddr.startsWith("ws://")) {
            wssAddr = "wss://" + wssAddr;
        }
        // 拒绝缺少服务器主机的链接（如旧版 xtunnel://?token=... 无 host）
        if (wssAddr.startsWith("wss://") && wssAddr.length() <= 6) {
            Toast.makeText(this, getString(R.string.toast_import_missing_host), Toast.LENGTH_LONG).show();
            return;
        }

        // 解析查询参数
        String prefIp = "";
        String fallbackIp = "";
        String userId = "";
        int wsConn = Preferences.DEFAULT_WS_CONN;
        boolean enableDynamicPool = false;
        int dynamicPoolMax = Preferences.DEFAULT_DYNAMIC_POOL_MAX;
        String token = "";
        String relayNodes = "";
        int connections = Preferences.DEFAULT_XT_CONNECTIONS;
        boolean disableEch = false;
        boolean insecure = false;
        boolean enableHotPair = false;
        int hotPairCount = Preferences.DEFAULT_XT_HOT_PAIR_COUNT;
        String ipStrategy = Preferences.DEFAULT_XT_IP_STRATEGY;
        if (!query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0];
                    String value = kv[1];
                    switch (key) {
                        case "ip":
                        case "relay":
                            prefIp = value;
                            break;
                        case "fip":
                        case "fallbackip":
                            fallbackIp = value;
                            break;
                        case "token":
                        case "user_id":
                            if (isXtunnel) {
                                token = value;
                            } else {
                                userId = value;
                            }
                            break;
                        case "ws_conn":
                            try {
                                wsConn = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "enable_dynamic_pool":
                            enableDynamicPool = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "dynamic_pool_max":
                            try {
                                dynamicPoolMax = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "relay_nodes":
                            relayNodes = value;
                            break;
                        case "connections":
                            try {
                                connections = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "ech":
                            // ech=0 表示禁用 ECH（与 GCM 的 disable_ech 语义一致）
                            disableEch = value.equals("0") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no");
                            break;
                        // 兼容旧 URI：domain/dns 参数忽略，ECH 域名与 DoH 服务器复用全局设置
                        case "domain":
                        case "dns":
                            break;
                        case "insecure":
                            insecure = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "hotpair":
                            // hotpair=1/true/yes 兼容旧格式（启用 1 对）；hotpair=2..8 表示启用 N 对
                            int hotPairValue = 1;
                            try {
                                hotPairValue = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            if (hotPairValue >= 2 && hotPairValue <= Preferences.MAX_XT_HOT_PAIR_COUNT) {
                                enableHotPair = true;
                                hotPairCount = hotPairValue;
                            } else {
                                enableHotPair = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                                hotPairCount = 1;
                            }
                            break;
                        case "ips":
                            // IP 策略（4,6/6,4/4/6；default 为 v1.3.1 存量兼容值）；缺失回落默认，非法值忽略
                            if (Preferences.isValidXtIpStrategy(value)) {
                                ipStrategy = value;
                            }
                            break;
                    }
                }
            }
        }

        // 从 fragment 解码配置名称
        String profileName = "";
        if (!fragment.isEmpty()) {
            try {
                profileName = java.net.URLDecoder.decode(fragment, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                profileName = fragment;
            }
        }

        // Confirm before changing any form field or mode. Re-enter only after the draft
        // no longer contains these overrides; cancellation leaves the whole editor intact.
        if (isXtunnel && !confirmXtImportOverrides(protocol)) return;
        // A first import with no explicit mode is NOT a user request to opt out forever.
        // Keep the automatic mode; before activation it uses the URI's original addresses,
        // after activation it still requires this profile's own saved target validation.
        boolean implicitImport = preferredInitialImplicit && !preferredDraftDirty
                && PREFERRED_MODES[preferredMode.getSelectedItemPosition()].equals(preferredOriginalMode);
        preferredImportedAddresses = true;
        if (!implicitImport) {
            preferredDraftDirty = true;
            preferredMode.setSelection(1); // replacing an explicitly configured node is an independent URI snapshot
        }

        if (isXtunnel) {
            // X-Tunnel 配置
            setProtocolSelection(Preferences.PROTOCOL_X_TUNNEL);
            edittext_xt_server_addr.setText(wssAddr);
            edittext_xt_token.setText(token);
            edittext_xt_relay_nodes.setText(relayNodes); // absent/empty means domain default, not the old IP
            edittext_xt_connections.setText(String.valueOf(connections));
            checkbox_xt_disable_ech.setChecked(disableEch);
            checkbox_xt_insecure.setChecked(insecure);
            spinner_xt_ip_strategy.setSelection(xtIpStrategyIndex(ipStrategy));
            checkbox_xt_enable_hot_pair.setChecked(enableHotPair);
            edittext_xt_hot_pair_count.setText(String.valueOf(hotPairCount));
            setFieldEnabled(edittext_xt_hot_pair_count, enableHotPair);
        } else {
            // GCM 配置
            setProtocolSelection(Preferences.PROTOCOL_GCM);
            edittext_worker_host.setText(wssAddr);
            edittext_pref_ip.setText(prefIp); // complete URI snapshot: empty clears the old entry
            if (!fallbackIp.isEmpty()) {
                edittext_fallback_ip.setText(fallbackIp);
            }
            if (!userId.isEmpty()) {
                edittext_user_id.setText(userId);
            }
            edittext_ws_conn.setText(String.valueOf(wsConn));
            checkbox_enable_dynamic_pool.setChecked(enableDynamicPool);
            edittext_dynamic_pool_max.setText(String.valueOf(dynamicPoolMax));
        }
        if (!profileName.isEmpty()) {
            edittext_profile_name.setText(profileName);
        }

        refreshPreferredConflict(isXtunnel ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM);
        showPreferredSummary();
        Toast.makeText(this, getString(R.string.toast_imported_check), Toast.LENGTH_LONG).show();
    }

    private boolean confirmXtImportOverrides(String protocol) {
        try {
            JSONObject draft = parseXtAdvancedDraft();
            java.util.List<String> conflicts = new java.util.ArrayList<>();
            // These are supplied by the URI or its defaults. Legacy domain/dns URI fields
            // use global settings, so their old advanced overrides must also be cleared.
            for (String key : new String[]{"server_addr", "token", "relay_nodes", "connections",
                    "enable_ech", "ech_domain", "dns_server", "insecure", "ip_strategy",
                    "enable_hot_pair", "hot_pair_count"}) {
                if (draft.has(key)) conflicts.add(key);
            }
            if (conflicts.isEmpty()) return true;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setMessage("导入将删除高级JSON中的以下覆盖键，使用链接字段及默认值（ECH域名/DNS使用全局设置）：\n"
                            + android.text.TextUtils.join(", ", conflicts)
                            + "\n其他高级参数保留；取消不会修改当前编辑内容。确认后仍需保存才生效。")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (d, w) -> {
                        if (isFinishing() || isDestroyed() || preferredLocked()) return;
                        for (String key : conflicts) draft.remove(key);
                        xtAdvancedDraft = draft.toString();
                        preferredDraftDirty = true;
                        refreshPreferredConflict(getSelectedProtocol());
                        importFromProtocol(protocol);
                    }).show();
        } catch (JSONException error) {
            Toast.makeText(this, R.string.error_xt_advanced_invalid, Toast.LENGTH_LONG).show();
        }
        return false;
    }
}
