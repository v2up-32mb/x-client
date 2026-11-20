/*
 ============================================================================
 Name        : XtunnelConfigBinder.java
 Description : X-Tunnel Protocol Form Binder, JSON Adapter and Validator
 ============================================================================
 */

package com.x.client.app;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * X-Tunnel 协议表单控件绑定、高级参数 JSON 转换与字段校验器。
 */
public class XtunnelConfigBinder implements ProtocolConfigBinder {

    public static final String[] XT_IP_STRATEGY_VALUES = {"4,6", "6,4", "4", "6"};

    private static final String[] CONFLICT_KEYS = {
            "server_addr", "token", "relay_nodes", "connections",
            "enable_ech", "ech_domain", "dns_server", "insecure", "ip_strategy",
            "enable_hot_pair", "hot_pair_count"
    };

    private final Activity activity;

    private LinearLayout xtunnelConnectionFields;
    private LinearLayout xtunnelAdvancedFields;

    private EditText editXtServerAddr;
    private EditText editXtToken;
    private EditText editXtRelayNodes;
    private EditText editXtConnections;
    private CheckBox checkXtDisableEch;
    private CheckBox checkXtInsecure;
    private Spinner spinnerXtIpStrategy;
    private CheckBox checkXtEnableHotPair;
    private EditText editXtHotPairCount;

    private TextView xtAdvancedHeader;
    private LinearLayout xtAdvancedContainer;
    private EditText editXtAdvBackpressure;
    private EditText editXtAdvWriteQueueWait;
    private EditText editXtAdvDialTimeout;
    private EditText editXtAdvHandshakeTimeout;
    private EditText editXtAdvReadTimeout;
    private EditText editXtAdvWriteTimeout;
    private EditText editXtAdvPingInterval;
    private EditText editXtAdvReconnectDelay;
    private EditText editXtAdvConnectTimeout;
    private EditText editXtAdvMaxSocks5;
    private EditText editXtAdvUdpPorts;

    private String xtAdvancedDraft;

    public XtunnelConfigBinder(Activity activity) {
        this.activity = activity;
    }

    public String getAdvancedDraft() {
        return xtAdvancedDraft;
    }

    public void setAdvancedDraft(String draft) {
        this.xtAdvancedDraft = draft;
    }

