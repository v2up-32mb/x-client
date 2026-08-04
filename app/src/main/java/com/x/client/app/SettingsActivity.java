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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import xclient.Xclient;

public class SettingsActivity extends AppCompatActivity {
    private Preferences prefs;

    private CheckBox checkbox_global;
    private Button button_apps;
    private EditText edittext_socks_port;
    private CheckBox checkbox_bypass_private;
    private CheckBox checkbox_bypass_geoip_cn;
    private CheckBox checkbox_bypass_geosite_cn;
    private EditText edittext_bypass_rules;
    private EditText edittext_ech_dns;
    private EditText edittext_ech_domain;
    private CheckBox checkbox_enable_dns_warmup;
    private Button btn_save;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);
        setContentView(R.layout.activity_settings);

        // 设置标题
        setTitle("全局设置");

        // 初始化控件
        checkbox_global = findViewById(R.id.checkbox_global);
        button_apps = findViewById(R.id.button_apps);
        edittext_socks_port = findViewById(R.id.edittext_socks_port);
        checkbox_bypass_private = findViewById(R.id.checkbox_bypass_private);
        checkbox_bypass_geoip_cn = findViewById(R.id.checkbox_bypass_geoip_cn);
        checkbox_bypass_geosite_cn = findViewById(R.id.checkbox_bypass_geosite_cn);
        edittext_bypass_rules = findViewById(R.id.edittext_bypass_rules);
        edittext_ech_dns = findViewById(R.id.edittext_ech_dns);
        edittext_ech_domain = findViewById(R.id.edittext_ech_domain);
        checkbox_enable_dns_warmup = findViewById(R.id.checkbox_enable_dns_warmup);
        btn_save = findViewById(R.id.btn_save);

        // 加载当前设置
        loadSettings();

        // 设置选择应用按钮点击事件（始终可用）
        button_apps.setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });

        // 设置保存按钮点击事件
        btn_save.setOnClickListener(v -> {
            if (saveSettings()) {
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void loadSettings() {
        // 加载全局设置
        checkbox_global.setChecked(prefs.getGlobal());
        edittext_socks_port.setText(String.valueOf(prefs.getSocksPort()));
        checkbox_bypass_private.setChecked(prefs.getBypassPrivate());
        checkbox_bypass_geoip_cn.setChecked(prefs.getBypassGeoIpCn());
        checkbox_bypass_geosite_cn.setChecked(prefs.getBypassGeoSiteCn());
        edittext_bypass_rules.setText(prefs.getBypassRules());
        edittext_ech_dns.setText(prefs.getEchDns());
        edittext_ech_domain.setText(prefs.getEchDomain());
        checkbox_enable_dns_warmup.setChecked(prefs.getEnableDnsWarmup());

        // 检查 VPN 是否正在运行
        boolean isVpnRunning = prefs.getEnable();
        if (isVpnRunning) {
            // VPN 运行时禁用所有全局设置的修改
            checkbox_global.setEnabled(false);
            button_apps.setEnabled(false);
            edittext_socks_port.setEnabled(false);
            checkbox_bypass_private.setEnabled(false);
            checkbox_bypass_geoip_cn.setEnabled(false);
            checkbox_bypass_geosite_cn.setEnabled(false);
            edittext_bypass_rules.setEnabled(false);
            edittext_ech_dns.setEnabled(false);
            edittext_ech_domain.setEnabled(false);
            checkbox_enable_dns_warmup.setEnabled(false);
            btn_save.setEnabled(false);

            Toast.makeText(this, "VPN 正在运行，无法修改全局设置", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (prefs.getEnable() || saveSettings()) {
            super.onBackPressed();
        }
    }

    private boolean saveSettings() {
        // 验证并保存端口
        int port = 1080;
        try {
            port = Integer.parseInt(edittext_socks_port.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "端口号格式错误", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (port < 1024) {
            Toast.makeText(this, "端口号必须 ≥ 1024", Toast.LENGTH_SHORT).show();
            return false;
        }

        String bypassRules = edittext_bypass_rules.getText().toString().trim();
        try {
            Xclient.validateBypassRules(bypassRules);
        } catch (Exception e) {
            Toast.makeText(this, "绕过规则格式错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

        return true;
    }
}
