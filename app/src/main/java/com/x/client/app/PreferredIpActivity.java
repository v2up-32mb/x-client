package com.x.client.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Real three-tab front end; only immutable controller snapshots are consumed on the UI thread. */
public class PreferredIpActivity extends BaseActivity implements PreferredIpController.Listener {
    private static final int OPEN_TXT = 502;
    private PreferredIpController controller;
    private PreferredIpController.State state;
    private FrameLayout page;
    private TabLayout tabs;
    private TextView logText;
    private ScrollView logScroll;
    private boolean logTouched;
    private long logInteraction;
    private int restoredLogY = -1;
    private String sourceRenderKey = "", selectedRenderKey = "";
    private TextView status, sourceSummary, parseSummary, selectedSummary, scope, targetDetail;
    private TextInputEditText manual, sourceUrl;
    private LinearLayout selectedRows, sourceRows;
    private MaterialButton accept, commit;
    private final List<View> idleOnly = new ArrayList<>();
    private ResultAdapter results;
    private int currentTab, resultFilter;
    private String manualDraft = "", urlDraft = "https://776771.xyz/all.txt";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastCustomNonConfigurationInstance();
        controller = retained instanceof PreferredIpController ? (PreferredIpController) retained
                : new PreferredIpController(this, getIntent().getStringExtra(ProfileEditActivity.EXTRA_PROFILE_ID) == null
                ? new Preferences(this).getCurrentProfileId() : getIntent().getStringExtra(ProfileEditActivity.EXTRA_PROFILE_ID));
        if (saved != null) {
            currentTab = saved.getInt("tab");
            restoredLogY = saved.getInt("log_y", 0);
            manualDraft = saved.getString("manual", "");
            urlDraft = saved.getString("url", urlDraft);
        }
        setContentView(R.layout.activity_preferred_ip);
        ((MaterialToolbar) findViewById(R.id.preferred_toolbar)).setNavigationOnClickListener(v -> finish());
        page = findViewById(R.id.preferred_page);
        logText = findViewById(R.id.preferred_log_text);
        logScroll = findViewById(R.id.preferred_log_scroll);
        logScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) { logTouched = true; logInteraction++; }
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) logTouched = false;
            return false;
        });
        tabs = findViewById(R.id.preferred_tabs);
        for (int title : new int[]{R.string.preferred_sources, R.string.preferred_test, R.string.preferred_selected}) {
            tabs.addTab(tabs.newTab().setText(title));
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        tabs.selectTab(tabs.getTabAt(currentTab));
        showPage(currentTab);
    }

    @Override protected void onStart() { super.onStart(); controller.attach(this); }
    @Override protected void onStop() { controller.detach(isChangingConfigurations()); super.onStop(); }
    @Override public Object onRetainCustomNonConfigurationInstance() { return controller; }
    @Override protected void onDestroy() { if (!isChangingConfigurations()) controller.close(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        retainDrafts();
        out.putInt("log_y", logScroll.getScrollY());
        out.putInt("tab", currentTab); out.putString("manual", manualDraft); out.putString("url", urlDraft);
        // Opaque ID only: a killed UI process never silently starts a replacement task.
        if (state != null) out.putString("task_id", state.taskId);
        super.onSaveInstanceState(out);
    }

    private void retainDrafts() {
        if (manual != null) manualDraft = manual.getText().toString();
        if (sourceUrl != null) urlDraft = sourceUrl.getText().toString();
    }

    private int dimen(int res) { return getResources().getDimensionPixelSize(res); }
    private LinearLayout column() {
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        int p = dimen(R.dimen.spacing_lg); out.setPadding(p, p, p, p);
        out.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        return out;
    }
    private TextView text(LinearLayout parent, CharSequence value) {
        TextView out = new TextView(this);
        out.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        out.setText(value); out.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dimen(R.dimen.spacing_md); parent.addView(out, lp);
        return out;
    }
    private void heading(LinearLayout parent, int res) {
        text(parent, getString(res)).setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    }
    private MaterialButton button(LinearLayout parent, int title, Runnable action, boolean needsIdle) {
        MaterialButton out = new MaterialButton(this);
        out.setText(title); out.setMinHeight(dimen(R.dimen.touch_target_min));
        out.setMaxLines(5); out.setOnClickListener(v -> action.run());
        parent.addView(out, new LinearLayout.LayoutParams(-1, -2));
        if (needsIdle) idleOnly.add(out);
        return out;
    }
    private TextInputEditText input(LinearLayout parent, int label, String value) {
        TextInputLayout box = new TextInputLayout(this);
        box.setHint(getString(label)); box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText edit = new TextInputEditText(box.getContext());
        edit.setText(value); edit.setMinHeight(dimen(R.dimen.touch_target_min));
        box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dimen(R.dimen.spacing_md); parent.addView(box, lp);
        return edit;
    }
    private LinearLayout scrollPage() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = column(); scroll.addView(content);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return content;
    }

    private void showPage(int tab) {
        retainDrafts(); currentTab = tab; idleOnly.clear();
        manual = null; sourceUrl = null; results = null;
        sourceSummary = null; parseSummary = null; selectedSummary = null; scope = null;
        selectedRows = null; sourceRows = null; accept = null; commit = null;
        sourceRenderKey = ""; selectedRenderKey = "";
        page.removeAllViews();
        if (tab == 0) buildSources(); else if (tab == 1) buildTests(); else buildSelected();
        render();
    }

    private void buildSources() {
        LinearLayout content = scrollPage();
        text(content, getString(R.string.preferred_intro));
        status = text(content, ""); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sourceSummary = text(content, getString(R.string.preferred_empty_sources));
        sourceRows = column(); content.addView(sourceRows);
        heading(content, R.string.preferred_official);
        button(content, R.string.preferred_fetch_v4, () -> controller.fetch("cf-v4", "https://www.cloudflare.com/ips-v4", "cidr4"), true);
        button(content, R.string.preferred_fetch_v6, () -> controller.fetch("cf-v6", "https://www.cloudflare.com/ips-v6", "cidr6"), true);
        text(content, getString(R.string.preferred_v6_hint));
        heading(content, R.string.preferred_third);
        sourceUrl = input(content, R.string.preferred_url, urlDraft);
        sourceUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        button(content, R.string.preferred_fetch_list, () -> {
            String url = sourceUrl.getText().toString().trim();
            if (!url.startsWith("https://")) { sourceUrl.setError("请使用 HTTPS URL"); return; }
            controller.fetch(PreferredIpIds.opaque("list", url), url, "list");
        }, true);
        heading(content, R.string.preferred_manual);
        text(content, getString(R.string.preferred_format));
        manual = input(content, R.string.preferred_content, manualDraft);
        manual.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        manual.setMinLines(3);
        button(content, R.string.preferred_parse, () -> controller.parse(manual.getText().toString(), "manual", "manual"), true);
        button(content, R.string.preferred_import, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, OPEN_TXT);
        }, true);
        button(content, R.string.preferred_legacy, () -> controller.legacy(), true);
        parseSummary = text(content, "");
        accept = button(content, R.string.preferred_accept, () -> controller.acceptParsed(), true);
        button(content, R.string.preferred_cancel, () -> controller.cancel("用户取消，保留已保存池"), false);
        button(content, R.string.preferred_start, () -> tabs.selectTab(tabs.getTabAt(1)), true);
    }

    private void buildTests() {
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        LinearLayout header = column();
        status = text(header, ""); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        text(header, getString(R.string.preferred_direct));
        text(header, getString(R.string.preferred_budget));
        button(header, R.string.preferred_start, this::testDialog, true);
        button(header, R.string.preferred_cancel, () -> controller.cancel("用户取消，保留完成的部分结果"), false);
        button(header, R.string.preferred_recommend, () -> controller.recommend(), true);
        button(header, R.string.preferred_view_selected, () -> tabs.selectTab(tabs.getTabAt(2)), true);
        Spinner filter = new Spinner(this);
        filter.setMinimumHeight(dimen(R.dimen.touch_target_min));
        filter.setContentDescription("结果筛选");
        ArrayAdapter<String> filters = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"全部结果", "仅推荐", "仅失败"});
        filters.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(filters); filter.setSelection(resultFilter); header.addView(filter);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int position, long id) {
                resultFilter = position;
                if (results != null) results.updateRows();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });
        results = new ResultAdapter(header); list.setAdapter(results);
        page.addView(list, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildSelected() {
        LinearLayout content = scrollPage();
        status = text(content, ""); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        selectedSummary = text(content, "");
        text(content, getString(R.string.preferred_save_hint));
        selectedRows = column(); content.addView(selectedRows);
        button(content, R.string.preferred_save, () -> controller.prepareSave(), true);
        scope = text(content, "");
        commit = button(content, R.string.preferred_confirm, () -> {
            if (state == null || state.preview == null) return;
            new MaterialAlertDialogBuilder(this).setTitle(R.string.preferred_confirm)
                    .setMessage(state.preview.optString("scope") + "\n保存后下次连接生效，不自动重连。原节点IP保留，可恢复。")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (d, w) -> controller.commitPreview()).show();
        }, true);
        button(content, R.string.preferred_cancel, () -> controller.cancel("取消兼容检查，不保存"), false);
        button(content, R.string.preferred_disable, () -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.preferred_disable).setMessage(R.string.preferred_disable_body)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok, (d, w) -> controller.disable()).show(), true);
    }

    private void testDialog() {
        List<Preferences.ProfileInfo> profiles = new Preferences(this).getProfileList();
        if (profiles.isEmpty()) { showError(getString(R.string.preferred_no_profiles)); return; }
        try { PreferredIpNetwork.requireDirect(this); } catch (Exception error) { showError(error.getMessage()); return; }
        LinearLayout body = column();
        text(body, getString(R.string.preferred_target));
        Spinner selector = new Spinner(this); selector.setMinimumHeight(dimen(R.dimen.touch_target_min));
        List<String> names = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < profiles.size(); i++) {
            names.add(profiles.get(i).name);
            if (state != null && profiles.get(i).id.equals(state.referenceId)) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter); selector.setSelection(selected); body.addView(selector);
        targetDetail = text(body, "读取最终目标参数…");
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int position, long id) {
                controller.inspectReference(profiles.get(position).id);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });
        TextInputEditText sample = input(body, R.string.preferred_sample, "256"); sample.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        TextInputEditText concurrency = input(body, R.string.preferred_concurrency, "8"); concurrency.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        TextInputEditText url = input(body, R.string.preferred_download_url, PreferredIpController.DOWNLOAD);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.preferred_start)
                .setView(scroll).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.preferred_go, null).create();
        dialog.setOnDismissListener(d -> targetDetail = null);
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            int count, workers;
            try { count = Integer.parseInt(sample.getText().toString()); if (count < 1 || count > 4096) throw new Exception(); }
            catch (Exception invalid) { sample.setError("请输入1–4096"); return; }
            try { workers = Integer.parseInt(concurrency.getText().toString()); if (workers < 1 || workers > 32) throw new Exception(); }
            catch (Exception invalid) { concurrency.setError("请输入1–32"); return; }
            String download = url.getText().toString().trim();
            if (!download.startsWith("https://")) { url.setError("请使用 HTTPS URL"); return; }
            String id = profiles.get(selector.getSelectedItemPosition()).id;
            Runnable start = () -> controller.test(id, count, workers, download);
            dialog.dismiss();
            if (PreferredIpNetwork.metered(this)) new MaterialAlertDialogBuilder(this).setTitle(R.string.preferred_metered)
                    .setMessage(R.string.preferred_metered_body).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.preferred_go, (dd, w) -> start.run()).show();
            else start.run();
        }));
        dialog.show();
    }

    private void showError(String message) {
        new MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton(R.string.ok, null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN_TXT && result == RESULT_OK && data != null && data.getData() != null) controller.importFile(data.getData());
    }

    @Override public void changed(PreferredIpController.State next) { state = next; render(); }

    private void render() {
        if (state == null || status == null) return;
        if (targetDetail != null) targetDetail.setText(state.referenceSummary);
        JSONObject snap = state.snapshot;
        String progress = snap.length() == 0 ? getString(R.string.preferred_not_tested)
                : PreferredIpPresentation.phase(snap.optString("phase")) + " · " + PreferredIpPresentation.state(snap.optString("state")) + " · "
                + snap.optInt("completed") + "/" + snap.optInt("total") + " · 下载正文 "
                + String.format(Locale.getDefault(), "%.2f MiB", snap.optLong("download_bytes") / 1048576.0);
        String statusText = state.network + "\n" + progress + (state.stale ? "\n" + getString(R.string.preferred_old_network) : "")
                + "\n" + state.message;
        if (!statusText.contentEquals(status.getText())) status.setText(statusText);
        if (!state.logs.contentEquals(logText.getText())) {
            boolean follow = restoredLogY < 0 && !logTouched && !logScroll.canScrollVertically(1);
            int oldY = restoredLogY < 0 ? logScroll.getScrollY() : restoredLogY;
            restoredLogY = -1;
            long interaction = logInteraction;
            logText.setText(state.logs);
            logScroll.post(() -> {
                if (!logTouched && interaction == logInteraction) {
                    if (follow) logScroll.fullScroll(View.FOCUS_DOWN);
                    else logScroll.scrollTo(0, oldY);
                }
            });
        }
        for (View v : idleOnly) v.setEnabled(!state.busy);
        if (sourceSummary != null) {
            JSONArray sources = state.data.optJSONArray("sources");
            String key = String.valueOf(sources) + state.busy;
            if (!key.equals(sourceRenderKey)) {
                sourceRenderKey = key;
                StringBuilder summary = new StringBuilder();
                sourceRows.removeAllViews();
                if (sources != null) for (int i = 0; i < sources.length(); i++) {
                    JSONObject source = sources.optJSONObject(i);
                    String id = source.optString("id");
                    MaterialCheckBox enabled = new MaterialCheckBox(this);
                    enabled.setText("参与测试：" + PreferredIpPresentation.source(source));
                    enabled.setMinHeight(dimen(R.dimen.touch_target_min));
                    enabled.setChecked(source.optBoolean("enabled", true));
                    enabled.setEnabled(!state.busy);
                    enabled.setOnCheckedChangeListener((b, on) -> controller.enableSource(id, on));
                    sourceRows.addView(enabled);
                    summary.append(PreferredIpPresentation.source(source)).append("：")
                            .append(source.optJSONArray("candidates").length()).append(" IP / ")
                            .append(source.optJSONArray("prefixes").length()).append(" 段 · ")
                            .append(DateFormat.getDateTimeInstance().format(new Date(source.optLong("updated_at")))).append('\n');
                }
                sourceSummary.setText(summary.length() == 0 ? getString(R.string.preferred_empty_sources) : summary);
            }
            accept.setVisibility(state.parse == null ? View.GONE : View.VISIBLE);
            if (state.parse != null) {
                StringBuilder parsed = new StringBuilder("有效行：" + state.parse.optJSONArray("candidates").length() + "\n");
                JSONArray errors = state.parse.optJSONArray("errors");
                for (int i = 0; errors != null && i < errors.length(); i++) {
                    JSONObject error = errors.optJSONObject(i);
                    parsed.append("第").append(error.optInt("line")).append("行：").append(error.optString("message"))
                            .append("\n").append(state.parseThirdParty ? error.optString("text").split("#", 2)[0]
                                    : error.optString("text")).append('\n');
                }
                parseSummary.setText(parsed);
            } else parseSummary.setText("");
        }
        if (results != null) results.updateRows();
        if (selectedSummary != null) {
            selectedSummary.setText(getString(R.string.preferred_summary, state.data.optLong("revision"),
                    state.data.optBoolean("enabled") ? "启用" : "未启用", state.data.optJSONArray("selected").length(), state.chosen.length())
                    + (state.chosen.length() > 3 ? "\n" + getString(R.string.preferred_many) : ""));
            String key = state.chosen + "|" + state.validation + "|" + state.results + "|" + state.data.optLong("revision") + state.busy + state.stale;
            if (!key.equals(selectedRenderKey)) {
                selectedRenderKey = key;
                selectedRows.removeAllViews();
                if (state.chosen.length() == 0) text(selectedRows, getString(R.string.preferred_no_selection));
                for (int i = 0; i < state.chosen.length(); i++) {
                    String address = state.chosen.optString(i);
                    renderSelectedAddress(address);
                    MaterialButton remove = button(selectedRows, R.string.preferred_selected, () -> controller.select(address, false), false);
                    remove.setText(getString(R.string.preferred_remove, address)); remove.setEnabled(!state.busy);
                }
            }
            String reportScope = state.validation == null ? "尚未验证当前选择" :
                    ("stale".equals(state.validation.optString("state")) ? state.validation.optString("reason")
                    : state.validation.optString("scope", "正在验证；未完成结果不可保存"));
            scope.setText(reportScope + "\n\n" + state.savedScope);
            commit.setVisibility(state.preview == null ? View.GONE : View.VISIBLE);
        }
    }

    private static String time(long value) {
        return value > 0 ? DateFormat.getDateTimeInstance().format(new Date(value)) : "无测量时间";
    }

    private void renderSelectedAddress(String address) {
        JSONObject candidate = PreferredIpPresentation.find(state.selectedCandidates, address);
        text(selectedRows, address).setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        if (candidate != null) {
            String comment = PreferredIpPresentation.comment(candidate, state.data);
            text(selectedRows, PreferredIpPresentation.sources(candidate, state.data) + (comment.isEmpty() ? "" : "\n备注：" + comment));
        }
        JSONObject original = PreferredIpPresentation.find(state.results.optJSONArray("results"), address);
        if (original == null) text(selectedRows, "原测速：无结果（不代表兼容验证失败）");
        else text(selectedRows, "原测速：" + (original.optBoolean("recommended") ? "曾推荐" : "未推荐")
                + " · " + original.optString("status") + " · CF " + original.optString("colo") + " · " + time(original.optLong("tested_at"))
                + "\n" + PreferredIpPresentation.target(state.results.optJSONObject("test_target"))
                + (state.stale ? "\n旧网络结果" : "") + "\n仅为原性能记录，不代表下面各实际节点的最新兼容性");
        JSONObject report = state.validation;
        boolean current = report != null && "completed".equals(report.optString("state"));
        if (!current) {
            text(selectedRows, report == null ? "当前选择：未做实际目标兼容验证" :
                    "当前选择：" + ("running".equals(report.optString("state")) ? "验证尚未完整结束" : report.optString("reason"))
                    + "；不展示旧验证成功，请重新验证");
            return;
        }
        JSONArray profiles = report.optJSONArray("profiles");
        int valid = 0, failed = 0, untested = 0;
        StringBuilder details = new StringBuilder();
        for (int i = 0; profiles != null && i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            JSONObject result = PreferredIpPresentation.find(profile.optJSONArray("results"), address);
            details.append(profile.optString("name")).append("：");
            if (result == null) {
                untested++;
                details.append("未测 · ").append(profile.optString("reason", "没有结果"));
            } else {
                boolean passed = "valid".equals(result.optString("status"));
                if (passed) valid++; else failed++;
                details.append(passed ? "有效（HTTPS / CF trace 通过）" : "失败 · " + result.optString("error"))
                        .append(" · CF ").append(result.optString("colo").isEmpty() ? "未验证" : result.optString("colo"))
                        .append("\n").append(time(result.optLong("tested_at")));
            }
            details.append("\n").append(PreferredIpPresentation.target(profile.optJSONObject("target"))).append("\n\n");
        }
        String status = valid > 0 ? (failed + untested > 0 ? "部分目标兼容" : "参与目标均有效") : (failed > 0 ? "已测目标均失败" : "未测");
        text(selectedRows, "最新实际目标验证：" + status + " · 有效 " + valid + " / 失败 " + failed + " / 未测 " + untested
                + (report.optBoolean("saved") ? "\n已确认保存；下次连接生效" : "\n未确认保存；尚未改变正在使用的入口")
                + "\n无下载验证不产生新推荐；单节点失败不表示 IP 对所有节点失效。\n" + details);
    }

    private final class ResultAdapter extends RecyclerView.Adapter<ResultHolder> {
        private final LinearLayout header;
        private JSONArray visible = new JSONArray();
        private String renderKey = "";
        ResultAdapter(LinearLayout header) { this.header = header; }
        private JSONArray rows() { return visible; }
        void updateRows() {
            String key = state == null ? "" : state.results + "|" + state.chosen + "|" + state.busy + state.stale + resultFilter + "|" + state.data.optLong("revision");
            if (key.equals(renderKey)) return;
            renderKey = key;
            visible = new JSONArray();
            JSONArray all = state == null ? null : state.results.optJSONArray("results");
            if (all != null) for (int i = 0; i < all.length(); i++) {
                JSONObject row = all.optJSONObject(i);
                if (resultFilter == 0 || (resultFilter == 1 && row.optBoolean("recommended"))
                        || (resultFilter == 2 && "failed".equals(row.optString("status")))) visible.put(row);
            }
            notifyDataSetChanged();
        }
        @Override public int getItemCount() { return 1 + rows().length(); }
        @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }
        @Override public ResultHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == 0) {
                if (header.getParent() != null) ((ViewGroup) header.getParent()).removeView(header);
                return new ResultHolder(header, null, null);
            }
            MaterialCardView card = new MaterialCardView(PreferredIpActivity.this);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            int margin = dimen(R.dimen.spacing_sm); lp.setMargins(margin, margin, margin, margin); card.setLayoutParams(lp);
            LinearLayout body = column(); card.addView(body);
            MaterialCheckBox choice = new MaterialCheckBox(PreferredIpActivity.this);
            choice.setMinHeight(dimen(R.dimen.touch_target_min)); body.addView(choice);
            TextView detail = text(body, "");
            return new ResultHolder(card, choice, detail);
        }
        @Override public void onBindViewHolder(ResultHolder holder, int position) {
            if (position == 0) return;
            JSONObject r = rows().optJSONObject(position - 1);
            String address = r.optString("address");
            holder.choice.setOnCheckedChangeListener(null);
            boolean checked = false;
            for (int i = 0; i < state.chosen.length(); i++) if (address.equals(state.chosen.optString(i))) checked = true;
            holder.choice.setChecked(checked);
            holder.choice.setText(address + (r.optBoolean("recommended") ? " · 推荐" : "") + (state.stale ? " · 旧网络" : ""));
            holder.choice.setEnabled(!state.busy && "valid".equals(r.optString("status")));
            holder.choice.setOnCheckedChangeListener((b, selected) -> controller.select(address, selected));
            String latency = r.optInt("http_success") > 0 ? String.format(Locale.getDefault(), "HTTPS 中位 %.1fms · 波动 %.1fms",
                    r.optDouble("http_median_ms"), r.optDouble("http_jitter_ms")) : "HTTPS 未验证";
            String download = r.optString("download_state", "untested");
            String speed = "ok".equals(download) || "short".equals(download)
                    ? String.format(Locale.getDefault(), "下载均速 %.2f MiB/s · %.2fs · %s", r.optDouble("download_mib_s"),
                    r.optDouble("download_seconds"), "short".equals(download) ? "短样本，低置信" : "有效样本")
                    : "下载：" + ("failed".equals(download) ? "失败" : "未测");
            holder.detail.setText(PreferredIpPresentation.sources(r, state.data) + " · CF " + (r.optString("colo").isEmpty() ? "未验证" : r.optString("colo"))
                    + "\n" + PreferredIpPresentation.comment(r, state.data) + "\n" + latency
                    + "\nTCP 成功 " + r.optInt("tcp_success") + "/" + r.optInt("tcp_attempts")
                    + " · HTTPS 成功 " + r.optInt("http_success") + "/" + r.optInt("http_attempts")
                    + (r.optInt("tcp_success") > 0 ? String.format(Locale.getDefault(), "\nTCP 最小/均值/最大 %.1f/%.1f/%.1fms",
                    r.optDouble("tcp_min_ms"), r.optDouble("tcp_avg_ms"), r.optDouble("tcp_max_ms")) : "")
                    + (r.optDouble("tls_ms", 0) > 0 ? String.format(Locale.getDefault(), " · TLS %.1fms", r.optDouble("tls_ms")) : " · TLS 未测/失败")
                    + "\n" + speed
                    + (r.optLong("download_bytes") > 0 ? String.format(Locale.getDefault(), "\n正文 %.2f MiB · 辅助峰值 %.2f MiB/s",
                    r.optLong("download_bytes") / 1048576.0, r.optDouble("download_peak_mib_s")) : "") + "\nECH：" + r.optString("ech_status") + " · " + r.optString("status")
                    + (r.optString("error").isEmpty() ? "" : "\n" + r.optString("error"))
                    + "\n" + (r.optLong("tested_at") > 0 ? DateFormat.getDateTimeInstance().format(new Date(r.optLong("tested_at"))) : "尚未测量"));
        }
    }
    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final MaterialCheckBox choice; final TextView detail;
        ResultHolder(View view, MaterialCheckBox choice, TextView detail) { super(view); this.choice = choice; this.detail = detail; }
    }
}