    @Override
    public void bindViews(View root) {
        xtunnelConnectionFields = root.findViewById(R.id.xtunnel_connection_fields);
        xtunnelAdvancedFields = root.findViewById(R.id.xtunnel_advanced_fields);

        editXtServerAddr = root.findViewById(R.id.xt_server_addr);
        editXtToken = root.findViewById(R.id.xt_token);
        editXtRelayNodes = root.findViewById(R.id.xt_relay_nodes);
        editXtConnections = root.findViewById(R.id.xt_connections);
        checkXtDisableEch = root.findViewById(R.id.xt_disable_ech);
        checkXtInsecure = root.findViewById(R.id.xt_insecure);
        spinnerXtIpStrategy = root.findViewById(R.id.xt_ip_strategy_spinner);
        checkXtEnableHotPair = root.findViewById(R.id.xt_enable_hot_pair);
        editXtHotPairCount = root.findViewById(R.id.xt_hot_pair_count);

        xtAdvancedHeader = root.findViewById(R.id.xt_advanced_header);
        xtAdvancedContainer = root.findViewById(R.id.xt_advanced_container);
        editXtAdvBackpressure = root.findViewById(R.id.xt_adv_backpressure);
        editXtAdvWriteQueueWait = root.findViewById(R.id.xt_adv_write_queue_wait);
        editXtAdvDialTimeout = root.findViewById(R.id.xt_adv_dial_timeout);
        editXtAdvHandshakeTimeout = root.findViewById(R.id.xt_adv_handshake_timeout);
        editXtAdvReadTimeout = root.findViewById(R.id.xt_adv_read_timeout);
        editXtAdvWriteTimeout = root.findViewById(R.id.xt_adv_write_timeout);
        editXtAdvPingInterval = root.findViewById(R.id.xt_adv_ping_interval);
        editXtAdvReconnectDelay = root.findViewById(R.id.xt_adv_reconnect_delay);
        editXtAdvConnectTimeout = root.findViewById(R.id.xt_adv_connect_timeout);
        editXtAdvMaxSocks5 = root.findViewById(R.id.xt_adv_max_socks5);
        editXtAdvUdpPorts = root.findViewById(R.id.xt_adv_udp_ports);

        // 高级参数折叠逻辑
        xtAdvancedHeader.setText(activity.getString(R.string.xt_advanced_params) + " ▸");
        xtAdvancedHeader.setOnClickListener(v -> {
            boolean visible = xtAdvancedContainer.getVisibility() == View.VISIBLE;
            xtAdvancedContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
            xtAdvancedHeader.setText(activity.getString(R.string.xt_advanced_params) + (visible ? " ▸" : " ▾"));
        });

        // Hot Pair 联动
        checkXtEnableHotPair.setOnCheckedChangeListener((buttonView, isChecked) ->
                FormUtils.setFieldEnabled(editXtHotPairCount, isChecked));

        // IP 策略适配器
        String[] ipStrategyOptions = activity.getResources().getStringArray(R.array.xt_ip_strategy_options);
        String[] ipStrategyDescriptions = activity.getResources().getStringArray(R.array.xt_ip_strategy_descriptions);
        ArrayAdapter<String> ipStrategyAdapter =
                new ArrayAdapter<String>(activity, R.layout.item_spinner_ip_strategy, ipStrategyOptions) {
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
                        : activity.getLayoutInflater().inflate(R.layout.item_spinner_ip_strategy, parent, false);
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
        spinnerXtIpStrategy.setAdapter(ipStrategyAdapter);

        // 附加 TV 焦点反馈
        TvFocusHelper.applyFocusFeedback(checkXtDisableEch, checkXtInsecure, checkXtEnableHotPair, xtAdvancedHeader);
    }

    @Override
    public void loadFromPreferences(Preferences prefs) {
        editXtServerAddr.setText(prefs.getXtServerAddr());
        editXtToken.setText(prefs.getXtToken());
        editXtRelayNodes.setText(prefs.getXtRelayNodes());
        editXtConnections.setText(String.valueOf(prefs.getXtConnections()));
        checkXtDisableEch.setChecked(prefs.getXtDisableEch());
        checkXtInsecure.setChecked(prefs.getXtInsecure());
        spinnerXtIpStrategy.setSelection(xtIpStrategyIndex(prefs.getXtIpStrategy()));
        checkXtEnableHotPair.setChecked(prefs.getXtEnableHotPair());
        editXtHotPairCount.setText(String.valueOf(prefs.getXtHotPairCount()));
        FormUtils.setFieldEnabled(editXtHotPairCount, prefs.getXtEnableHotPair());

        if (xtAdvancedDraft == null) {
            xtAdvancedDraft = prefs.getXtAdvancedParams();
        }
        loadXtAdvancedParams(xtAdvancedDraft);
    }

    @Override
    public void applyImport(ProfileUriCodec.ParsedProfile parsed) {
        editXtServerAddr.setText(parsed.serverAddr);
        editXtToken.setText(parsed.xtToken);
        editXtRelayNodes.setText(parsed.xtRelayNodes);
        editXtConnections.setText(String.valueOf(parsed.xtConnections));
        checkXtDisableEch.setChecked(parsed.xtDisableEch);
        checkXtInsecure.setChecked(parsed.xtInsecure);
        spinnerXtIpStrategy.setSelection(xtIpStrategyIndex(parsed.xtIpStrategy));
        checkXtEnableHotPair.setChecked(parsed.xtEnableHotPair);
        editXtHotPairCount.setText(String.valueOf(parsed.xtHotPairCount));
        FormUtils.setFieldEnabled(editXtHotPairCount, parsed.xtEnableHotPair);
    }

    @Override
    public boolean validateAndSave(Preferences prefs, boolean saveRelay) {
        String serverAddr = editXtServerAddr.getText().toString().trim();
        if (serverAddr.isEmpty()) {
            FormUtils.showFieldError(editXtServerAddr, activity.getString(R.string.error_server_addr_empty));
            return false;
        }
        if (!serverAddr.startsWith("wss://") && !serverAddr.startsWith("ws://")) {
            FormUtils.showFieldError(editXtServerAddr, activity.getString(R.string.error_server_addr_scheme));
            return false;
        }

        // 校验连接数
        int xtConnections = Preferences.DEFAULT_XT_CONNECTIONS;
        String xtConnectionsText = editXtConnections.getText().toString().trim();
        if (!xtConnectionsText.isEmpty()) {
            try {
                xtConnections = Integer.parseInt(xtConnectionsText);
            } catch (NumberFormatException e) {
                FormUtils.showFieldError(editXtConnections, activity.getString(R.string.error_xt_connections_invalid));
                return false;
            }
        }
        if (xtConnections <= 0 || xtConnections > 16) {
            FormUtils.showFieldError(editXtConnections, activity.getString(R.string.error_xt_connections_range));
            return false;
        }

        // 校验 Hot Pair 数量
        int xtHotPairCount = Preferences.DEFAULT_XT_HOT_PAIR_COUNT;
        String xtHotPairCountText = editXtHotPairCount.getText().toString().trim();
        if (checkXtEnableHotPair.isChecked()) {
            if (xtHotPairCountText.isEmpty()) {
                xtHotPairCountText = "1";
            }
            try {
                xtHotPairCount = Integer.parseInt(xtHotPairCountText);
            } catch (NumberFormatException e) {
                FormUtils.showFieldError(editXtHotPairCount, activity.getString(R.string.error_hot_pair_invalid));
                return false;
            }
            if (xtHotPairCount < 1 || xtHotPairCount > Preferences.MAX_XT_HOT_PAIR_COUNT) {
                FormUtils.showFieldError(editXtHotPairCount,
                        activity.getString(R.string.error_hot_pair_range, Preferences.MAX_XT_HOT_PAIR_COUNT));
                return false;
            }
        }

        // IP 策略
        String xtIpStrategy = XT_IP_STRATEGY_VALUES[spinnerXtIpStrategy.getSelectedItemPosition()];

        // 收集高级参数
        EditText invalidAdvField = findInvalidXtAdvField();
        if (invalidAdvField != null) {
            if (xtAdvancedContainer.getVisibility() != View.VISIBLE) {
                xtAdvancedContainer.setVisibility(View.VISIBLE);
                xtAdvancedHeader.setText(activity.getString(R.string.xt_advanced_params) + " ▾");
            }
            FormUtils.showFieldError(invalidAdvField, activity.getString(R.string.error_xt_advanced_invalid));
            return false;
        }

        String advancedParams = collectXtAdvancedParams();
        if (advancedParams == null) {
            Toast.makeText(activity, activity.getString(R.string.error_xt_advanced_invalid), Toast.LENGTH_SHORT).show();
            return false;
        }

        // 保存字段
        prefs.setXtServerAddr(serverAddr);
        prefs.setXtToken(editXtToken.getText().toString().trim());
        if (saveRelay) {
            prefs.setXtRelayNodes(editXtRelayNodes.getText().toString().trim());
        }
        prefs.setXtConnections(xtConnections);
        prefs.setXtDisableEch(checkXtDisableEch.isChecked());
        prefs.setXtInsecure(checkXtInsecure.isChecked());
        prefs.setXtEnableHotPair(checkXtEnableHotPair.isChecked());
        prefs.setXtHotPairCount(xtHotPairCount);
        prefs.setXtIpStrategy(xtIpStrategy);
        prefs.setXtAdvancedParams(advancedParams);

        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        FormUtils.setFieldEnabled(editXtServerAddr, enabled);
        FormUtils.setFieldEnabled(editXtToken, enabled);
        FormUtils.setFieldEnabled(editXtRelayNodes, enabled);
        FormUtils.setFieldEnabled(editXtConnections, enabled);
        checkXtDisableEch.setEnabled(enabled);
        checkXtInsecure.setEnabled(enabled);
        spinnerXtIpStrategy.setEnabled(enabled);
        checkXtEnableHotPair.setEnabled(enabled);
        FormUtils.setFieldEnabled(editXtHotPairCount, enabled && checkXtEnableHotPair.isChecked());

        FormUtils.setFieldEnabled(editXtAdvBackpressure, enabled);
        FormUtils.setFieldEnabled(editXtAdvWriteQueueWait, enabled);
        FormUtils.setFieldEnabled(editXtAdvDialTimeout, enabled);
        FormUtils.setFieldEnabled(editXtAdvHandshakeTimeout, enabled);
        FormUtils.setFieldEnabled(editXtAdvReadTimeout, enabled);
        FormUtils.setFieldEnabled(editXtAdvWriteTimeout, enabled);
        FormUtils.setFieldEnabled(editXtAdvPingInterval, enabled);
        FormUtils.setFieldEnabled(editXtAdvReconnectDelay, enabled);
        FormUtils.setFieldEnabled(editXtAdvConnectTimeout, enabled);
        FormUtils.setFieldEnabled(editXtAdvMaxSocks5, enabled);
        FormUtils.setFieldEnabled(editXtAdvUdpPorts, enabled);
    }

    @Override
    public void setRelayEnabled(boolean independent) {
        FormUtils.setFieldEnabled(editXtRelayNodes, independent);
    }

    @Override
    public void clearErrors() {
        FormUtils.clearFieldError(editXtServerAddr);
        FormUtils.clearFieldError(editXtToken);
        FormUtils.clearFieldError(editXtRelayNodes);
        FormUtils.clearFieldError(editXtConnections);
        FormUtils.clearFieldError(editXtHotPairCount);
        FormUtils.clearFieldError(editXtAdvBackpressure);
        FormUtils.clearFieldError(editXtAdvWriteQueueWait);
        FormUtils.clearFieldError(editXtAdvDialTimeout);
        FormUtils.clearFieldError(editXtAdvHandshakeTimeout);
        FormUtils.clearFieldError(editXtAdvReadTimeout);
        FormUtils.clearFieldError(editXtAdvWriteTimeout);
        FormUtils.clearFieldError(editXtAdvPingInterval);
        FormUtils.clearFieldError(editXtAdvReconnectDelay);
        FormUtils.clearFieldError(editXtAdvConnectTimeout);
        FormUtils.clearFieldError(editXtAdvMaxSocks5);
        FormUtils.clearFieldError(editXtAdvUdpPorts);
    }

    @Override
    public void setVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (xtunnelConnectionFields != null) xtunnelConnectionFields.setVisibility(v);
        if (xtunnelAdvancedFields != null) xtunnelAdvancedFields.setVisibility(v);
    }

