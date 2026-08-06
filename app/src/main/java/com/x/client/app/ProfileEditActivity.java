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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.ScanOptions;
import org.json.JSONException;
import org.json.JSONObject;

public class ProfileEditActivity extends AppCompatActivity {
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

    private Button btn_import;
    private Button btn_save;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);

        // 获取要编辑的配置 ID
        profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        isNewProfile = getIntent().getBooleanExtra(EXTRA_IS_NEW_PROFILE, false);
        if (profileId == null) {
            profileId = prefs.getCurrentProfileId();
        }

        setContentView(R.layout.activity_profile_edit);

        // 设置标题
        setTitle("编辑配置: " + prefs.getProfileName(profileId));

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
        // Hot Pair 关闭时数量输入框禁用
        checkbox_xt_enable_hot_pair.setOnCheckedChangeListener((buttonView, isChecked) ->
                edittext_xt_hot_pair_count.setEnabled(isChecked));

        btn_import = findViewById(R.id.btn_import);
        btn_save = findViewById(R.id.btn_save);

        // 协议选择器
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.protocol_gcm), getString(R.string.protocol_x_tunnel)});
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_protocol.setAdapter(protocolAdapter);
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

        // 设置导入按钮点击事件
        btn_import.setOnClickListener(v -> showImportDialog());

        // 设置保存按钮点击事件
        btn_save.setOnClickListener(v -> {
            if (savePrefs()) {
                hasBeenSaved = true;
                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void updateFieldVisibility(String protocol) {
        boolean isXtunnel = Preferences.PROTOCOL_X_TUNNEL.equals(protocol);
        gcm_fields.setVisibility(isXtunnel ? View.GONE : View.VISIBLE);
        xtunnel_fields.setVisibility(isXtunnel ? View.VISIBLE : View.GONE);
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
        // 临时切换到目标配置以读取数据
        String originalId = prefs.getCurrentProfileId();
        prefs.setCurrentProfileId(profileId);

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
        checkbox_xt_enable_hot_pair.setChecked(prefs.getXtEnableHotPair());
        edittext_xt_hot_pair_count.setText(String.valueOf(prefs.getXtHotPairCount()));
        edittext_xt_hot_pair_count.setEnabled(prefs.getXtEnableHotPair());
        loadXtAdvancedParams(prefs.getXtAdvancedParams());

        // 恢复原配置
        prefs.setCurrentProfileId(originalId);

        // 检查是否是当前激活的配置且 VPN 正在运行
        boolean isVpnRunning = prefs.getEnable();
        boolean isCurrentProfile = profileId.equals(prefs.getCurrentProfileId());

        if (isVpnRunning && isCurrentProfile) {
            // VPN 运行时禁用当前配置的修改
            edittext_profile_name.setEnabled(false);
            edittext_worker_host.setEnabled(false);
            edittext_pref_ip.setEnabled(false);
            edittext_user_id.setEnabled(false);
            edittext_fallback_ip.setEnabled(false);
            checkbox_disable_ech.setEnabled(false);
            checkbox_disable_ipv6_route.setEnabled(false);
            edittext_ws_conn.setEnabled(false);
            checkbox_enable_dynamic_pool.setEnabled(false);
            edittext_dynamic_pool_max.setEnabled(false);
            edittext_xt_server_addr.setEnabled(false);
            edittext_xt_token.setEnabled(false);
            edittext_xt_relay_nodes.setEnabled(false);
            edittext_xt_connections.setEnabled(false);
            checkbox_xt_disable_ech.setEnabled(false);
            checkbox_xt_insecure.setEnabled(false);
            checkbox_xt_enable_hot_pair.setEnabled(false);
            edittext_xt_hot_pair_count.setEnabled(false);
            edittext_xt_adv_backpressure.setEnabled(false);
            edittext_xt_adv_write_queue_wait.setEnabled(false);
            edittext_xt_adv_dial_timeout.setEnabled(false);
            edittext_xt_adv_handshake_timeout.setEnabled(false);
            edittext_xt_adv_read_timeout.setEnabled(false);
            edittext_xt_adv_write_timeout.setEnabled(false);
            edittext_xt_adv_ping_interval.setEnabled(false);
            edittext_xt_adv_reconnect_delay.setEnabled(false);
            edittext_xt_adv_connect_timeout.setEnabled(false);
            edittext_xt_adv_max_socks5.setEnabled(false);
            edittext_xt_adv_udp_ports.setEnabled(false);
            spinner_protocol.setEnabled(false);
            btn_save.setEnabled(false);

            Toast.makeText(this, "VPN 正在运行，无法修改当前配置", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        // 如果是新配置且未保存，删除该配置
        if (isNewProfile && !hasBeenSaved) {
            prefs.removeProfile(profileId);
        }
        super.onBackPressed();
    }

    private boolean savePrefs() {
        // 验证配置名称不为空
        String profileName = edittext_profile_name.getText().toString().trim();
        if (profileName.isEmpty()) {
            Toast.makeText(this, "配置名称不能为空", Toast.LENGTH_SHORT).show();
            return false;
        }

        // 检查配置名称是否重复（排除当前配置）
        if (prefs.profileNameExists(profileName, profileId)) {
            Toast.makeText(this, "配置名称已存在", Toast.LENGTH_SHORT).show();
            return false;
        }

        String protocol = getSelectedProtocol();

        // 按协议验证必填字段
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            String serverAddr = edittext_xt_server_addr.getText().toString().trim();
            if (serverAddr.isEmpty()) {
                Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!serverAddr.startsWith("wss://") && !serverAddr.startsWith("ws://")) {
                Toast.makeText(this, "服务器地址必须以 wss:// 或 ws:// 开头", Toast.LENGTH_SHORT).show();
                return false;
            }
        } else {
            String workerHost = edittext_worker_host.getText().toString().trim();
            if (workerHost.isEmpty()) {
                Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "WebSocket 连接数格式错误", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (wsConn < 1 || wsConn > Preferences.MAX_DYNAMIC_POOL_LIMIT) {
                Toast.makeText(this, "WebSocket 连接数必须在 1-" + Preferences.MAX_DYNAMIC_POOL_LIMIT + " 之间", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        int dynamicPoolMax = Preferences.DEFAULT_DYNAMIC_POOL_MAX;
        if (Preferences.PROTOCOL_GCM.equals(protocol)) {
            String limitText = edittext_dynamic_pool_max.getText().toString().trim();
            try {
                dynamicPoolMax = limitText.isEmpty() ? Preferences.DEFAULT_DYNAMIC_POOL_MAX : Integer.parseInt(limitText);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "动态扩容上限格式错误", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (dynamicPoolMax > Preferences.MAX_DYNAMIC_POOL_LIMIT
                    || (checkbox_enable_dynamic_pool.isChecked() && dynamicPoolMax < wsConn)) {
                Toast.makeText(this, "启用动态扩容时，上限必须在 WebSocket 连接数和 "
                        + Preferences.MAX_DYNAMIC_POOL_LIMIT + " 之间", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "连接数必须是数字", Toast.LENGTH_SHORT).show();
                return false;
            }
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
                Toast.makeText(this, "热通道对数必须是数字", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (xtHotPairCount < 1 || xtHotPairCount > Preferences.MAX_XT_HOT_PAIR_COUNT) {
                Toast.makeText(this, "热通道对数必须在 1-" + Preferences.MAX_XT_HOT_PAIR_COUNT + " 之间", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        // 收集并校验 X-Tunnel 高级参数（每项留空表示使用默认值）
        String xtAdvancedParams = "";
        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
            xtAdvancedParams = collectXtAdvancedParams();
            if (xtAdvancedParams == null) {
                Toast.makeText(this, "高级参数数值无效，请检查输入（留空表示使用默认值）", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        // 临时切换到目标配置以保存数据
        String originalId = prefs.getCurrentProfileId();
        prefs.setCurrentProfileId(profileId);

        // 保存配置名称
        prefs.setProfileName(profileId, profileName);

        // 保存协议
        prefs.setProtocol(protocol);

        // 保存 GCM 配置级参数（保留，切换协议时不丢失）
        prefs.setWorkerHost(edittext_worker_host.getText().toString().trim());
        prefs.setPrefIp(edittext_pref_ip.getText().toString().trim());
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
        prefs.setXtRelayNodes(edittext_xt_relay_nodes.getText().toString().trim());
        prefs.setXtConnections(xtConnections);
        prefs.setXtDisableEch(checkbox_xt_disable_ech.isChecked());
        prefs.setXtInsecure(checkbox_xt_insecure.isChecked());
        prefs.setXtEnableHotPair(checkbox_xt_enable_hot_pair.isChecked());
        prefs.setXtHotPairCount(xtHotPairCount);
        prefs.setXtAdvancedParams(xtAdvancedParams);

        // 恢复原配置
        prefs.setCurrentProfileId(originalId);

        return true;
    }

    // ======================== X-Tunnel 高级参数 ========================

    // 从各输入框收集高级参数并组装 JSON；数值非法时返回 null（留空项不写入，使用默认值）
    private String collectXtAdvancedParams() {
        try {
            JSONObject adv = new JSONObject();
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
            return adv.length() > 0 ? adv.toString() : "";
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
        final CharSequence[] options = {"手动输入", "扫描二维码"};
        new AlertDialog.Builder(this)
                .setTitle("导入配置")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showManualInputDialog();
                    } else if (which == 1) {
                        scanQrCode();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualInputDialog() {
        final EditText input = new EditText(this);
        input.setHint("gcm://server.com?ip=1.1.1.1:443&fip=2.2.2.2&user_id=v2up#Name\nxtunnel://server:8443?token=t&relay=r1.com:443#Name");
        new AlertDialog.Builder(this)
                .setTitle("导入配置")
                .setView(input)
                .setPositiveButton("确定", (dialog, whichButton) -> {
                    String protocol = input.getText().toString().trim();
                    if (!protocol.isEmpty()) {
                        importFromProtocol(protocol);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void scanQrCode() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("扫描配置二维码");
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(CustomCaptureActivity.class);
        try {
            Intent intent = options.createScanIntent(this);
            startActivityForResult(intent, REQUEST_SCAN_QR);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动扫描器", Toast.LENGTH_SHORT).show();
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
        // 支持 gcm:// / ech://（兼容）和 xtunnel:// 前缀
        boolean isXtunnel = protocol.startsWith("xtunnel://");
        if (!isXtunnel && !protocol.startsWith("gcm://") && !protocol.startsWith("ech://")) {
            Toast.makeText(this, "无效的协议格式", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "链接缺少服务器地址，无法导入", Toast.LENGTH_LONG).show();
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

        if (isXtunnel) {
            // X-Tunnel 配置
            setProtocolSelection(Preferences.PROTOCOL_X_TUNNEL);
            edittext_xt_server_addr.setText(wssAddr);
            if (!token.isEmpty()) edittext_xt_token.setText(token);
            if (!relayNodes.isEmpty()) edittext_xt_relay_nodes.setText(relayNodes);
            edittext_xt_connections.setText(String.valueOf(connections));
            checkbox_xt_disable_ech.setChecked(disableEch);
            checkbox_xt_insecure.setChecked(insecure);
            checkbox_xt_enable_hot_pair.setChecked(enableHotPair);
            edittext_xt_hot_pair_count.setText(String.valueOf(hotPairCount));
            edittext_xt_hot_pair_count.setEnabled(enableHotPair);
        } else {
            // GCM 配置
            setProtocolSelection(Preferences.PROTOCOL_GCM);
            edittext_worker_host.setText(wssAddr);
            if (!prefIp.isEmpty()) {
                edittext_pref_ip.setText(prefIp);
            }
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

        Toast.makeText(this, "配置已导入，请检查并保存", Toast.LENGTH_LONG).show();
    }
}
