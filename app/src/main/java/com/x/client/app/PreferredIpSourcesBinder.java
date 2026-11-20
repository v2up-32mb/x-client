package com.x.client.app;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the UI views, source management list, and import actions of the Sources tab.
 */
public class PreferredIpSourcesBinder {

    private final Activity activity;
    private final PreferredIpController controller;
    private View rootView;

    private TextView sourceStatus;
    private TextView sourceSummary;
    private LinearLayout sourceRows;
    private TextInputEditText sourceUrl;
    private TextInputEditText manual;
    private TextView parseSummary;
    private View parseCard;
    private MaterialButton accept;
    private final List<View> networkButtons = new ArrayList<>();

    private String sourceKey = "";

    public PreferredIpSourcesBinder(@NonNull Activity activity, @NonNull PreferredIpController controller) {
        this.activity = activity;
        this.controller = controller;
    }

    public View inflateAndBind(@NonNull ViewGroup parent) {
        if (rootView != null) {
            return rootView;
        }
        rootView = LayoutInflater.from(parent.getContext()).inflate(R.layout.layout_preferred_sources, parent, false);
        bind(rootView);
        return rootView;
    }

    public View getRootView() {
        return rootView;
    }

    public void bind(@NonNull View view) {
        this.rootView = view;

        sourceStatus = view.findViewById(R.id.preferred_sources_status);
        sourceSummary = view.findViewById(R.id.preferred_sources_summary);
        sourceRows = view.findViewById(R.id.preferred_sources_container);
        sourceUrl = view.findViewById(R.id.preferred_source_url);
        manual = view.findViewById(R.id.preferred_manual_input);
        parseSummary = view.findViewById(R.id.preferred_parse_summary);
        parseCard = view.findViewById(R.id.preferred_parse_card);
        accept = view.findViewById(R.id.preferred_btn_accept);

        MaterialButton btnFetchV4 = view.findViewById(R.id.preferred_btn_fetch_v4);
        MaterialButton btnFetchV6 = view.findViewById(R.id.preferred_btn_fetch_v6);
        MaterialButton btnFetchList = view.findViewById(R.id.preferred_btn_fetch_list);
        MaterialButton btnParseManual = view.findViewById(R.id.preferred_btn_parse_manual);
        MaterialButton btnLegacy = view.findViewById(R.id.preferred_btn_legacy_nodes);

        sourceUrl.setText(controller.urlDraft);
        manual.setText(controller.manualDraft);

        btnFetchV4.setOnClickListener(v ->
                controller.fetch("cf-v4", "https://www.cloudflare.com/ips-v4", "cidr4", PreferredIpController.ActionType.FETCH_V4));
        btnFetchV6.setOnClickListener(v ->
                controller.fetch("cf-v6", "https://www.cloudflare.com/ips-v6", "cidr6", PreferredIpController.ActionType.FETCH_V6));

        networkButtons.add(btnFetchV4);
        networkButtons.add(btnFetchV6);

        btnFetchList.setOnClickListener(v -> {
            String url = sourceUrl.getText() == null ? "" : sourceUrl.getText().toString().trim();
            if (!url.startsWith("https://")) {
                sourceUrl.setError(activity.getString(R.string.preferred_err_https_url));
                confirm(activity.getString(R.string.preferred_err_url_format_title),
                        activity.getString(R.string.preferred_err_url_format_msg), () -> {});
                return;
            }
            controller.fetch(PreferredIpIds.opaque("list", url), url, "list", PreferredIpController.ActionType.FETCH_LIST);
        });
        networkButtons.add(btnFetchList);

        btnParseManual.setOnClickListener(v -> {
            String text = manual.getText() == null ? "" : manual.getText().toString().trim();
            if (text.isEmpty()) {
                manual.setError(activity.getString(R.string.preferred_err_input_empty));
                return;
            }
            controller.parse(text, "manual", "manual", PreferredIpController.ActionType.PARSE_MANUAL);
        });

        btnLegacy.setOnClickListener(v -> controller.legacy());
        accept.setOnClickListener(v -> controller.acceptParsed());

        // TV 遥控器焦点高光
        TvFocusHelper.applyFocusFeedback(btnFetchV4, btnFetchV6, btnFetchList, btnParseManual, btnLegacy, accept);
    }