    // ======================== 高级参数 JSON 与冲突分析 ========================

    public JSONObject parseXtAdvancedDraft() throws JSONException {
        return xtAdvancedDraft == null || xtAdvancedDraft.trim().isEmpty()
                ? new JSONObject() : new JSONObject(xtAdvancedDraft);
    }

    public boolean hasRelayNodesInDraft() {
        try {
            return parseXtAdvancedDraft().has("relay_nodes");
        } catch (JSONException error) {
            return true;
        }
    }

    public void removeRelayNodesFromDraft() throws JSONException {
        JSONObject draft = parseXtAdvancedDraft();
        draft.remove("relay_nodes");
        xtAdvancedDraft = draft.toString();
    }

    public List<String> getConflictingKeysInDraft() {
        List<String> conflicts = new ArrayList<>();
        try {
            JSONObject draft = parseXtAdvancedDraft();
            for (String key : CONFLICT_KEYS) {
                if (draft.has(key)) conflicts.add(key);
            }
        } catch (JSONException ignored) {
        }
        return conflicts;
    }

    public void removeConflictingKeysFromDraft(List<String> keys) {
        try {
            JSONObject draft = parseXtAdvancedDraft();
            for (String key : keys) {
                draft.remove(key);
            }
            xtAdvancedDraft = draft.toString();
        } catch (JSONException ignored) {
        }
    }

