/*
 ============================================================================
 Name        : ProfileListActivity.java
 Author      : Claude Code
 Description : Profile List Activity (Main Entry Point)
 ============================================================================
 */

package com.x.client.app;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.x.client.app.ui.ConnectionFab;
import com.x.client.app.ui.EmptyStateView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ProfileListActivity extends BaseActivity implements ProfileAdapter.OnProfileActionListener {
    private static final int REQUEST_VPN = 0;
    private static final int REQUEST_SCAN_QR = 1001;

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private ProfileAdapter adapter;
    private ConnectionFab vpnFab;
    private EmptyStateView emptyState;
    private FloatingActionButton fabMain;
    private Preferences prefs;
    private static volatile boolean profileMutation;
    private boolean pendingVpnStart = false;
    private boolean vpnStarting = false;
    private boolean vpnDisconnecting = false;
    private boolean statusReceiverRegistered = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable vpnStartupTimeout = () -> {
        if (!vpnStarting) {
            return;
        }
        vpnStarting = false;
        prefs.setEnable(false);
        // 超时 = 连接失败：FAB 原位换装为错误态（展开中则停留 3.5s，review §7.2）
        vpnFab.setState(ConnectionFab.State.ERROR,
                getString(R.string.status_error),
                getString(R.string.status_connecting_timeout));
    };
    /** 断开中乐观态兑底：无 STOPPING 广播，3s 未收到 STOPPED 则回 DISCONNECTED（review §7.3） */
    private final Runnable vpnDisconnectFallback = () -> {
        if (!vpnDisconnecting) {
            return;
        }
        vpnDisconnecting = false;
        updateConnectionState();
    };
    private final BroadcastReceiver vpnStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(TProxyService.EXTRA_STATUS);
            if (status == null) {
                return;
            }
            switch (status) {
                case TProxyService.STATUS_STARTING:
                    vpnStarting = true;
                    vpnFab.setState(ConnectionFab.State.CONNECTING,
                            getString(R.string.status_connecting),
                            getString(R.string.status_connecting_subtitle));
                    return;
                case TProxyService.STATUS_STARTED:
                    mainHandler.removeCallbacks(vpnStartupTimeout);
                    vpnStarting = false;
                    prefs.setEnable(true);
                    // morph 展开卡已是完整反馈，移除冗余 Toast（避免 TalkBack 双读，review §7.1）
                    vpnFab.setState(ConnectionFab.State.CONNECTED,
                            getString(R.string.status_connected),
                            currentProfileSubtitle());
                    break;
                case TProxyService.STATUS_ERROR:
                    mainHandler.removeCallbacks(vpnStartupTimeout);
                    mainHandler.removeCallbacks(vpnDisconnectFallback);
                    vpnStarting = false;
                    vpnDisconnecting = false;
                    prefs.setEnable(false);
                    String error = intent.getStringExtra(TProxyService.EXTRA_ERROR);
                    // 失败终态由 FAB 错误卡承载（内嵌重试/查看日志），不再走 Toast（review §7.1）
                    vpnFab.setState(ConnectionFab.State.ERROR,
                            getString(R.string.status_error),
                            error == null || error.trim().isEmpty()
                                    ? getString(R.string.vpn_start_failed) : error);
                    break;
                case TProxyService.STATUS_STOPPED:
                    mainHandler.removeCallbacks(vpnStartupTimeout);
                    mainHandler.removeCallbacks(vpnDisconnectFallback);
                    vpnStarting = false;
                    vpnDisconnecting = false;
                    prefs.setEnable(false);
                    vpnFab.setState(ConnectionFab.State.DISCONNECTED,
                            getString(R.string.status_disconnected),
                            getString(R.string.status_tap_to_connect));
                    break;
                default:
                    return;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Preferences(this);
        setContentView(R.layout.activity_profile_list);

        // 初始化 Toolbar
        toolbar = findViewById(R.id.toolbar);
        setupToolbar();

        // 初始化视图
        recyclerView = findViewById(R.id.profile_list);
        vpnFab = findViewById(R.id.vpn_fab);
        emptyState = findViewById(R.id.empty_state);
        fabMain = findViewById(R.id.fab_main);

        // 设置 RecyclerView（自适应单列/多列网格）
        int spanCount = getResources().getInteger(R.integer.profile_grid_columns);
        if (spanCount <= 1) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
        } else {
            recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));
        }
        adapter = new ProfileAdapter(this, prefs, spanCount);
        recyclerView.setAdapter(adapter);

        // 添加滚动监听器，滚动时收起 VPN FAB 展开卡（review §2.2）
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    vpnFab.collapse();
                }
            }
        });

        // 设置 FAB 点击事件
        fabMain.setOnClickListener(v -> showFabMenu());

        // TV 遥控器与 D-pad 焦点链导航连接
        fabMain.setFocusable(true);
        fabMain.setNextFocusDownId(R.id.vpn_fab);
        fabMain.setNextFocusLeftId(R.id.profile_list);
        vpnFab.setNextFocusUpId(R.id.fab_main);
        vpnFab.setNextFocusLeftId(R.id.profile_list);
        fabMain.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).translationZ(8f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
            }
        });

        // VPN FAB：tap=连接/断开主操作（展开态中 tap 由组件内部折叠，review §1）
        vpnFab.setOnToggleListener(() -> toggleVpn());
        // 错误展开卡内嵌动作：重试 / 查看日志（收编原 ErrorBanner 能力，review §7.1）
        vpnFab.setOnRetryListener(this::toggleVpn);
        vpnFab.setOnDetailsListener(() ->
                startActivity(new Intent(this, RuntimeLogActivity.class)));

        // 空态快捷行动按钮
        emptyState.setOnEmptyStateActionListener(new EmptyStateView.OnEmptyStateActionListener() {
            @Override
            public void onImportFromClipboard() {
                importFromClipboard();
            }

            @Override
            public void onScanQrCode() {
                scanQrCode();
            }

            @Override
            public void onAddManual() {
                showAddProfileDialog();
            }
        });
        emptyState.setAction(getString(R.string.btn_add), v -> showFabMenu());

        // 加载配置列表
        refreshProfileList();

        // 校正可能残留的 VPN 运行状态（APP 被意外终止后 Enable 可能为陈旧 true）
        reconcileVpnState();

        // 被动同步 FAB 状态（compact，无 morph）
        updateConnectionState();

    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新列表（从编辑页返回时）
        refreshProfileList();
        reconcileVpnState();
        updateConnectionState();

        // 电视模式下自动聚焦到当前选中的节点项（或首项），遥控器直接按 OK 即可连接/断开
        if (DeviceUtils.isTv(this)) {
            recyclerView.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                int selectedPos = adapter.getSelectedPosition();
                int targetPos = selectedPos >= 0 ? selectedPos : 0;
                if (adapter.getItemCount() > 0) {
                    recyclerView.scrollToPosition(targetPos);
                    recyclerView.postDelayed(() -> {
                        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(targetPos);
                        if (vh != null && vh.itemView != null) {
                            View fg = vh.itemView.findViewById(R.id.foreground_layout);
                            if (fg != null) {
                                fg.requestFocus();
                                return;
                            }
                        }
                        if (vpnFab != null && vpnFab.isShown()) {
                            vpnFab.requestFocus();
                        }
                    }, 100);
                } else if (fabMain != null && fabMain.isShown()) {
                    fabMain.requestFocus();
                }
            }, 250);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!statusReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    vpnStatusReceiver,
                    new IntentFilter(TProxyService.ACTION_STATUS),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            statusReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(vpnStatusReceiver);
            statusReceiverRegistered = false;
        }
        // 移除挂起的超时/兑底回调，避免操作已分离的 view（review §7.4）
        mainHandler.removeCallbacks(vpnStartupTimeout);
        mainHandler.removeCallbacks(vpnDisconnectFallback);
        super.onStop();
    }

    private void refreshProfileList() {
        List<Preferences.ProfileInfo> profiles = prefs.getProfileList();
        String selectedId = prefs.getCurrentProfileId();
        adapter.setProfiles(profiles, selectedId);
        // 空态指引（research-ux P8：空态必须指向下一步动作）
        boolean isEmpty = profiles.isEmpty();
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        if (isEmpty) {
            checkClipboardOnFirstRun();
        }
        // 当前项滚出视口后的被动补偿：保证回到主页/换配置后当前行可见（review §4）
        if (!isEmpty && recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
            LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
            int idx = -1;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id.equals(selectedId)) {
                    idx = i;
                    break;
                }
            }
            if (idx != -1 && (idx < lm.findFirstVisibleItemPosition()
                    || idx > lm.findLastVisibleItemPosition())) {
                lm.scrollToPosition(idx);
            }
        }
        updateConnectionState();
    }

    /** 展开卡副文案：当前配置上下文。 */
    private CharSequence currentProfileSubtitle() {
        String currentId = prefs.getCurrentProfileId();
        String name = currentId == null ? "" : prefs.getProfileName(currentId);
        return getString(R.string.home_current_profile, name);
    }

    /**
     * 被动同步 FAB 状态（compact，无 morph）：onResume/refresh 等场景调用。
     * 状态未变时 ConnectionFab.setState 内部 no-op，不打断进行中的停留计时。
     */
    private void updateConnectionState() {
        if (vpnStarting) {
            vpnFab.setState(ConnectionFab.State.CONNECTING,
                    getString(R.string.status_connecting),
                    getString(R.string.status_connecting_subtitle));
            return;
        }
        if (vpnDisconnecting) {
            vpnFab.setState(ConnectionFab.State.DISCONNECTING,
                    getString(R.string.status_disconnecting),
                    currentProfileSubtitle());
            return;
        }
        if (prefs.getEnable()) {
            vpnFab.setState(ConnectionFab.State.CONNECTED,
                    getString(R.string.status_connected),
                    currentProfileSubtitle());
        } else {
            vpnFab.setState(ConnectionFab.State.DISCONNECTED,
                    getString(R.string.status_disconnected),
                    getString(R.string.status_tap_to_connect));
        }
    }

    /**
     * 校正 VPN 运行状态：APP 进程被系统意外终止后，TProxyService(:vpn) 持有的
     * VPN 隧道随进程消亡，但持久化的 Enable 仍为 true，按钮会误显示「停止」。
     * 仅当全部满足以下条件才视为自身 VPN 运行中：
     *   1) Enable 标记为 true；
     *   2) 系统当前存在活跃的 VPN 网络；
     *   3) 自身的 TProxyService 服务存活（getRunningServices 只返回本应用的
     *      服务，因此其他 VPN 程序建立的 VPN 网络不会被当成自身的）。
     * 否则清除陈旧标记，让按钮回到「启动」。
     */
    private void reconcileVpnState() {
        if (!prefs.getEnable()) {
            return;
        }
        if (hasActiveVpnNetwork() && isOwnVpnServiceRunning()) {
            return;
        }
        prefs.setEnable(false);
    }

    private boolean hasActiveVpnNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean isOwnVpnServiceRunning() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
            if (info.service != null && TProxyService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void checkClipboardOnFirstRun() {
        if (!prefs.getProfileList().isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    String str = text.toString().trim();
                    if (ProfileUriCodec.isSupportedUri(str)) {
                        com.google.android.material.snackbar.Snackbar.make(
                                findViewById(android.R.id.content),
                                R.string.clipboard_detected_prompt,
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        ).setAction(R.string.preferred_btn_import_now, v -> importFromProtocol(str)).show();
                    }
                }
            }
        }
    }

    private void showFabMenu() {
        if (profileMutation) return;
        PopupMenu popup = new PopupMenu(this, fabMain);
        boolean hasCamera = DeviceUtils.hasCamera(this);
        if (hasCamera) {
            popup.getMenu().add(0, 1, 0, getString(R.string.scan_qr_code));
        }
        popup.getMenu().add(0, 2, 0, getString(R.string.import_config_link));
        popup.getMenu().add(0, 3, 0, getString(R.string.add_manual_profile));

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    scanQrCode();
                    return true;
                case 2:
                    showManualInputDialog();
                    return true;
                case 3:
                    showAddProfileDialog();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void showAddProfileDialog() {
        // 创建新配置并直接跳转到编辑页面
        String newId = UUID.randomUUID().toString();
        prefs.addProfile(newId, "新配置"); // 使用默认名称

        // 跳转到编辑页面，标记为新配置
        Intent intent = new Intent(this, ProfileEditActivity.class);
        intent.putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, newId);
        intent.putExtra(ProfileEditActivity.EXTRA_IS_NEW_PROFILE, true);
        startActivity(intent);
    }

    private void toggleVpn() {
        if (profileMutation) return;
        // 双向防抖：连接中/断开中均吞掉 tap（review §7.3）
        if (vpnStarting || vpnDisconnecting) {
            return;
        }
        boolean isVpnRunning = prefs.getEnable();

        if (isVpnRunning) {
            // 停止 VPN
            stopVpn();
        } else {
            // 启动 VPN
            startVpn();
        }
    }

    private void startVpn() {
        String currentProfileId = prefs.getCurrentProfileId();
        String wssAddr = prefs.getWorkerHostForProfile(currentProfileId);

        // 验证 WSS 地址不为空
        if (wssAddr == null || wssAddr.trim().isEmpty()) {
            Toast.makeText(this, "当前配置的服务器地址为空，请先编辑配置", Toast.LENGTH_LONG).show();
            return;
        }

        // 检查 VPN 权限授权状态
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            // 需要用户授权，保存待启动状态，授权返回后在 onActivityResult 中启动
            pendingVpnStart = true;
            startActivityForResult(prepareIntent, REQUEST_VPN);
        } else {
            // 已授权，直接启动
            doStartVpn();
        }
    }

    private void doStartVpn() {
        vpnStarting = true;
        prefs.setEnable(false);
        updateConnectionState();
        mainHandler.removeCallbacks(vpnStartupTimeout);
        mainHandler.postDelayed(vpnStartupTimeout, 60_000);

        Intent intent = new Intent(this, TProxyService.class);
        intent.setAction(TProxyService.ACTION_CONNECT);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception error) {
            mainHandler.removeCallbacks(vpnStartupTimeout);
            vpnStarting = false;
            prefs.setEnable(false);
            // 启动异常 = 错误态（FAB 错误卡内可重试/查看日志）
            vpnFab.setState(ConnectionFab.State.ERROR,
                    getString(R.string.status_error),
                    getString(R.string.cannot_start_vpn_service, error.getMessage()));
        }
    }

    private void stopVpn() {
        mainHandler.removeCallbacks(vpnStartupTimeout);
        vpnStarting = false;
        vpnDisconnecting = true;
        prefs.setEnable(false);
        // DISCONNECTING 乐观态：无 STOPPING 广播，3s 未收到 STOPPED 则兑底回 DISCONNECTED（review §7.3）
        vpnFab.setState(ConnectionFab.State.DISCONNECTING,
                getString(R.string.status_disconnecting),
                currentProfileSubtitle());
        mainHandler.removeCallbacks(vpnDisconnectFallback);
        mainHandler.postDelayed(vpnDisconnectFallback, 3_000);
        try {
            Intent intent = new Intent(this, TProxyService.class);
            startService(intent.setAction(TProxyService.ACTION_DISCONNECT));
        } catch (Exception e) {
            // 忽略服务停止异常
        }
    }

    @Override
    public void onProfileClick(String profileId) {
        if (profileMutation) return;
        // 只在配置真正改变时才刷新列表
        String currentId = prefs.getCurrentProfileId();
        if (profileId == null || !profileId.equals(currentId)) {
            if (prefs.getEnable()) {
                // VPN 运行中禁止切换配置（当前 VPN 绑定的是原配置）
                Toast.makeText(this, "VPN 正在运行，无法切换配置", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.setCurrentProfileId(profileId);
            // 延迟刷新，让关闭动画先完成（250ms）
            recyclerView.postDelayed(() -> refreshProfileList(), 250);
        } else {
            // 当前选中的节点再次点击或遥控器按 OK：一键启停 VPN
            toggleVpn();
        }
    }

    @Override
    public void onProfileLongClick(View anchor, String profileId, Preferences.ProfileInfo profile) {
        if (profileMutation) return;
        showProfileMenu(anchor, profileId, profile);
    }

    @Override
    public void onProfileMenuClick(View anchor, String profileId, Preferences.ProfileInfo profile) {
        if (profileMutation) return;
        showProfileMenu(anchor, profileId, profile);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View focused = getCurrentFocus();
            String profileId = findFocusedProfileId(focused);
            if (profileId != null) {
                showProfileMenu(focused, profileId, adapter.getProfileInfo(profileId));
                return true;
            } else {
                String currentId = prefs.getCurrentProfileId();
                if (currentId != null) {
                    showProfileMenu(null, currentId, adapter.getProfileInfo(currentId));
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private String findFocusedProfileId(View focused) {
        View v = focused;
        while (v != null && v != recyclerView) {
            if (v.getTag() instanceof String) {
                return (String) v.getTag();
            }
            if (v.getParent() instanceof View) {
                v = (View) v.getParent();
            } else {
                break;
            }
        }
        return null;
    }


    private void showProfileMenu(View anchor, String profileId, Preferences.ProfileInfo profile) {
        if (profileId == null) return;
        View targetAnchor = anchor != null ? anchor : recyclerView;
        if (targetAnchor == null) return;

        PopupMenu popup = new PopupMenu(this, targetAnchor);
        boolean isVpnRunning = prefs.getEnable();
        boolean isCurrent = profileId.equals(prefs.getCurrentProfileId());

        List<Runnable> actions = new ArrayList<>();

        if (isCurrent) {
            popup.getMenu().add(0, actions.size(), 0, getString(isVpnRunning ? R.string.acc_disconnect : R.string.acc_connect));
            actions.add(this::toggleVpn);
        } else {
            if (!isVpnRunning) {
                popup.getMenu().add(0, actions.size(), 0, getString(R.string.set_as_current));
                actions.add(() -> {
                    prefs.setCurrentProfileId(profileId);
                    refreshProfileList();
                });
            }
        }

        popup.getMenu().add(0, actions.size(), 0, getString(R.string.acc_edit));
        actions.add(() -> editProfile(profileId));

        popup.getMenu().add(0, actions.size(), 0, getString(R.string.acc_copy));
        actions.add(() -> copyProfile(profileId));

        popup.getMenu().add(0, actions.size(), 0, getString(R.string.acc_share));
        actions.add(() -> exportProfile(profileId));

        if (!isCurrent || !isVpnRunning) {
            popup.getMenu().add(0, actions.size(), 0, getString(R.string.acc_delete));
            actions.add(() -> deleteProfile(profileId));
        }

        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void editProfile(String profileId) {
        if (profileMutation) return;
        Intent intent = new Intent(this, ProfileEditActivity.class);
        intent.putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, profileId);
        startActivity(intent);
    }

    private void copyProfile(String profileId) {
        String originalName = prefs.getProfileName(profileId);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(originalName);
        input.setSelection(originalName.length());

        new AlertDialog.Builder(this)
                .setTitle("复制配置")
                .setMessage("输入新配置的名称")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        // 名称为空时沿用原名称；配置名称允许重复，底层以唯一 ID 区分
                        name = originalName;
                    }
                    String newId = UUID.randomUUID().toString();
                    String copyName = name;
                    mutateProfile(() -> prefs.copyProfile(profileId, newId, copyName), () -> {
                        refreshProfileList();
                        Toast.makeText(this, "配置已复制: " + copyName, Toast.LENGTH_SHORT).show();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteProfile(String profileId) {
        // 检查 VPN 是否正在运行且要删除的是当前配置
        boolean isVpnRunning = prefs.getEnable();
        boolean isDeletingCurrent = profileId.equals(prefs.getCurrentProfileId());

        if (isVpnRunning && isDeletingCurrent) {
            Toast.makeText(this, "VPN 正在运行，无法删除当前配置", Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示确认对话框
        new AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确认删除配置 " + prefs.getProfileName(profileId) + "?")
                .setPositiveButton("确定", (dialog, which) -> {
                    mutateProfile(() -> {
                        if (prefs.getEnable() && profileId.equals(prefs.getCurrentProfileId())) {
                            throw new IllegalStateException("VPN正在运行，无法删除当前配置");
                        }
                        prefs.removeProfile(profileId);
                        if (profileId.equals(prefs.getCurrentProfileId())) {
                            Set<String> remainingIds = prefs.getProfileIds();
                            if (!remainingIds.isEmpty()) prefs.setCurrentProfileId(remainingIds.iterator().next());
                        }
                    }, () -> {
                        refreshProfileList();
                        Toast.makeText(this, "配置已删除", Toast.LENGTH_SHORT).show();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface ProfileMutation { void run() throws Exception; }

    /** Pool JSON may be large: keep copy/delete/import mode IO off the UI thread. */
    private void mutateProfile(ProfileMutation mutation, Runnable after) {
        if (profileMutation) return;
        profileMutation = true;
        new Thread(() -> {
            String failure = null;
            try { mutation.run(); } catch (Exception error) { failure = error.getMessage() == null ? error.toString() : error.getMessage(); }
            String message = failure;
            runOnUiThread(() -> {
                profileMutation = false;
                if (isFinishing() || isDestroyed()) return;
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                else after.run();
            });
        }, "preferred-ip-profile").start();
    }

    // ======================== 导入/导出功能 ========================

    private void exportProfile(String profileId) {
        new Thread(() -> exportProfileSnapshot(profileId), "preferred-ip-share").start();
    }

    private void exportProfileSnapshot(String profileId) {
        Preferences prefs = this.prefs.forProfile(profileId);
        org.json.JSONObject effective;
        try {
            effective = PreferredIpResolver.baseParams(prefs);
            PreferredIpResolver.apply(prefs, effective, new PreferredIpStore(this).read());
        } catch (Exception error) {
            runOnUiThread(() -> Toast.makeText(this, "无法生成有效入口快照：" + error.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }

        String snapshot = ProfileUriCodec.exportUri(prefs, profileId, effective);
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showExportDialog(snapshot); });
    }

    private void showExportDialog(String protocol) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_export, null);
        TextView textProtocol = dialogView.findViewById(R.id.text_protocol);
        ImageView imageQr = dialogView.findViewById(R.id.image_qr);

        textProtocol.setText(protocol);

        // 生成 QR 码
        try {
            MultiFormatWriter writer = new MultiFormatWriter();
            BitMatrix matrix = writer.encode(protocol, BarcodeFormat.QR_CODE, 400, 400);
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.createBitmap(matrix);
            imageQr.setImageBitmap(bitmap);
        } catch (WriterException e) {
            e.printStackTrace();
            imageQr.setImageResource(android.R.drawable.ic_dialog_alert);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_profile_title)
                .setView(dialogView)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("X Client Protocol", protocol);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.system_share, (dialog, which) -> {
                    try {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, protocol);
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_profile)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.no_share_app_hint, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void importFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    String str = text.toString().trim();
                    if (ProfileUriCodec.isSupportedUri(str)) {
                        importFromProtocol(str);
                        return;
                    }
                }
            }
        }
        Toast.makeText(this, "剪贴板未发现有效配置链接，请手动输入", Toast.LENGTH_SHORT).show();
        showManualInputDialog();
    }

    private void showManualInputDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.manual_import_hint);
        input.setSingleLine(true);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    String str = text.toString().trim();
                    if (ProfileUriCodec.isSupportedUri(str)) {
                        input.setText(str);
                        input.setSelection(str.length());
                    }
                }
            }
        }
        FrameLayout container = new FrameLayout(this);
        int padH = dp(24);
        int padV = dp(8);
        container.setPadding(padH, padV, padH, padV);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_import)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                    String protocol = input.getText().toString().trim();
                    if (!protocol.isEmpty()) {
                        importFromProtocol(protocol);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void scanQrCode() {
        if (!DeviceUtils.hasCamera(this)) {
            Toast.makeText(this, R.string.no_camera_hint, Toast.LENGTH_SHORT).show();
            showManualInputDialog();
            return;
        }
        ScanOptions options = new ScanOptions();
        options.setPrompt(getString(R.string.scan_qr_code));
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(CustomCaptureActivity.class);
        try {
            // 使用 ScanOptions 创建 intent
            Intent intent = options.createScanIntent(this);
            startActivityForResult(intent, REQUEST_SCAN_QR);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_camera_hint, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_VPN) {
            if (result == RESULT_OK && pendingVpnStart) {
                pendingVpnStart = false;
                doStartVpn();
            } else {
                pendingVpnStart = false;
                Toast.makeText(this, "VPN 权限被拒绝", Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
        if (!ProfileUriCodec.isSupportedUri(protocol)) {
            Toast.makeText(this, "无效的协议格式", Toast.LENGTH_SHORT).show();
            return;
        }
        ProfileUriCodec.ParsedProfile parsed;
        try {
            parsed = ProfileUriCodec.parse(protocol);
        } catch (Exception e) {
            Toast.makeText(this, "无法解析配置链接：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        String newId = UUID.randomUUID().toString();
        showImportNameDialog(newId, parsed);
    }

    private void showImportNameDialog(final String id, final ProfileUriCodec.ParsedProfile parsed) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        if (parsed.name != null && !parsed.name.isEmpty()) {
            input.setText(parsed.name);
            input.setSelection(parsed.name.length());
        }
        FrameLayout container = new FrameLayout(this);
        int padH = dp(24);
        int padV = dp(8);
        container.setPadding(padH, padV, padH, padV);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_name)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = (parsed.name != null && !parsed.name.isEmpty())
                                ? parsed.name
                                : getString(R.string.unnamed_profile);
                    }
                    if (profileMutation) return;
                    prefs.addProfile(id, name);

                    Preferences profilePrefs = this.prefs.forProfile(id);
                    ProfileUriCodec.applyToProfile(profilePrefs, parsed);

                    mutateProfile(() -> {
                        // New URI profiles keep an unset mode, just like new manual profiles.
                        new PreferredIpStore(this).read();
                    }, () -> {
                        refreshProfileList();
                        Toast.makeText(this, "配置已导入；原入口保留，全局跟随需在优选页验证并确认", Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ======================== Toolbar ========================

    private void setupToolbar() {
        // 标题 = 应用名，副标题 = 版本号（M3 Toolbar 原生两行结构）
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            toolbar.setTitle(getString(R.string.app_name));
            toolbar.setSubtitle(getString(R.string.toolbar_version, packageInfo.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            toolbar.setTitle(getString(R.string.app_name));
        }

        int iconTint = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant);
        Menu menu = toolbar.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                icon.mutate().setTint(iconTint);
            }
        }

        // 顶栏快捷操作：优选中转、运行日志、全局设置
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_preferred_ip) {
                startActivity(new Intent(this, PreferredIpActivity.class));
                return true;
            } else if (id == R.id.action_logs) {
                startActivity(new Intent(this, RuntimeLogActivity.class));
                return true;
            } else if (id == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
