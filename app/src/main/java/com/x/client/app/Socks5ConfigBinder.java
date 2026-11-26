/*
 ============================================================================
 Name        : Socks5ConfigBinder.java
 Description : SOCKS5 Protocol Form Binder and Validator
 ============================================================================
 */

package com.x.client.app;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.nio.charset.StandardCharsets;

/**
 * SOCKS5 协议表单控件绑定、数据映射与字段校验器。
 */
public class Socks5ConfigBinder implements ProtocolConfigBinder {

    private final Activity activity;

    private LinearLayout socks5ConnectionFields;

    private EditText editS5Host;
    private EditText editS5Port;
    private EditText editS5Username;
    private EditText editS5Password;

    public Socks5ConfigBinder(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void bindViews(View root) {
        socks5ConnectionFields = root.findViewById(R.id.socks5_connection_fields);

        editS5Host = root.findViewById(R.id.s5_host);
        editS5Port = root.findViewById(R.id.s5_port);
        editS5Username = root.findViewById(R.id.s5_username);
        editS5Password = root.findViewById(R.id.s5_password);
    }

    @Override
    public void loadFromPreferences(Preferences prefs) {
        editS5Host.setText(prefs.getS5Host());
        editS5Port.setText(String.valueOf(prefs.getS5Port()));
        editS5Username.setText(prefs.getS5Username());
        editS5Password.setText(prefs.getS5Password());
    }

    @Override
    public void applyImport(ProfileUriCodec.ParsedProfile parsed) {
        editS5Host.setText(parsed.s5Host);
        editS5Port.setText(String.valueOf(parsed.s5Port));
        editS5Username.setText(parsed.s5Username);
        editS5Password.setText(parsed.s5Password);
    }

    @Override
    public boolean validateAndSave(Preferences prefs, boolean saveRelay) {
        String host = editS5Host.getText().toString().trim();
        if (host.isEmpty()) {
            FormUtils.showFieldError(editS5Host, activity.getString(R.string.error_s5_host_empty));
            return false;
        }
        if (!isValidHostSyntax(host)) {
            FormUtils.showFieldError(editS5Host, activity.getString(R.string.error_s5_host_invalid));
            return false;
        }

        int port = Preferences.DEFAULT_S5_PORT;
        String portText = editS5Port.getText().toString().trim();
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                FormUtils.showFieldError(editS5Port, activity.getString(R.string.error_s5_port_invalid));
                return false;
            }
            if (port < 1 || port > 65535) {
                FormUtils.showFieldError(editS5Port, activity.getString(R.string.error_s5_port_invalid));
                return false;
            }
        }

        String username = editS5Username.getText().toString().trim();
        String password = editS5Password.getText().toString();
        if (!password.isEmpty() && username.isEmpty()) {
            FormUtils.showFieldError(editS5Username, activity.getString(R.string.error_s5_username_required));
            return false;
        }
        if (username.getBytes(StandardCharsets.UTF_8).length > 255) {
            FormUtils.showFieldError(editS5Username, activity.getString(R.string.error_s5_credential_too_long));
            return false;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 255) {
            FormUtils.showFieldError(editS5Password, activity.getString(R.string.error_s5_credential_too_long));
            return false;
        }

        prefs.setS5Host(host);
        prefs.setS5Port(port);
        prefs.setS5Username(username);
        prefs.setS5Password(password);
        return true;
    }

    private static boolean isValidHostSyntax(String host) {
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                return false;
            }
        }
        if (host.startsWith("[")) {
            if (!host.endsWith("]")) {
                return false;
            }
            if (host.indexOf('[', 1) != -1 || host.indexOf(']') != host.length() - 1) {
                return false;
            }
            return !host.substring(1, host.length() - 1).trim().isEmpty();
        }
        if (host.indexOf('[') != -1 || host.indexOf(']') != -1) {
            return false;
        }
        int firstColon = host.indexOf(':');
        int lastColon = host.lastIndexOf(':');
        return firstColon == -1 || firstColon != lastColon;
    }

    @Override
    public void setEnabled(boolean enabled) {
        FormUtils.setFieldEnabled(editS5Host, enabled);
        FormUtils.setFieldEnabled(editS5Port, enabled);
        FormUtils.setFieldEnabled(editS5Username, enabled);
        FormUtils.setFieldEnabled(editS5Password, enabled);
    }

    @Override
    public void setRelayEnabled(boolean independent) {
        // SOCKS5 不使用优选中转节点
    }

    @Override
    public void clearErrors() {
        FormUtils.clearFieldError(editS5Host);
        FormUtils.clearFieldError(editS5Port);
        FormUtils.clearFieldError(editS5Username);
        FormUtils.clearFieldError(editS5Password);
    }

    @Override
    public void setVisible(boolean visible) {
        if (socks5ConnectionFields != null) {
            socks5ConnectionFields.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