    public static int xtIpStrategyIndex(String value) {
        for (int i = 0; i < XT_IP_STRATEGY_VALUES.length; i++) {
            if (XT_IP_STRATEGY_VALUES[i].equals(value)) return i;
        }
        return 0;
    }

    private EditText findInvalidXtAdvField() {
        if (!isEmpty(editXtAdvBackpressure) && !isValidPositiveInt(editXtAdvBackpressure)) {
            return editXtAdvBackpressure;
        }
        if (!isEmpty(editXtAdvWriteQueueWait) && !isValidPositiveInt(editXtAdvWriteQueueWait)) {
            return editXtAdvWriteQueueWait;
        }
        EditText[] timeoutFields = {
                editXtAdvDialTimeout, editXtAdvHandshakeTimeout,
                editXtAdvReadTimeout, editXtAdvWriteTimeout,
                editXtAdvPingInterval, editXtAdvReconnectDelay,
                editXtAdvConnectTimeout};
        for (EditText field : timeoutFields) {
            if (!isEmpty(field) && !isValidPositiveDecimal(field)) {
                return field;
            }
        }
        if (!isEmpty(editXtAdvMaxSocks5) && !isValidNonNegativeInt(editXtAdvMaxSocks5)) {
            return editXtAdvMaxSocks5;
        }
        if (!isEmpty(editXtAdvUdpPorts) && !isValidUdpPorts(editXtAdvUdpPorts)) {
            return editXtAdvUdpPorts;
        }
        return null;
    }

    private boolean isEmpty(EditText edit) {
        return edit.getText().toString().trim().isEmpty();
    }

