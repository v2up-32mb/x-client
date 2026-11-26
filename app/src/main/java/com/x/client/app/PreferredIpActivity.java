package com.x.client.app;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Native two-page coordinator for preferred IP optimization.
 * Refactored to Material 3 standards, Android TV focus navigation, and ConcatAdapter architecture.
 */
public class PreferredIpActivity extends BaseActivity implements
        PreferredIpController.Listener,
        PreferredIpController.ActionListener,
        PreferredIpResultAdapter.ItemListener,
        PreferredIpTestsBinder.Listener {

    private PreferredIpController controller;
    private PreferredIpController.State state;
    private final Set<String> selectedAddresses = new HashSet<>();

    private PreferredIpSourcesBinder sourcesBinder;
    private PreferredIpTestsBinder testsBinder;
    private PreferredIpHeaderAdapter headerAdapter;
    private PreferredIpResultAdapter resultAdapter;
    private ConcatAdapter concatAdapter;

    private ViewPager pager;
    private RecyclerView list;
    private LinearLayoutManager layout;

    private List<Preferences.ProfileInfo> profiles = new ArrayList<>();
    private int initialSample = 256;
    private int initialConcurrency = 16;
    private int initialReference = 0;
    private Parcelable pendingScroll;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);

        String id = getIntent().getStringExtra(ProfileEditActivity.EXTRA_PROFILE_ID);
        if (id == null) id = new Preferences(this).getCurrentProfileId();
        controller = PreferredIpRuntime.acquire(this, id);

        initialSample = controller.uiSample;
        initialConcurrency = controller.uiConcurrency;
        if (!controller.uiReference.isEmpty()) id = controller.uiReference;

        if (saved != null) {
            controller.tab = saved.getInt("tab", controller.tab);
            controller.manualDraft = saved.getString("manual", controller.manualDraft);
            controller.urlDraft = saved.getString("url", controller.urlDraft);
            ArrayList<String> expanded = saved.getStringArrayList("expanded");
            if (expanded != null) {
                controller.expanded.clear();
                controller.expanded.addAll(expanded);
            }
            initialSample = saved.getInt("sample", 256);
            initialConcurrency = saved.getInt("concurrency", 16);
            initialReference = saved.getInt("reference", 0);
            controller.scroll = saved.getParcelable("scroll");
        }

        setContentView(R.layout.activity_preferred_ip);

        MaterialToolbar toolbar = findViewById(R.id.preferred_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_preferred_ip);
        toolbar.setOnMenuItemClickListener(item -> {
            int action = item.getItemId();
            if (action == R.id.preferred_restart) {
                if (state != null && state.busy) {
                    controller.pause(getString(R.string.preferred_pause_user));
                    Toast.makeText(this, "正在暂停当前测试，请稍候重试", Toast.LENGTH_SHORT).show();
                } else {
                    confirm(getString(R.string.preferred_restart_confirm_title),
                            getString(R.string.preferred_restart_confirm_msg), this::startScan);
                }
            } else if (action == R.id.preferred_enable) {
                controller.enable();
            } else if (action == R.id.preferred_disable_action) {
                confirm(getString(R.string.preferred_disable),
                        getString(R.string.preferred_disable_body), () -> controller.disable());
            }
            return true;
        });

        // 初始化组件 Binders
        sourcesBinder = new PreferredIpSourcesBinder(this, controller);
        testsBinder = new PreferredIpTestsBinder(initialSample, initialConcurrency, initialReference);

        View sourcesPage = sourcesBinder.inflateAndBind(findViewById(R.id.preferred_pager));
        View testsPage = buildTests(id);

        pager = findViewById(R.id.preferred_pager);
        pager.setAdapter(new PagerAdapter() {
            private final View[] pages = {sourcesPage, testsPage};
            @Override public int getCount() { return 2; }
            @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }
            @NonNull
            @Override public Object instantiateItem(@NonNull ViewGroup container, int position) {
                container.addView(pages[position]);
                return pages[position];
            }
            @Override public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
            @Override public CharSequence getPageTitle(int position) {
                return getString(position == 0 ? R.string.preferred_sources : R.string.preferred_test);
            }
        });

        TabLayout tabLayout = findViewById(R.id.preferred_tabs);
        tabLayout.setupWithViewPager(pager);
        pager.setCurrentItem(Math.min(1, controller.tab), false);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) {
                controller.tab = position;
            }
        });
    }

    private View buildTests(String referenceId) {
        list = new RecyclerView(this);
        list.setId(R.id.preferred_results);
        layout = new LinearLayoutManager(this);
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.spacing_xl));

        headerAdapter = new PreferredIpHeaderAdapter(testsBinder, this);
        resultAdapter = new PreferredIpResultAdapter(this);
        ConcatAdapter.Config config = new ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                .build();
        concatAdapter = new ConcatAdapter(config, headerAdapter, resultAdapter);
        list.setAdapter(concatAdapter);

        profiles = loadEligibleProfiles();
        testsBinder.updateProfiles(this, profiles, referenceId);

        pendingScroll = controller.scroll;
        return list;
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.attach(this);
        controller.setActionListener(this);
        profiles = loadEligibleProfiles();
        testsBinder.updateProfiles(this, profiles, controller.uiReference);
    }

    private List<Preferences.ProfileInfo> loadEligibleProfiles() {
        List<Preferences.ProfileInfo> filtered = new ArrayList<>();
        for (Preferences.ProfileInfo info : new Preferences(this).getProfileList()) {
            if (info != null && !Preferences.PROTOCOL_SOCKS5.equals(info.protocol)) {
                filtered.add(info);
            }
        }
        return filtered;
    }

    @Override
    protected void onStop() {
        remember();
        controller.detach(isChangingConfigurations());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (sourcesBinder != null) sourcesBinder.onDestroy();
        if (testsBinder != null) testsBinder.onDestroy();
        if (isFinishing()) {
            PreferredIpRuntime.forget();
        }
        super.onDestroy();
    }

    private void remember() {
        if (sourcesBinder != null) sourcesBinder.rememberDrafts();
        if (layout != null) controller.scroll = layout.onSaveInstanceState();
        if (testsBinder != null) {
            controller.uiSample = testsBinder.getSampleCount();
            controller.uiConcurrency = testsBinder.getConcurrencyCount();
            String profileId = testsBinder.getSelectedProfileId();
            if (profileId != null) controller.uiReference = profileId;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        remember();
        out.putInt("tab", controller.tab);
        out.putString("manual", controller.manualDraft);
        out.putString("url", controller.urlDraft);
        out.putStringArrayList("expanded", new ArrayList<>(controller.expanded));
        out.putParcelable("scroll", controller.scroll);
        if (testsBinder != null) {
            out.putInt("sample", testsBinder.getSampleCount());
            out.putInt("concurrency", testsBinder.getConcurrencyCount());
            out.putInt("reference", testsBinder.getSelectedPosition());
        }
        super.onSaveInstanceState(out);
    }

    private void confirm(String title, String body, Runnable action) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (action != null) action.run();
                })
                .show();
    }

    // --- PreferredIpController.Listener 回调 ---

    @Override
    public void changed(PreferredIpController.State next) {
        state = next;
        selectedAddresses.clear();
        JSONArray selected = next.data.optJSONArray("selected");
        for (int i = 0; selected != null && i < selected.length(); i++) {
            JSONObject sel = selected.optJSONObject(i);
            if (sel != null) selectedAddresses.add(sel.optString("address"));
        }

        // 更新 Binders
        if (testsBinder != null) testsBinder.updateState(next);
        if (sourcesBinder != null) sourcesBinder.updateState(next);

        // 提交候选列表给 0-indexed ListAdapter
        if (resultAdapter != null) {
            resultAdapter.setSelectedAddresses(selectedAddresses);
            resultAdapter.setExpandedAddresses(controller.expanded);
            resultAdapter.setActiveDownload(next.activeDownload);
            resultAdapter.submitList(next.rows, () -> {
                if (pendingScroll != null && layout != null) {
                    layout.onRestoreInstanceState(pendingScroll);
                    pendingScroll = null;
                }
            });
        }
    }

    @Override
    public void selectionSettled(String address) {
        if (resultAdapter != null) {
            resultAdapter.rebindSelection(address);
        }
    }

    // --- PreferredIpController.ActionListener 回调 ---

    @Override
    public void onActionResult(PreferredIpController.ActionResult result) {
        if (sourcesBinder != null) {
            sourcesBinder.handleActionResult(result);
        }
    }

    // --- PreferredIpResultAdapter.ItemListener 回调 ---

    @Override
    public void onToggleSelect(String address, boolean checked) {
        if (address != null && controller != null) {
            controller.select(address, checked);
        }
    }

    @Override
    public void onToggleExpand(String address) {
        if (address == null || controller == null) return;
        if (!controller.expanded.remove(address)) {
            controller.expanded.add(address);
        }
        controller.refreshDetails();
        if (resultAdapter != null) {
            resultAdapter.setExpandedAddresses(controller.expanded);
            resultAdapter.rebindSelection(address);
        }
    }

    @Override
    public void onStartDownload(String address) {
        if (address == null || controller == null || state == null) return;
        if (address.equals(state.activeDownload)) {
            controller.pause(getString(R.string.preferred_pause_speed_test));
            return;
        }
        Runnable download = () -> controller.download(address);
        if (state.metered) {
            confirm(getString(R.string.preferred_metered_speed_title),
                    getString(R.string.preferred_metered_speed_msg), download);
        } else {
            download.run();
        }
    }

    // --- PreferredIpTestsBinder.Listener 回调 ---

    @Override
    public void onReferenceSelected(String profileId) {
        if (profileId != null && controller != null) {
            controller.inspectReference(profileId);
        }
    }

    @Override
    public void onParametersChanged(String profileId, int sample, int concurrency) {
        if (profileId != null && controller != null) {
            controller.parametersChanged(profileId, sample, concurrency);
        }
    }

    @Override
    public void onPrimaryAction() {
        if (state != null && state.busy) {
            controller.pause(getString(R.string.preferred_pause_user));
            return;
        }
        if (state != null && !state.sessionId.isEmpty() && !state.stale) {
            controller.resume();
        } else {
            startScan();
        }
    }

    private void startScan() {
        if (profiles.isEmpty()) {
            confirm(getString(R.string.preferred_no_profile_title),
                    getString(R.string.preferred_no_profiles), () -> {});
            return;
        }
        if (testsBinder == null) return;

        int count = testsBinder.getSampleCount();
        int workers = testsBinder.getConcurrencyCount();

        if (count < 1 || count > 4096) {
            testsBinder.setSampleError(getString(R.string.preferred_err_sample_range));
            return;
        }
        if (workers < 1 || workers > 64) {
            testsBinder.setConcurrencyError(getString(R.string.preferred_err_concurrency_range));
            return;
        }

        String profileId = testsBinder.getSelectedProfileId();
        if (profileId == null) return;

        Runnable start = () -> controller.test(profileId, count, workers, PreferredIpController.PROBE_DOWNLOAD);
        if (state != null && state.metered) {
            confirm(getString(R.string.preferred_metered_confirm_title),
                    getString(R.string.preferred_metered_confirm_msg), start);
        } else {
            start.run();
        }
    }
}
