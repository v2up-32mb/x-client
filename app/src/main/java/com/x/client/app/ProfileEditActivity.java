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
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.ScanOptions;

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

        // 恢复原配置
        prefs.setCurrentProfileId(originalId);

        return true;
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
        int schemeLen = isXtunnel ? 9 : 6;
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
        // 确保 wss:// 前缀
        if (!wssAddr.startsWith("wss://") && !wssAddr.startsWith("ws://")) {
            wssAddr = "wss://" + wssAddr;
        }

        // 解析查询参数
        String prefIp = "";
        String fallbackIp = "";
        String userId = "";
        String token = "";
        String relayNodes = "";
        int connections = Preferences.DEFAULT_XT_CONNECTIONS;
        boolean disableEch = false;
        boolean insecure = false;
        boolean enableHotPair = false;
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
                            token = value;
                            userId = value;
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
                            enableHotPair = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
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
        }
        if (!profileName.isEmpty()) {
            edittext_profile_name.setText(profileName);
        }

        Toast.makeText(this, "配置已导入，请检查并保存", Toast.LENGTH_LONG).show();
    }
}