    private boolean isValidPositiveInt(EditText edit) {
        try {
            return Integer.parseInt(edit.getText().toString().trim()) >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidNonNegativeInt(EditText edit) {
        try {
            return Integer.parseInt(edit.getText().toString().trim()) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidPositiveDecimal(EditText edit) {
        try {
            return Double.parseDouble(edit.getText().toString().trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

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

    private String collectXtAdvancedParams() {
        try {
            JSONObject adv = parseXtAdvancedDraft();
            for (String key : new String[]{"backpressure_limit", "write_queue_wait_timeout", "dial_timeout",
                    "handshake_timeout", "read_timeout", "write_timeout", "ping_interval", "reconnect_delay",
                    "connect_timeout", "max_socks5_connections", "udp_blocked_ports"}) {
                adv.remove(key);
            }
            String mb = editXtAdvBackpressure.getText().toString().trim();
            if (!mb.isEmpty()) {
                int n = Integer.parseInt(mb);
                if (n < 1) return null;
                adv.put("backpressure_limit", n * 1048576L);
            }
            String wq = editXtAdvWriteQueueWait.getText().toString().trim();
            if (!wq.isEmpty()) {
                int n = Integer.parseInt(wq);
                if (n < 1) return null;
                adv.put("write_queue_wait_timeout", n);
            }
            putTimeoutSeconds(adv, "dial_timeout", editXtAdvDialTimeout);
            putTimeoutSeconds(adv, "handshake_timeout", editXtAdvHandshakeTimeout);
            putTimeoutSeconds(adv, "read_timeout", editXtAdvReadTimeout);
            putTimeoutSeconds(adv, "write_timeout", editXtAdvWriteTimeout);
            putTimeoutSeconds(adv, "ping_interval", editXtAdvPingInterval);
            putTimeoutSeconds(adv, "reconnect_delay", editXtAdvReconnectDelay);
            putTimeoutSeconds(adv, "connect_timeout", editXtAdvConnectTimeout);
            String max = editXtAdvMaxSocks5.getText().toString().trim();
            if (!max.isEmpty()) {
                int n = Integer.parseInt(max);
                if (n < 0) return null;
                adv.put("max_socks5_connections", n);
            }
            String ports = editXtAdvUdpPorts.getText().toString().trim();
            if (!ports.isEmpty()) {
                for (String item : ports.split(",")) {
                    int port = Integer.parseInt(item.trim());
                    if (port < 1 || port > 65535) return null;
                }
                adv.put("udp_blocked_ports", ports);
            }
            xtAdvancedDraft = adv.length() > 0 ? adv.toString() : "";
            return xtAdvancedDraft;
        } catch (JSONException | NumberFormatException e) {
            return null;
        }
    }

    private void putTimeoutSeconds(JSONObject adv, String key, EditText input) throws JSONException {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        double seconds = Double.parseDouble(text);
        if (seconds <= 0) throw new NumberFormatException("timeout must be positive");
        adv.put(key, Math.round(seconds * 1000));
    }

    private void loadXtAdvancedParams(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            long mb = o.optLong("backpressure_limit", 0);
            if (mb > 0) editXtAdvBackpressure.setText(String.valueOf(mb / 1048576));
            int wq = o.optInt("write_queue_wait_timeout", 0);
            if (wq > 0) editXtAdvWriteQueueWait.setText(String.valueOf(wq));
            fillTimeoutSeconds(o, "dial_timeout", editXtAdvDialTimeout);
            fillTimeoutSeconds(o, "handshake_timeout", editXtAdvHandshakeTimeout);
            fillTimeoutSeconds(o, "read_timeout", editXtAdvReadTimeout);
            fillTimeoutSeconds(o, "write_timeout", editXtAdvWriteTimeout);
            fillTimeoutSeconds(o, "ping_interval", editXtAdvPingInterval);
            fillTimeoutSeconds(o, "reconnect_delay", editXtAdvReconnectDelay);
            fillTimeoutSeconds(o, "connect_timeout", editXtAdvConnectTimeout);
            int max = o.optInt("max_socks5_connections", -1);
            if (max >= 0) editXtAdvMaxSocks5.setText(String.valueOf(max));
            String ports = o.optString("udp_blocked_ports", "");
            if (!ports.isEmpty()) editXtAdvUdpPorts.setText(ports);
        } catch (JSONException ignored) {
        }
    }

    private void fillTimeoutSeconds(JSONObject o, String key, EditText input) {
        long ms = o.optLong(key, 0);
        if (ms > 0) input.setText(String.valueOf(ms / 1000));
    }
}