    public void updateState(PreferredIpController.State state) {
        if (state == null) return;

        if (sourceStatus != null) {
            sourceStatus.setText(state.message);
            sourceStatus.setVisibility(state.message != null && !state.message.isEmpty() ? View.VISIBLE : View.GONE);
        }

        for (View view : networkButtons) {
            view.setEnabled(!state.busy);
        }

        renderSources(state);
    }

    private void renderSources(PreferredIpController.State state) {
        if (sourceRows == null || state.data == null) return;
        JSONArray sources = state.data.optJSONArray("sources");
        String key = String.valueOf(sources) + state.busy + state.sourceMessages;

        if (!key.equals(sourceKey)) {
            sourceKey = key;
            int count = sources == null ? 0 : sources.length();

            if (sourceSummary != null) {
                sourceSummary.setText(count == 0 ? activity.getString(R.string.preferred_empty_sources)
                        : "来源修改不改变本轮冻结队列；重新开始时采用。");
            }

            // 清理多余子视图或复用
            while (sourceRows.getChildCount() > count) {
                sourceRows.removeViewAt(sourceRows.getChildCount() - 1);
            }

            for (int i = 0; sources != null && i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null) continue;

                String id = source.optString("id");
                String url = source.optString("url");

                View rowView;
                if (i < sourceRows.getChildCount()) {
                    rowView = sourceRows.getChildAt(i);
                } else {
                    rowView = LayoutInflater.from(activity).inflate(R.layout.item_preferred_source, sourceRows, false);
                    sourceRows.addView(rowView);
                }

                MaterialCheckBox on = rowView.findViewById(R.id.source_checkbox);
                TextView title = rowView.findViewById(R.id.source_title);
                TextView chip = rowView.findViewById(R.id.source_count_chip);
                TextView statusText = rowView.findViewById(R.id.source_status_text);
                ImageButton refresh = rowView.findViewById(R.id.btn_refresh_source);
                ImageButton delete = rowView.findViewById(R.id.btn_delete_source);

                title.setText(PreferredIpPresentation.source(source));
                on.setOnCheckedChangeListener(null);
                on.setChecked(source.optBoolean("enabled", true));
                on.setOnCheckedChangeListener((b, checked) -> controller.enableSource(id, checked));

                JSONArray cands = source.optJSONArray("candidates");
                JSONArray prefs = source.optJSONArray("prefixes");
                int candCount = cands == null ? 0 : cands.length();
                int prefCount = prefs == null ? 0 : prefs.length();
                chip.setText(candCount + " IP · " + prefCount + " 段");

                statusText.setText(state.sourceMessages.getOrDefault(id, "已有来源快照"));

                if (!TextUtils.isEmpty(url)) {
                    refresh.setVisibility(View.VISIBLE);
                    refresh.setEnabled(!state.busy);
                    refresh.setOnClickListener(v ->
                            controller.fetch(id, url, source.optString("kind"), PreferredIpController.ActionType.REFRESH_SOURCE));
                    TvFocusHelper.applyFocusFeedback(refresh);
                } else {
                    refresh.setVisibility(View.GONE);
                }

                delete.setOnClickListener(v -> confirm(
                        activity.getString(R.string.preferred_delete_source_title),
                        activity.getString(R.string.preferred_delete_source_msg),
                        () -> controller.deleteSource(id)));
                TvFocusHelper.applyFocusFeedback(delete);
            }
        }

        if (parseCard != null) {
            parseCard.setVisibility(state.parse == null ? View.GONE : View.VISIBLE);
        }
        if (accept != null) {
            accept.setVisibility(state.parse == null ? View.GONE : View.VISIBLE);
        }

