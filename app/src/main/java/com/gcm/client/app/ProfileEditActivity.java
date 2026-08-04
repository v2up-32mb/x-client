/*
 ============================================================================
 Name        : ProfileEditActivity.java
 Author      : Claude Code
 Description : Profile Edit Activity (Edit Single Profile)
 ============================================================================
 */

package com.gcm.client.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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

    private EditText edittext_profile_name;
    private EditText edittext_worker_host;
    private EditText edittext_pref_ip;
    private EditText edittext_user_id;
    private EditText edittext_fallback_ip;
    private CheckBox checkbox_disable_ech;
    private CheckBox checkbox_disable_ipv6_route;
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
        edittext_profile_name = findViewById(R.id.profile_name);
        edittext_worker_host = findViewById(R.id.worker_host);
        edittext_pref_ip = findViewById(R.id.pref_ip);
        edittext_user_id = findViewById(R.id.user_id);
        edittext_fallback_ip = findViewById(R.id.fallback_ip);
        checkbox_disable_ech = findViewById(R.id.disable_ech);
        checkbox_disable_ipv6_route = findViewById(R.id.disable_ipv6_route);
        btn_import = findViewById(R.id.btn_import);
        btn_save = findViewById(R.id.btn_save);

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

    private void loadProfileData() {
        // 临时切换到目标配置以读取数据
        String originalId = prefs.getCurrentProfileId();
        prefs.setCurrentProfileId(profileId);

        // 加载配置名称
        edittext_profile_name.setText(prefs.getProfileName(profileId));

        // 加载配置级参数
        edittext_worker_host.setText(prefs.getWorkerHost());
        edittext_pref_ip.setText(prefs.getPrefIp());
        edittext_user_id.setText(prefs.getUserID());
        edittext_fallback_ip.setText(prefs.getFallbackIp());
        checkbox_disable_ech.setChecked(prefs.getDisableEch());
        checkbox_disable_ipv6_route.setChecked(prefs.getDisableIpv6Route());

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

        // 验证 WSS 地址不为空
        String workerHost = edittext_worker_host.getText().toString().trim();
        if (workerHost.isEmpty()) {
            Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
            return false;
        }

        // 临时切换到目标配置以保存数据
        String originalId = prefs.getCurrentProfileId();
        prefs.setCurrentProfileId(profileId);

        // 保存配置名称
        prefs.setProfileName(profileId, profileName);

        // 保存配置级参数
        prefs.setWorkerHost(workerHost);
        prefs.setPrefIp(edittext_pref_ip.getText().toString().trim());
        prefs.setUserID(edittext_user_id.getText().toString().trim());
        prefs.setFallbackIp(edittext_fallback_ip.getText().toString().trim());

        prefs.setDisableEch(checkbox_disable_ech.isChecked());
        prefs.setDisableIpv6Route(checkbox_disable_ipv6_route.isChecked());

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
        input.setHint("gcm://server.com?ip=1.1.1.1:443&fip=2.2.2.2&user_id=v2up#Name");
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
        // 支持 gcm:// 和 ech:// 前缀
        if (!protocol.startsWith("gcm://") && !protocol.startsWith("ech://")) {
            Toast.makeText(this, "无效的协议格式", Toast.LENGTH_SHORT).show();
            return;
        }
        String rest = protocol.substring(6); // 去掉 scheme 前缀
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
        if (!wssAddr.startsWith("wss://")) {
            wssAddr = "wss://" + wssAddr;
        }

        // 解析配置级参数：ip= 优选中转节点，fip= 出口代理 IP，user_id= 用户标识。
        // 旧文档中的 dns/domain 参数不再写入 profile，继续保持全局设置。
        String prefIp = "";
        String fallbackIp = "";
        String userId = "";
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
                            userId = value;
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

        // 更新当前配置的字段
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
        if (!profileName.isEmpty()) {
            edittext_profile_name.setText(profileName);
        }

        Toast.makeText(this, "配置已导入，请检查并保存", Toast.LENGTH_LONG).show();
    }
}
