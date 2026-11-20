/*
 ============================================================================
 Name        : GcmConfigBinder.java
 Description : GCM Protocol Form Binder and Validator
 ============================================================================
 */

package com.x.client.app;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * GCM 协议表单控件绑定、数据映射与字段校验器。
 */
public class GcmConfigBinder implements ProtocolConfigBinder {

    private final Activity activity;

    private LinearLayout gcmConnectionFields;
    private LinearLayout gcmAdvancedFields;

    private EditText editWorkerHost;
    private EditText editPrefIp;
    private EditText editUserId;
    private EditText editFallbackIp;
    private CheckBox checkDisableEch;
    private CheckBox checkDisableIpv6Route;
    private EditText editWsConn;
    private CheckBox checkEnableDynamicPool;
    private EditText editDynamicPoolMax;

    public GcmConfigBinder(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void bindViews(View root) {
        gcmConnectionFields = root.findViewById(R.id.gcm_connection_fields);
        gcmAdvancedFields = root.findViewById(R.id.gcm_advanced_fields);

        editWorkerHost = root.findViewById(R.id.worker_host);
        editPrefIp = root.findViewById(R.id.pref_ip);
        editUserId = root.findViewById(R.id.user_id);
        editFallbackIp = root.findViewById(R.id.fallback_ip);
        checkDisableEch = root.findViewById(R.id.disable_ech);
        checkDisableIpv6Route = root.findViewById(R.id.disable_ipv6_route);
        editWsConn = root.findViewById(R.id.ws_conn);
        checkEnableDynamicPool = root.findViewById(R.id.enable_dynamic_pool);
        editDynamicPoolMax = root.findViewById(R.id.dynamic_pool_max);

        // 附加 TV 焦点反馈
        TvFocusHelper.applyFocusFeedback(checkDisableEch, checkDisableIpv6Route, checkEnableDynamicPool);
    }

    @Override
    public void loadFromPreferences(Preferences prefs) {
        editWorkerHost.setText(prefs.getWorkerHost());
        editPrefIp.setText(prefs.getPrefIp());
        editUserId.setText(prefs.getUserID());
        editFallbackIp.setText(prefs.getFallbackIp());
        checkDisableEch.setChecked(prefs.getDisableEch());
        checkDisableIpv6Route.setChecked(prefs.getDisableIpv6Route());
        editWsConn.setText(String.valueOf(prefs.getWsConn()));
        checkEnableDynamicPool.setChecked(prefs.getEnableDynamicPool());
        editDynamicPoolMax.setText(String.valueOf(prefs.getDynamicPoolMax()));
    }

    @Override
    public void applyImport(ProfileUriCodec.ParsedProfile parsed) {
        editWorkerHost.setText(parsed.serverAddr);
        editPrefIp.setText(parsed.prefIp);
        if (!parsed.fallbackIp.isEmpty()) {
            editFallbackIp.setText(parsed.fallbackIp);
        }
        if (!parsed.userId.isEmpty()) {
            editUserId.setText(parsed.userId);
        }
        editWsConn.setText(String.valueOf(parsed.wsConn));
        checkEnableDynamicPool.setChecked(parsed.enableDynamicPool);
        editDynamicPoolMax.setText(String.valueOf(parsed.dynamicPoolMax));
        checkDisableEch.setChecked(parsed.disableEch);
    }

    @Override
    public boolean validateAndSave(Preferences prefs, boolean saveRelay) {
        String workerHost = editWorkerHost.getText().toString().trim();
        if (workerHost.isEmpty()) {
            FormUtils.showFieldError(editWorkerHost, activity.getString(R.string.error_server_addr_empty));
            return false;
        }

        // 校验 WebSocket 连接数
        int wsConn = Preferences.DEFAULT_WS_CONN;
        String wsConnText = editWsConn.getText().toString().trim();
        try {
            wsConn = wsConnText.isEmpty() ? Preferences.DEFAULT_WS_CONN : Integer.parseInt(wsConnText);
        } catch (NumberFormatException e) {
            FormUtils.showFieldError(editWsConn, activity.getString(R.string.error_ws_conn_invalid));
            return false;
        }
        if (wsConn < 1 || wsConn > Preferences.MAX_DYNAMIC_POOL_LIMIT) {
            FormUtils.showFieldError(editWsConn,
                    activity.getString(R.string.error_ws_conn_range, Preferences.MAX_DYNAMIC_POOL_LIMIT));
            return false;
        }

        // 校验动态扩容连接池上限
        int dynamicPoolMax = Preferences.DEFAULT_DYNAMIC_POOL_MAX;
        String limitText = editDynamicPoolMax.getText().toString().trim();
        try {
            dynamicPoolMax = limitText.isEmpty() ? Preferences.DEFAULT_DYNAMIC_POOL_MAX : Integer.parseInt(limitText);
        } catch (NumberFormatException e) {
            FormUtils.showFieldError(editDynamicPoolMax, activity.getString(R.string.error_pool_max_invalid));
            return false;
        }
        if (dynamicPoolMax > Preferences.MAX_DYNAMIC_POOL_LIMIT
                || (checkEnableDynamicPool.isChecked() && dynamicPoolMax < wsConn)) {
            FormUtils.showFieldError(editDynamicPoolMax,
                    activity.getString(R.string.error_pool_max_range, Preferences.MAX_DYNAMIC_POOL_LIMIT));
            return false;
        }

        // 持久化保存
        prefs.setWorkerHost(workerHost);
        if (saveRelay) {
            prefs.setPrefIp(editPrefIp.getText().toString().trim());
        }
        prefs.setUserID(editUserId.getText().toString().trim());
        prefs.setFallbackIp(editFallbackIp.getText().toString().trim());
        prefs.setDisableEch(checkDisableEch.isChecked());
        prefs.setDisableIpv6Route(checkDisableIpv6Route.isChecked());
        prefs.setWsConn(wsConn);
        prefs.setEnableDynamicPool(checkEnableDynamicPool.isChecked());
        prefs.setDynamicPoolMax(dynamicPoolMax);

        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        FormUtils.setFieldEnabled(editWorkerHost, enabled);
        FormUtils.setFieldEnabled(editPrefIp, enabled);
        FormUtils.setFieldEnabled(editUserId, enabled);
        FormUtils.setFieldEnabled(editFallbackIp, enabled);
        checkDisableEch.setEnabled(enabled);
        checkDisableIpv6Route.setEnabled(enabled);
        FormUtils.setFieldEnabled(editWsConn, enabled);
        checkEnableDynamicPool.setEnabled(enabled);
        FormUtils.setFieldEnabled(editDynamicPoolMax, enabled);
    }

    @Override
    public void setRelayEnabled(boolean independent) {
        FormUtils.setFieldEnabled(editPrefIp, independent);
    }

    @Override
    public void clearErrors() {
        FormUtils.clearFieldError(editWorkerHost);
        FormUtils.clearFieldError(editPrefIp);
        FormUtils.clearFieldError(editUserId);
        FormUtils.clearFieldError(editFallbackIp);
        FormUtils.clearFieldError(editWsConn);
        FormUtils.clearFieldError(editDynamicPoolMax);
    }

    @Override
    public void setVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (gcmConnectionFields != null) gcmConnectionFields.setVisibility(v);
        if (gcmAdvancedFields != null) gcmAdvancedFields.setVisibility(v);
    }
}
