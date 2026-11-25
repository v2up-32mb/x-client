package com.x.client.app;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encapsulates the UI views, metrics dashboard, spinner, and primary action of the Tests tab.
 */
public class PreferredIpTestsBinder {

    public interface Listener {
        void onReferenceSelected(String profileId);
        void onParametersChanged(String profileId, int sample, int concurrency);
        void onPrimaryAction();
    }

    private View rootView;
    private Listener listener;
    private Spinner reference;
    private TextView referenceDetail;
    private TextInputEditText sample;
    private TextInputEditText concurrency;
    private TextView status;
    private MaterialButton primary;
    private TextView phaseBadge;
    private TextView networkBadge;
    private TextView metricProgress;
    private TextView metricSuccess;
    private TextView metricSelected;
    private TextView ledgerText;

    private List<Preferences.ProfileInfo> profiles = new ArrayList<>();
    private String currentReferenceId;
    private PreferredIpController.State lastState;
    private boolean settingsReady;
    private int initialSample = 256;
    private int initialConcurrency = 16;
    private int initialReference = 0;

    public PreferredIpTestsBinder(int initialSample, int initialConcurrency, int initialReference) {
        this.initialSample = initialSample;
        this.initialConcurrency = initialConcurrency;
        this.initialReference = initialReference;
    }

