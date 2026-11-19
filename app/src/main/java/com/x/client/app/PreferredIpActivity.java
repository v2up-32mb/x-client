package com.x.client.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native two-page UI. There is no service, fake progress, save preview or selected/log page. */
public class PreferredIpActivity extends BaseActivity implements PreferredIpController.Listener {
    private PreferredIpController controller;
    private PreferredIpController.State state;
    // Latest published Store selection, independent of any older in-flight adapter diff.
    private final Set<String> selectedAddresses = new HashSet<>();
    private ViewPager pager;
    private RecyclerView list;
    private LinearLayoutManager layout;
    private ResultAdapter adapter;
    private View header;
    private TextView status, sourceStatus, sourceSummary, parseSummary, referenceDetail;
    private TextView phaseBadge, networkBadge, metricProgress, metricSuccess, metricSelected, ledgerText;
    private View parseCard;
    private TextInputEditText manual, sourceUrl, sample, concurrency;
    private Spinner reference;
    private List<Preferences.ProfileInfo> profiles;
    private LinearLayout sourceRows;
    private MaterialButton accept, primary;
    private String sourceKey = "";
    private final List<View> sourceNetworkButtons = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService differ = Executors.newSingleThreadExecutor();
    private boolean touched, destroyed, settingsReady;
    private String accessibilityAddress = "";
    private long interaction;
    private int initialSample = 256, initialConcurrency = 8, initialReference;
    private android.os.Parcelable pendingScroll;
    private String pendingAction = null;
    private String pendingActionTarget = null;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String id = getIntent().getStringExtra(ProfileEditActivity.EXTRA_PROFILE_ID);
        if (id == null) id = new Preferences(this).getCurrentProfileId();
        controller = PreferredIpRuntime.acquire(this, id);
        initialSample = controller.uiSample; initialConcurrency = controller.uiConcurrency;
        if (!controller.uiReference.isEmpty()) id = controller.uiReference;
        if (saved != null) {
            controller.tab = saved.getInt("tab", controller.tab);
            controller.manualDraft = saved.getString("manual", controller.manualDraft);
            controller.urlDraft = saved.getString("url", controller.urlDraft);
            ArrayList<String> expanded = saved.getStringArrayList("expanded");
            if (expanded != null) { controller.expanded.clear(); controller.expanded.addAll(expanded); }
            initialSample = saved.getInt("sample", 256); initialConcurrency = saved.getInt("concurrency", 8);
            initialReference = saved.getInt("reference", 0);
            controller.scroll = saved.getParcelable("scroll");
        }
        setContentView(R.layout.activity_preferred_ip);
        MaterialToolbar toolbar = findViewById(R.id.preferred_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.getMenu().add(0, R.id.preferred_restart, 0, getString(R.string.preferred_restart_test));
        toolbar.getMenu().add(0, R.id.preferred_enable, 1, getString(R.string.preferred_enable_global_action));
        toolbar.getMenu().add(0, R.id.preferred_disable_action, 2, R.string.preferred_disable);
        toolbar.setOnMenuItemClickListener(item -> {
            int action = item.getItemId();
            if (action == R.id.preferred_restart) {
                if (state != null && state.busy) {
                    controller.pause(getString(R.string.preferred_pause_user));
                    Toast.makeText(this, "正在暂停当前测试，请稍候重试", Toast.LENGTH_SHORT).show();
                } else {
                    confirm(getString(R.string.preferred_restart_confirm_title), getString(R.string.preferred_restart_confirm_msg), this::startScan);
                }
            } else if (action == R.id.preferred_enable) controller.enable();
            else if (action == R.id.preferred_disable_action) confirm(getString(R.string.preferred_disable), getString(R.string.preferred_disable_body), () -> controller.disable());
            return true;
        });
        View sources = buildSources(), tests = buildTests(id);
        pager = findViewById(R.id.preferred_pager);
        pager.setAdapter(new PagerAdapter() {
            private final View[] pages = {sources, tests};
            @Override public int getCount() { return 2; }
            @Override public boolean isViewFromObject(View view, Object object) { return view == object; }
            @Override public Object instantiateItem(ViewGroup container, int position) { container.addView(pages[position]); return pages[position]; }
            @Override public void destroyItem(ViewGroup container, int position, Object object) { container.removeView((View) object); }
            @Override public CharSequence getPageTitle(int position) { return getString(position == 0 ? R.string.preferred_sources : R.string.preferred_test); }
        });
        ((TabLayout) findViewById(R.id.preferred_tabs)).setupWithViewPager(pager);
        pager.setCurrentItem(Math.min(1, controller.tab), false);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) { controller.tab = position; }
        });
    }

    @Override protected void onStart() { super.onStart(); controller.attach(this); }
    @Override protected void onStop() { remember(); controller.detach(isChangingConfigurations()); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true;
        differ.shutdown();
        main.removeCallbacksAndMessages(null);
        if (isFinishing()) {
            PreferredIpRuntime.forget();
        }
        super.onDestroy();
    }

    private void remember() {
        if (manual != null) controller.manualDraft = manual.getText().toString();
        if (sourceUrl != null) controller.urlDraft = sourceUrl.getText().toString();
        if (layout != null) controller.scroll = layout.onSaveInstanceState();
        if (sample != null) controller.uiSample = number(sample, 256);
        if (concurrency != null) controller.uiConcurrency = number(concurrency, 8);
        if (profiles != null && !profiles.isEmpty() && reference != null && reference.getSelectedItemPosition() >= 0)
            controller.uiReference = profiles.get(reference.getSelectedItemPosition()).id;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        remember(); out.putInt("tab", controller.tab); out.putString("manual", controller.manualDraft); out.putString("url", controller.urlDraft);
        out.putStringArrayList("expanded", new ArrayList<>(controller.expanded)); out.putParcelable("scroll", controller.scroll);
        out.putInt("sample", number(sample, 256)); out.putInt("concurrency", number(concurrency, 8));
        out.putInt("reference", reference == null ? 0 : reference.getSelectedItemPosition());
        super.onSaveInstanceState(out);
    }

    private int dimen(int id) { return getResources().getDimensionPixelSize(id); }

    private void confirm(String title, String body, Runnable action) {
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> action.run()).show();
    }

    private View buildSources() {
        View view = LayoutInflater.from(this).inflate(R.layout.layout_preferred_sources, null, false);
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

        btnFetchV4.setOnClickListener(v -> {
            pendingAction = "fetch_v4";
            pendingActionTarget = "cf-v4";
            controller.fetch("cf-v4", "https://www.cloudflare.com/ips-v4", "cidr4");
        });
        btnFetchV6.setOnClickListener(v -> {
            pendingAction = "fetch_v6";
            pendingActionTarget = "cf-v6";
            controller.fetch("cf-v6", "https://www.cloudflare.com/ips-v6", "cidr6");
        });
        sourceNetworkButtons.add(btnFetchV4);
        sourceNetworkButtons.add(btnFetchV6);

        btnFetchList.setOnClickListener(v -> {
            String url = sourceUrl.getText().toString().trim();
            if (!url.startsWith("https://")) {
                sourceUrl.setError(getString(R.string.preferred_err_https_url));
                confirm(getString(R.string.preferred_err_url_format_title), getString(R.string.preferred_err_url_format_msg), () -> {});
                return;
            }
            pendingAction = "fetch_list";
            pendingActionTarget = url;
            controller.fetch(PreferredIpIds.opaque("list", url), url, "list");
        });
        sourceNetworkButtons.add(btnFetchList);

        btnParseManual.setOnClickListener(v -> {
            String text = manual.getText().toString().trim();
            if (text.isEmpty()) {
                manual.setError(getString(R.string.preferred_err_input_empty));
                return;
            }
            pendingAction = "parse_manual";
            pendingActionTarget = "manual";
            controller.parse(text, "manual", "manual");
        });
        btnLegacy.setOnClickListener(v -> controller.legacy());
        accept.setOnClickListener(v -> controller.acceptParsed());

        return view;
    }

    private View buildTests(String referenceId) {
        list = new RecyclerView(this);
        list.setId(R.id.preferred_results);
        layout = new LinearLayoutManager(this);
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dimen(R.dimen.spacing_xl));

        header = LayoutInflater.from(this).inflate(R.layout.layout_preferred_tests_header, list, false);
        profiles = new Preferences(this).getProfileList();

        reference = header.findViewById(R.id.preferred_reference);
        referenceDetail = header.findViewById(R.id.preferred_reference_detail);
        sample = header.findViewById(R.id.preferred_sample_input);
        concurrency = header.findViewById(R.id.preferred_concurrency_input);
        status = header.findViewById(R.id.preferred_status);
        primary = header.findViewById(R.id.preferred_primary);

        phaseBadge = header.findViewById(R.id.preferred_phase_badge);
        networkBadge = header.findViewById(R.id.preferred_network_badge);
        metricProgress = header.findViewById(R.id.preferred_metric_progress);
        metricSuccess = header.findViewById(R.id.preferred_metric_success);
        metricSelected = header.findViewById(R.id.preferred_metric_selected);
        ledgerText = header.findViewById(R.id.preferred_ledger_text);

        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        sample.setText(Integer.toString(initialSample));
        concurrency.setText(Integer.toString(initialConcurrency));

        List<String> names = new ArrayList<>();
        int selected = initialReference;
        for (int i = 0; i < profiles.size(); i++) {
            names.add(profiles.get(i).name);
            if (initialReference == 0 && referenceId.equals(profiles.get(i).id)) selected = i;
        }
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reference.setAdapter(choices);
        if (!profiles.isEmpty()) {
            reference.setSelection(Math.max(0, Math.min(selected, profiles.size() - 1)));
        }

        primary.setOnClickListener(v -> primaryAction());

        reference.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos < profiles.size()) controller.inspectReference(profiles.get(pos).id);
                settingsChanged();
            }
            public void onNothingSelected(AdapterView<?> a) { }
        });

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) { settingsChanged(); }
            public void afterTextChanged(Editable e) { }
        };
        sample.addTextChangedListener(watcher);
        concurrency.addTextChangedListener(watcher);
        settingsReady = true;

        adapter = new ResultAdapter(header);
        list.setAdapter(adapter);

        list.getViewTreeObserver().addOnPreDrawListener(() -> {
            adapter.reconcileVisibleSelections();
            return true;
        });
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(RecyclerView v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { touched = true; interaction++; }
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    touched = false; interaction++; main.postDelayed(() -> adapter.submitLatest(), 250);
                }
                return false;
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recycler, int value) {
                interaction++; if (value == RecyclerView.SCROLL_STATE_IDLE) adapter.submitLatest();
            }
        });
        pendingScroll = controller.scroll;
        return list;
    }

    private void settingsChanged() {
        if (!settingsReady || profiles.isEmpty() || reference.getSelectedItemPosition() < 0) return;
        controller.parametersChanged(profiles.get(reference.getSelectedItemPosition()).id, number(sample, -1), number(concurrency, -1));
    }

    private static int number(TextInputEditText edit, int fallback) {
        try { return Integer.parseInt(edit.getText().toString()); } catch (Exception e) { return fallback; }
    }

    private void newDownloadRound() {
        if (profiles.isEmpty() || reference.getSelectedItemPosition() < 0) {
            confirm(getString(R.string.preferred_no_profile_title), getString(R.string.preferred_no_profiles), () -> { }); return;
        }
        controller.newDownloadRound(profiles.get(reference.getSelectedItemPosition()).id,
                number(sample, -1), number(concurrency, -1));
    }

    private void primaryAction() {
        if (state != null && state.busy) { controller.pause(getString(R.string.preferred_pause_user)); return; }
        if (state != null && !state.sessionId.isEmpty() && !state.stale) controller.resume(); else startScan();
    }

    private void startScan() {
        if (profiles.isEmpty()) { confirm(getString(R.string.preferred_no_profile_title), getString(R.string.preferred_no_profiles), () -> { }); return; }
        int count = number(sample, -1), workers = number(concurrency, -1);
        if (count < 1 || count > 4096) { sample.setError(getString(R.string.preferred_err_sample_range)); return; }
        if (workers < 1 || workers > 32) { concurrency.setError(getString(R.string.preferred_err_concurrency_range)); return; }
        String id = profiles.get(reference.getSelectedItemPosition()).id;
        Runnable start = () -> controller.test(id, count, workers, PreferredIpController.PROBE_DOWNLOAD);
        if (state != null && state.metered) confirm(getString(R.string.preferred_metered_confirm_title), getString(R.string.preferred_metered_confirm_msg), start);
        else start.run();
    }

    private static boolean isSourcePresent(JSONArray sources, String id) {
        if (sources == null || id == null) return false;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return true;
        }
        return false;
    }

    private void handlePendingActionResult(PreferredIpController.State next) {
        String action = pendingAction;
        String target = pendingActionTarget;
        pendingAction = null;
        pendingActionTarget = null;

        if ("fetch_list".equals(action)) {
            String sourceId = PreferredIpIds.opaque("list", target);
            boolean added = isSourcePresent(next.data.optJSONArray("sources"), sourceId);
            if (next.parse != null) {
                JSONArray cands = next.parse.optJSONArray("candidates");
                JSONArray errs = next.parse.optJSONArray("errors");
                int candCount = cands == null ? 0 : cands.length();
                int errCount = errs == null ? 0 : errs.length();
                if (candCount > 0) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.preferred_dialog_list_parse_title)
                            .setMessage("已获取到 " + candCount + " 个有效中转 IP" + (errCount > 0 ? "（另有 " + errCount + " 行不合规或注释行已自动过滤）" : "") + "。\n\n是否立即加入已配置来源？")
                            .setNegativeButton(R.string.preferred_btn_discard, null)
                            .setPositiveButton(R.string.preferred_btn_import_valid, (d, w) -> {
                                controller.acceptParsed();
                                Toast.makeText(this, "已成功导入 " + candCount + " 个中转 IP", Toast.LENGTH_SHORT).show();
                            })
                            .show();
                } else {
                    confirm(getString(R.string.preferred_err_no_valid_ip_title), "该列表中未发现符合「IP:端口」格式的有效中转 IP（共 " + errCount + " 行不合规）。请检查该列表内容。\n\n标准格式示例：\n104.16.24.5:443#香港", () -> {});
                }
            } else if (added) {
                Toast.makeText(this, R.string.preferred_msg_list_added, Toast.LENGTH_SHORT).show();
            } else {
                String errMsg = next.message;
                if (errMsg == null || errMsg.isEmpty() || errMsg.contains("已保存")) {
                    errMsg = "无法从该链接获取内容或列表内容为空，请检查链接可达性。";
                }
                confirm(getString(R.string.preferred_err_fetch_list_failed), errMsg, () -> {});
            }
        } else if ("fetch_v4".equals(action) || "fetch_v6".equals(action)) {
            boolean added = isSourcePresent(next.data.optJSONArray("sources"), target);
            if (added) {
                Toast.makeText(this, R.string.preferred_msg_official_updated, Toast.LENGTH_SHORT).show();
            } else {
                String errMsg = next.message;
                if (errMsg == null || errMsg.isEmpty()) errMsg = "更新官方地址库失败，请检查网络连接。";
                confirm(getString(R.string.preferred_err_update_failed), errMsg, () -> {});
            }
        } else if ("refresh_source".equals(action)) {
            if (next.message != null && (next.message.contains("失败") || next.message.contains("错误") || next.message.contains("未完成"))) {
                confirm(getString(R.string.preferred_err_refresh_source_failed), next.message, () -> {});
            } else {
                Toast.makeText(this, R.string.preferred_msg_source_refreshed, Toast.LENGTH_SHORT).show();
            }
        } else if ("parse_manual".equals(action)) {
            if (next.parse != null) {
                JSONArray cands = next.parse.optJSONArray("candidates");
                JSONArray errs = next.parse.optJSONArray("errors");
                int candCount = cands == null ? 0 : cands.length();
                int errCount = errs == null ? 0 : errs.length();
                if (candCount > 0) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.preferred_dialog_manual_parse_title)
                            .setMessage("已识别到 " + candCount + " 个有效中转 IP" + (errCount > 0 ? "（" + errCount + " 行不合规）" : "") + "。\n\n是否立即导入？")
                            .setNegativeButton(R.string.preferred_btn_cancel, null)
                            .setPositiveButton(R.string.preferred_btn_import_now, (d, w) -> {
                                controller.acceptParsed();
                                Toast.makeText(this, "已导入 " + candCount + " 个中转 IP", Toast.LENGTH_SHORT).show();
                            })
                            .show();
                } else {
                    confirm(getString(R.string.preferred_err_no_valid_ip_title), "未发现有效的 IP:端口 地址行。示例：104.16.24.5:443#香港", () -> {});
                }
            }
        }
    }

    @Override public void changed(PreferredIpController.State next) {
        boolean wasBusy = state != null && state.busy;
        boolean nowBusy = next.busy;
        state = next;
        selectedAddresses.clear();
        JSONArray selected = next.data.optJSONArray("selected");
        for (int i = 0; selected != null && i < selected.length(); i++)
            selectedAddresses.add(selected.optJSONObject(i).optString("address"));

        if (referenceDetail != null) {
            referenceDetail.setText(next.referenceSummary != null ? next.referenceSummary.trim() : "");
        }

        String fullSummary = next.phase + " · 已完成 " + next.completed + "/" + next.total + "（成功 " + next.success + " / 失败 " + (next.completed - next.success) + "）"
                + "\n已选 " + next.selected + (next.saving ? " · 保存中" : "") + " · " + next.network
                + String.format(Locale.getDefault(), "\n测速已用流量 %.1f MiB", next.used / 1048576.0)
                + (next.stale ? "\n旧上下文仅为历史" : "") + "\n" + next.message;

        if (status != null) {
            status.setContentDescription(fullSummary);
            if (next.message != null && !next.message.isEmpty()) {
                status.setText(next.message);
                status.setVisibility(View.VISIBLE);
            } else if (next.stale) {
                status.setText("ℹ️ 当前旧上下文仅为历史");
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
            }
        }

        if (sourceStatus != null) {
            sourceStatus.setText(next.message);
            sourceStatus.setVisibility(next.message != null && !next.message.isEmpty() ? View.VISIBLE : View.GONE);
        }

        if (phaseBadge != null) {
            String p = (next.phase == null || next.phase.isEmpty()) ? "准备就绪" : PreferredIpPresentation.phase(next.phase);
            phaseBadge.setText(p);
        }
        if (networkBadge != null) {
            networkBadge.setText((next.network == null || next.network.isEmpty()) ? "直连网络" : next.network);
        }
        if (metricProgress != null) metricProgress.setText(next.completed + " / " + next.total);
        if (metricSuccess != null) metricSuccess.setText(String.valueOf(next.success));
        if (metricSelected != null) metricSelected.setText(String.valueOf(next.selected));
        if (ledgerText != null) {
            String ledgerStr = String.format(Locale.getDefault(), "⚡ 测速流量消耗：已用 %.1f MiB",
                    next.used / 1048576.0);
            ledgerText.setText(ledgerStr);
        }

        primary.setText(next.busy ? (next.paused ? "取消 / 等待退出" : "暂停") : next.sessionId.isEmpty() || next.stale ? "开始测试" : "继续测试");
        for (View view : sourceNetworkButtons) view.setEnabled(!next.busy);
        renderSources(); adapter.reconcileVisibleSelections(); adapter.submitLatest();

        // 当异步操作（抓取/更新）执行结束时，主动给用户反馈与弹窗
        if (wasBusy && !nowBusy && pendingAction != null) {
            handlePendingActionResult(next);
        }
    }

    @Override public void selectionSettled(String address) {
        if (!destroyed) adapter.rebindSelection(address);
    }

    private void renderSources() {
        if (sourceRows == null) return;
        JSONArray sources = state.data.optJSONArray("sources"); String key = String.valueOf(sources) + state.busy + state.sourceMessages;
        if (!key.equals(sourceKey)) {
            sourceKey = key; sourceRows.removeAllViews();
            sourceSummary.setText(sources == null || sources.length() == 0 ? getString(R.string.preferred_empty_sources) : "来源修改不改变本轮冻结队列；重新开始时采用。");
            for (int i = 0; sources != null && i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                String id = source.optString("id"), url = source.optString("url");
                View rowView = LayoutInflater.from(this).inflate(R.layout.item_preferred_source, sourceRows, false);

                MaterialCheckBox on = rowView.findViewById(R.id.source_checkbox);
                TextView title = rowView.findViewById(R.id.source_title);
                TextView chip = rowView.findViewById(R.id.source_count_chip);
                TextView statusText = rowView.findViewById(R.id.source_status_text);
                ImageButton refresh = rowView.findViewById(R.id.btn_refresh_source);
                ImageButton delete = rowView.findViewById(R.id.btn_delete_source);

                title.setText(PreferredIpPresentation.source(source));
                on.setChecked(source.optBoolean("enabled", true));
                on.setOnCheckedChangeListener((b, checked) -> controller.enableSource(id, checked));
                chip.setText(source.optJSONArray("candidates").length() + " IP · " + source.optJSONArray("prefixes").length() + " 段");
                statusText.setText(state.sourceMessages.getOrDefault(id, "已有来源快照"));

                if (!url.isEmpty()) {
                    refresh.setVisibility(View.VISIBLE);
                    refresh.setEnabled(!state.busy);
                    refresh.setOnClickListener(v -> {
                        pendingAction = "refresh_source";
                        pendingActionTarget = id;
                        controller.fetch(id, url, source.optString("kind"));
                    });
                } else {
                    refresh.setVisibility(View.GONE);
                }

                delete.setOnClickListener(v -> confirm(getString(R.string.preferred_delete_source_title), getString(R.string.preferred_delete_source_msg), () -> controller.deleteSource(id)));
                sourceRows.addView(rowView);
            }
        }
        if (parseCard != null) {
            parseCard.setVisibility(state.parse == null ? View.GONE : View.VISIBLE);
        }
        if (accept != null) {
            accept.setVisibility(state.parse == null ? View.GONE : View.VISIBLE);
        }
        StringBuilder parsed = new StringBuilder();
        if (state.parse != null) {
            JSONArray rows = state.parse.optJSONArray("candidates"), errors = state.parse.optJSONArray("errors");
            parsed.append("有效 IP：").append(rows == null ? 0 : rows.length());
            for (int i = 0; errors != null && i < errors.length(); i++) {
                JSONObject error = errors.optJSONObject(i);
                parsed.append("\n第").append(error.optInt("line")).append("行：").append(error.optString("message"))
                        .append('\n').append(state.parseThirdParty ? error.optString("text").split("#", 2)[0] : error.optString("text"));
            }
        }
        if (parseSummary != null) parseSummary.setText(parsed);
    }

    private boolean deferMoves() {
        return touched || list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || !accessibilityAddress.isEmpty();
    }

    private void trackAccessibility(View view, ResultHolder holder) {
        if (view == null) return;
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void sendAccessibilityEvent(View host, int eventType) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) { accessibilityAddress = holder.address; interaction++; }
                else if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED && accessibilityAddress.equals(holder.address)) {
                    accessibilityAddress = ""; interaction++; main.postDelayed(() -> adapter.submitLatest(), 250);
                }
                super.sendAccessibilityEvent(host, eventType);
            }
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (host == holder.heading) {
                    info.setClassName("android.widget.Button");
                    info.setContentDescription(holder.heading.getContentDescription());
                }
            }
        });
    }

    private final class ResultAdapter extends RecyclerView.Adapter<ResultHolder> {
        private final View header;
        private List<PreferredIpController.Row> rows = Collections.emptyList();
        private final Map<String, Long> ids = new HashMap<>();
        private long nextId = 1;
        private boolean diffRunning, pending;
        ResultAdapter(View header) { this.header = header; setHasStableIds(true); }
        @Override public int getItemCount() { return rows.size() + 1; }
        @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }
        @Override public long getItemId(int position) {
            if (position == 0) return 0;
            String address = rows.get(position - 1).address;
            Long id = ids.get(address); if (id == null) { id = nextId++; ids.put(address, id); } return id;
        }
        void reconcileVisibleSelections() {
            if (destroyed || state == null || list == null) return;
            // Bounded by attached children, not the candidate list. No row bind/move/focus change.
            for (int i = 0; i < list.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = list.getChildViewHolder(list.getChildAt(i));
                if (!(vh instanceof ResultHolder)) continue;
                ResultHolder holder = (ResultHolder) vh;
                int position = holder.getBindingAdapterPosition();
                if (position <= 0 || position > rows.size()) continue;
                String address = rows.get(position - 1).address;
                if (address.equals(holder.address)
                        && holder.choice != null
                        && holder.choice.isChecked() != selectedAddresses.contains(address))
                    bindChoiceSelection(holder, address);
            }
        }
        private void bindChoiceSelection(ResultHolder holder, String address) {
            if (holder.choice == null) return;
            holder.choice.setOnCheckedChangeListener(null);
            // Also used after detach/attach when equal Row models need no DiffUtil bind.
            holder.choice.setChecked(selectedAddresses.contains(address));
            holder.choice.setOnCheckedChangeListener((button, on) -> {
                if (address.equals(holder.address) && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION)
                    controller.select(address, on);
            });
        }
        void rebindSelection(String address) {
            // Native checkbox state can differ even when sameRows/DiffUtil sees equal models.
            // Resolve the address against CURRENT rows; never retain a position across a diff.
            for (int i = 0; i < rows.size(); i++) {
                if (address.equals(rows.get(i).address)) { notifyItemChanged(i + 1); return; }
            }
        }
        void submitLatest() {
            if (destroyed || state == null) return;
            if (diffRunning) { pending = true; return; }
            List<PreferredIpController.Row> desired = state.rows;
            if (deferMoves()) {
                // Update content/check states without moving the touched or TalkBack-focused address.
                Map<String, PreferredIpController.Row> latest = new LinkedHashMap<>();
                for (PreferredIpController.Row row : desired) latest.put(row.address, row);
                List<PreferredIpController.Row> held = new ArrayList<>();
                for (PreferredIpController.Row row : rows) { PreferredIpController.Row update = latest.remove(row.address); held.add(update == null ? row : update); }
                // New items wait until interaction ends; retaining removed rows avoids stealing focus.
                desired = held;
            }
            final List<PreferredIpController.Row> before = rows, after = desired;
            if (sameRows(before, after)) return;
            final long fence = interaction;
            diffRunning = true; pending = false;
            differ.execute(() -> {
                DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                    public int getOldListSize() { return before.size(); }
                    public int getNewListSize() { return after.size(); }
                    public boolean areItemsTheSame(int oldPos, int newPos) { return before.get(oldPos).address.equals(after.get(newPos).address); }
                    public boolean areContentsTheSame(int oldPos, int newPos) { return before.get(oldPos).content().equals(after.get(newPos).content()); }
                });
                main.post(() -> {
                    diffRunning = false; if (destroyed) return;
                    if (fence != interaction) { submitLatest(); return; }
                    int first = layout.findFirstVisibleItemPosition(); View anchor = layout.findViewByPosition(first);
                    int offset = anchor == null ? 0 : layout.getDecoratedTop(anchor)
                            - ((RecyclerView.LayoutParams) anchor.getLayoutParams()).topMargin
                            - list.getPaddingTop();
                    String address = first > 0 && first <= before.size() ? before.get(first - 1).address : "";
                    rows = after;
                    diff.dispatchUpdatesTo(new ListUpdateCallback() {
                        public void onInserted(int position, int count) { notifyItemRangeInserted(position + 1, count); }
                        public void onRemoved(int position, int count) { notifyItemRangeRemoved(position + 1, count); }
                        public void onMoved(int from, int to) { notifyItemMoved(from + 1, to + 1); }
                        public void onChanged(int position, int count, Object payload) { notifyItemRangeChanged(position + 1, count, payload); }
                    });
                    if (pendingScroll != null && !rows.isEmpty()) {
                        layout.onRestoreInstanceState(pendingScroll); pendingScroll = null;
                    } else if (!address.isEmpty() && !deferMoves()) {
                        for (int i = 0; i < rows.size(); i++) if (address.equals(rows.get(i).address)) { layout.scrollToPositionWithOffset(i + 1, offset); break; }
                    }
                    if (pending) submitLatest();
                });
            });
        }
        private boolean sameRows(List<PreferredIpController.Row> a, List<PreferredIpController.Row> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!a.get(i).address.equals(b.get(i).address) || !a.get(i).content().equals(b.get(i).content())) return false;
            return true;
        }
        @Override public ResultHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == 0) {
                if (header.getParent() != null) ((ViewGroup) header.getParent()).removeView(header);
                return new ResultHolder(header);
            }
            View card = LayoutInflater.from(PreferredIpActivity.this).inflate(R.layout.item_preferred_result, parent, false);
            ResultHolder holder = new ResultHolder(card);
            holder.heading = card.findViewById(R.id.preferred_row_heading);
            holder.choice = card.findViewById(R.id.preferred_choice);
            holder.addressText = card.findViewById(R.id.preferred_row_address);
            holder.location = card.findViewById(R.id.preferred_row_location);
            holder.score = card.findViewById(R.id.preferred_row_score);
            holder.chevron = card.findViewById(R.id.preferred_row_chevron);
            holder.expanded = card.findViewById(R.id.preferred_row_expanded);
            holder.detail = card.findViewById(R.id.preferred_row_detail);
            holder.download = card.findViewById(R.id.preferred_row_download);

            trackAccessibility(holder.heading, holder);
            trackAccessibility(holder.choice, holder);
            trackAccessibility(holder.detail, holder);
            trackAccessibility(holder.download, holder);

            holder.heading.setOnClickListener(v -> {
                String bound = holder.address; if (bound.isEmpty()) return;
                if (!controller.expanded.remove(bound)) controller.expanded.add(bound);
                controller.refreshDetails();
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) notifyItemChanged(position);
            });
            return holder;
        }
        @Override public void onBindViewHolder(ResultHolder holder, int position) {
            if (position == 0) return;
            PreferredIpController.Row row = rows.get(position - 1);
            holder.address = row.address;
            holder.itemView.setTag(row.address);
            holder.heading.setTag(row.address);
            holder.choice.setTag(row.address);

            // A late diff must also use the newest published Store selection.
            bindChoiceSelection(holder, row.address);
            holder.choice.setEnabled(row.selectable && !state.saving);
            holder.choice.setContentDescription("选择 " + row.address);

            if (row.location.isEmpty()) {
                holder.location.setVisibility(View.GONE);
            } else {
                holder.location.setVisibility(View.VISIBLE);
                holder.location.setText(row.location);
            }

            holder.addressText.setText(row.address.replace(":", ":\u200b"));
            holder.score.setText(row.score.isEmpty() ? "未测试" : ("评分 " + row.score));

            boolean expanded = controller.expanded.contains(row.address);
            holder.heading.setContentDescription(row.location + "，" + row.address + "，评分 " + row.score + (expanded ? "，已展开，点击收起" : "，已折叠，点击展开"));
            holder.expanded.setVisibility(expanded ? View.VISIBLE : View.GONE);
            // 折叠状态显示 > (0度)，展开状态旋转 90 度指向下方
            if (holder.chevron != null) holder.chevron.setRotation(expanded ? 90f : 0f);
            holder.detail.setText(expanded && row.detail.isEmpty() ? "读取详情…" : row.detail);

            boolean downloading = row.address.equals(state.activeDownload);
            holder.download.setText(downloading ? getString(R.string.preferred_cancel_speed_test) : getString(R.string.preferred_speed_test_btn));
            holder.download.setEnabled(downloading || row.downloadable);
            holder.download.setOnClickListener(v -> {
                if (!row.address.equals(holder.address)) return;
                if (row.address.equals(state.activeDownload)) { controller.pause(getString(R.string.preferred_pause_speed_test)); return; }
                Runnable download = () -> controller.download(row.address);
                if (state.metered) confirm(getString(R.string.preferred_metered_speed_title), getString(R.string.preferred_metered_speed_msg), download);
                else download.run();
            });
        }
    }
    private static final class ResultHolder extends RecyclerView.ViewHolder {
        String address = "";
        MaterialCheckBox choice;
        View heading, expanded;
        TextView location, addressText, score, detail;
        ImageView chevron;
        MaterialButton download;
        ResultHolder(View view) { super(view); }
    }
}