        if (state.parse != null) {
            StringBuilder parsed = new StringBuilder();
            JSONArray rows = state.parse.optJSONArray("candidates");
            JSONArray errors = state.parse.optJSONArray("errors");
            parsed.append("有效 IP：").append(rows == null ? 0 : rows.length());
            for (int i = 0; errors != null && i < errors.length(); i++) {
                JSONObject error = errors.optJSONObject(i);
                if (error != null) {
                    parsed.append("\n第").append(error.optInt("line")).append("行：").append(error.optString("message"))
                            .append('\n').append(state.parseThirdParty ? error.optString("text").split("#", 2)[0] : error.optString("text"));
                }
            }
            if (parseSummary != null) parseSummary.setText(parsed);
        }
    }

    public void handleActionResult(@NonNull PreferredIpController.ActionResult result) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        switch (result.type) {
            case FETCH_LIST:
                if (result.parse != null) {
                    JSONArray cands = result.parse.optJSONArray("candidates");
                    JSONArray errs = result.parse.optJSONArray("errors");
                    int candCount = cands == null ? 0 : cands.length();
                    int errCount = errs == null ? 0 : errs.length();
                    if (candCount > 0) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.preferred_dialog_list_parse_title)
                                .setMessage("已获取到 " + candCount + " 个有效中转 IP" + (errCount > 0 ? "（另有 " + errCount + " 行不合规或注释行已自动过滤）" : "") + "。\n\n是否立即加入已配置来源？")
                                .setNegativeButton(R.string.preferred_btn_discard, null)
                                .setPositiveButton(R.string.preferred_btn_import_valid, (d, w) -> {
                                    controller.acceptParsed();
                                    Toast.makeText(activity, "已成功导入 " + candCount + " 个中转 IP", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                    } else {
                        confirm(activity.getString(R.string.preferred_err_no_valid_ip_title),
                                "该列表中未发现符合「IP:端口」格式的有效中转 IP（共 " + errCount + " 行不合规）。请检查该列表内容。\n\n标准格式示例：\n104.16.24.5:443#香港", () -> {});
                    }
                } else if (result.success) {
                    Toast.makeText(activity, R.string.preferred_msg_list_added, Toast.LENGTH_SHORT).show();
                } else {
                    String errMsg = result.message;
                    if (errMsg == null || errMsg.isEmpty() || errMsg.contains("已保存")) {
                        errMsg = "无法从该链接获取内容或列表内容为空，请检查链接可达性。";
                    }
                    confirm(activity.getString(R.string.preferred_err_fetch_list_failed), errMsg, () -> {});
                }
                break;

            case FETCH_V4:
            case FETCH_V6:
                if (result.success) {
                    Toast.makeText(activity, R.string.preferred_msg_official_updated, Toast.LENGTH_SHORT).show();
                } else {
                    String errMsg = result.message;
                    if (errMsg == null || errMsg.isEmpty()) errMsg = "更新官方地址库失败，请检查网络连接。";
                    confirm(activity.getString(R.string.preferred_err_update_failed), errMsg, () -> {});
                }
                break;

            case REFRESH_SOURCE:
                if (!result.success || (result.message != null && (result.message.contains("失败") || result.message.contains("错误") || result.message.contains("未完成")))) {
                    confirm(activity.getString(R.string.preferred_err_refresh_source_failed), result.message, () -> {});
                } else {
                    Toast.makeText(activity, R.string.preferred_msg_source_refreshed, Toast.LENGTH_SHORT).show();
                }
                break;

            case PARSE_MANUAL:
                if (result.parse != null) {
                    JSONArray cands = result.parse.optJSONArray("candidates");
                    JSONArray errs = result.parse.optJSONArray("errors");
                    int candCount = cands == null ? 0 : cands.length();
                    int errCount = errs == null ? 0 : errs.length();
                    if (candCount > 0) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.preferred_dialog_manual_parse_title)
                                .setMessage("已识别到 " + candCount + " 个有效中转 IP" + (errCount > 0 ? "（" + errCount + " 行不合规）" : "") + "。\n\n是否立即导入？")
                                .setNegativeButton(R.string.preferred_btn_cancel, null)
                                .setPositiveButton(R.string.preferred_btn_import_now, (d, w) -> {
                                    controller.acceptParsed();
                                    Toast.makeText(activity, "已导入 " + candCount + " 个中转 IP", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                    } else {
                        confirm(activity.getString(R.string.preferred_err_no_valid_ip_title),
                                "未发现有效的 IP:端口 地址行。示例：104.16.24.5:443#香港", () -> {});
                    }
                }
                break;
        }
    }

    private void confirm(String title, String body, Runnable action) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (action != null) action.run();
                })
                .show();
    }

    public void rememberDrafts() {
        if (manual != null && manual.getText() != null) {
            controller.manualDraft = manual.getText().toString();
        }
        if (sourceUrl != null && sourceUrl.getText() != null) {
            controller.urlDraft = sourceUrl.getText().toString();
        }
    }

    public void onDestroy() {
        rootView = null;
        sourceStatus = null;
        sourceSummary = null;
        sourceRows = null;
        sourceUrl = null;
        manual = null;
        parseSummary = null;
        parseCard = null;
        accept = null;
        networkButtons.clear();
    }
}