    public View inflateAndBind(@NonNull ViewGroup parent, @NonNull Listener listener) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.layout_preferred_tests_header, parent, false);
        bind(view, listener);
        return view;
    }

    public View getRootView() {
        return rootView;
    }

    public void bind(@NonNull View view, @NonNull Listener listener) {
        this.rootView = view;
        this.listener = listener;

        reference = view.findViewById(R.id.preferred_reference);
        referenceDetail = view.findViewById(R.id.preferred_reference_detail);
        sample = view.findViewById(R.id.preferred_sample_input);
        concurrency = view.findViewById(R.id.preferred_concurrency_input);
        status = view.findViewById(R.id.preferred_status);
        primary = view.findViewById(R.id.preferred_primary);

        phaseBadge = view.findViewById(R.id.preferred_phase_badge);
        networkBadge = view.findViewById(R.id.preferred_network_badge);
        metricProgress = view.findViewById(R.id.preferred_metric_progress);
        metricSuccess = view.findViewById(R.id.preferred_metric_success);
        metricSelected = view.findViewById(R.id.preferred_metric_selected);
        ledgerText = view.findViewById(R.id.preferred_ledger_text);

        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        sample.setText(String.valueOf(initialSample));
        concurrency.setText(String.valueOf(initialConcurrency));

        primary.setOnClickListener(v -> {
            if (this.listener != null) this.listener.onPrimaryAction();
        });

        // TV 遥控器焦点高光
        TvFocusHelper.applyFocusFeedback(primary);
        TvFocusHelper.applyParentFocusFeedback(reference);

        reference.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos >= 0 && pos < profiles.size() && PreferredIpTestsBinder.this.listener != null) {
                    PreferredIpTestsBinder.this.listener.onReferenceSelected(profiles.get(pos).id);
                }
                notifySettingsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) { }
        });

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { notifySettingsChanged(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        sample.addTextChangedListener(watcher);
        concurrency.addTextChangedListener(watcher);
        settingsReady = true;

        if (!profiles.isEmpty()) {
            populateSpinner(view.getContext());
        }
        if (lastState != null) {
            updateState(lastState);
        }
    }

    public void updateProfiles(Context context, List<Preferences.ProfileInfo> profileList, String referenceId) {
        this.profiles = profileList == null ? new ArrayList<>() : new ArrayList<>(profileList);
        this.currentReferenceId = referenceId;
        if (reference != null && context != null) {
            populateSpinner(context);
        }
    }

    private void populateSpinner(Context context) {
        if (reference == null || context == null) return;

        List<String> names = new ArrayList<>();
        int selected = initialReference;
        for (int i = 0; i < profiles.size(); i++) {
            names.add(profiles.get(i).name);
            if (initialReference == 0 && currentReferenceId != null && currentReferenceId.equals(profiles.get(i).id)) {
                selected = i;
            }
        }
        ArrayAdapter<String> choices = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, names);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reference.setAdapter(choices);
        if (!profiles.isEmpty()) {
            reference.setSelection(Math.max(0, Math.min(selected, profiles.size() - 1)));
        }
    }

    private void notifySettingsChanged() {
        if (!settingsReady || profiles.isEmpty() || reference == null || reference.getSelectedItemPosition() < 0) return;
        if (listener != null) {
            String profileId = profiles.get(reference.getSelectedItemPosition()).id;
            listener.onParametersChanged(profileId, getSampleCount(), getConcurrencyCount());
        }
    }

    public void updateState(PreferredIpController.State state) {
        if (state == null) return;
        this.lastState = state;

        if (referenceDetail != null) {
            referenceDetail.setText(state.referenceSummary != null ? state.referenceSummary.trim() : "");
        }

        String fullSummary = state.phase + " · 已完成 " + state.completed + "/" + state.total + "（成功 " + state.success
                + " / 失败 " + (state.completed - state.success) + "）"
                + "\n已选 " + state.selected + (state.saving ? " · 保存中" : "") + " · " + state.network
                + String.format(Locale.getDefault(), "\n测速已用流量 %.1f MiB", state.used / 1048576.0)
                + (state.stale ? "\n旧上下文仅为历史" : "") + "\n" + state.message;

        if (status != null) {
            status.setContentDescription(fullSummary);
            if (state.message != null && !state.message.isEmpty()) {
                status.setText(state.message);
                status.setVisibility(View.VISIBLE);
            } else if (state.stale) {
                status.setText("ℹ️ 当前旧上下文仅为历史");
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
            }
        }

        if (phaseBadge != null) {
            String p = (state.phase == null || state.phase.isEmpty()) ? "准备就绪" : PreferredIpPresentation.phase(state.phase);
            phaseBadge.setText(p);
        }
        if (networkBadge != null) {
            networkBadge.setText((state.network == null || state.network.isEmpty()) ? "直连网络" : state.network);
        }
        if (metricProgress != null) metricProgress.setText(state.completed + " / " + state.total);
        if (metricSuccess != null) metricSuccess.setText(String.valueOf(state.success));
        if (metricSelected != null) metricSelected.setText(String.valueOf(state.selected));
        if (ledgerText != null) {
            String ledgerStr = String.format(Locale.getDefault(), "⚡ 测速流量消耗：已用 %.1f MiB", state.used / 1048576.0);
            ledgerText.setText(ledgerStr);
        }

        if (primary != null) {
            primary.setText(state.busy ? (state.paused ? "取消 / 等待退出" : "暂停")
                    : state.sessionId.isEmpty() || state.stale ? "开始测试" : "继续测试");
        }
    }

    public int getSampleCount() {
        if (sample == null) return initialSample;
        try {
            return Integer.parseInt(sample.getText().toString());
        } catch (Exception e) {
            return initialSample;
        }
    }

    public int getConcurrencyCount() {
        if (concurrency == null) return initialConcurrency;
        try {
            return Integer.parseInt(concurrency.getText().toString());
        } catch (Exception e) {
            return initialConcurrency;
        }
    }

    public String getSelectedProfileId() {
        if (profiles.isEmpty() || reference == null || reference.getSelectedItemPosition() < 0) return null;
        return profiles.get(reference.getSelectedItemPosition()).id;
    }

    public int getSelectedPosition() {
        return reference == null ? 0 : reference.getSelectedItemPosition();
    }

    public void setSampleError(String error) {
        if (sample != null) sample.setError(error);
    }

    public void setConcurrencyError(String error) {
        if (concurrency != null) concurrency.setError(error);
    }

    public void onDestroy() {
        listener = null;
        rootView = null;
        reference = null;
        referenceDetail = null;
        sample = null;
        concurrency = null;
        status = null;
        primary = null;
        phaseBadge = null;
        networkBadge = null;
        metricProgress = null;
        metricSuccess = null;
        metricSelected = null;
        ledgerText = null;
        lastState = null;
        settingsReady = false;
    }
}
