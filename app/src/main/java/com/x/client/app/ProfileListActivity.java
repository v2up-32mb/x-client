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
import android.content.BroadcastReceiver;
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
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.x.client.app.ui.ConnectionFab;
import com.x.client.app.ui.EmptyStateView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanOptions;
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

        // 设置 RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this, prefs);
        recyclerView.setAdapter(adapter);

        // 添加触摸监听器，点击空白处关闭打开的项
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    // 检查是否点击在任何 item 之外
                    View child = rv.findChildViewUnder(e.getX(), e.getY());
                    if (child == null) {
                        // 点击在空白处，关闭所有打开的项
                        adapter.closeAllItems();
                        return true; // 拦截事件
                    }
                }
                return false; // 不拦截，让 item 处理点击
            }
        });

        // 添加滚动监听器，滚动时关闭打开的项（含 VPN FAB 展开卡，与“一滚全收起”心智一致，review §2.2）
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    adapter.closeAllItems();
                    vpnFab.collapse();
                }
            }
        });

        // 设置 FAB 点击事件
        fabMain.setOnClickListener(v -> showFabMenu());

        // VPN FAB：tap=连接/断开主操作（展开态中 tap 由组件内部折叠，review §1）
        vpnFab.setOnToggleListener(() -> toggleVpn());
        // 错误展开卡内嵌动作：重试 / 查看日志（收编原 ErrorBanner 能力，review §7.1）
        vpnFab.setOnRetryListener(this::toggleVpn);
        vpnFab.setOnDetailsListener(() ->
                startActivity(new Intent(this, RuntimeLogActivity.class)));

        // 空态主按钮与 FAB 同义
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

    private void showFabMenu() {
        PopupMenu popup = new PopupMenu(this, fabMain);
        popup.getMenu().add(0, 1, 0, "导入");
        popup.getMenu().add(0, 2, 0, "新增");
        popup.getMenu().add(0, 3, 0, "设置");
        popup.getMenu().add(0, 4, 0, getString(R.string.view_runtime_logs));

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    importProfile();
                    return true;
                case 2:
                    showAddProfileDialog();
                    return true;
                case 3:
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                case 4:
                    startActivity(new Intent(this, RuntimeLogActivity.class));
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
        }
    }

    @Override
    public void onShareClick(String profileId) {
        // 分享配置
        exportProfile(profileId);
    }

    @Override
    public void onCopyClick(String profileId) {
        // 复制配置：弹出名称输入框，确定后以新 UUID 创建相同配置；取消则不创建
        copyProfile(profileId);
    }

    @Override
    public void onEditClick(String profileId) {
        // 编辑配置
        editProfile(profileId);
    }

    @Override
    public void onDeleteClick(String profileId) {
        // 删除配置
        deleteProfile(profileId);
    }

    @Override
    public void onBackPressed() {
        // 关闭所有打开的项
        adapter.closeAllItems();
        super.onBackPressed();
    }

    private void showProfileMenu(String profileId) {
        String profileName = prefs.getProfileName(profileId);
        boolean isVpnRunning = prefs.getEnable();
        boolean isCurrentProfile = profileId.equals(prefs.getCurrentProfileId());

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(profileName);

        // 根据 VPN 状态决定可用操作
        String[] items;
        if (isVpnRunning && isCurrentProfile) {
            // VPN 运行且是当前配置：只能分享
            items = new String[]{"分享"};
        } else {
            // 其他情况：可以编辑、删除、分享
            items = new String[]{"编辑", "删除", "分享"};
        }

        builder.setItems(items, (dialog, which) -> {
            if (isVpnRunning && isCurrentProfile) {
                // 只有分享选项
                if (which == 0) {
                    exportProfile(profileId);
                }
            } else {
                // 完整菜单
                switch (which) {
                    case 0: // 编辑
                        editProfile(profileId);
                        break;
                    case 1: // 删除
                        deleteProfile(profileId);
                        break;
                    case 2: // 分享
                        exportProfile(profileId);
                        break;
                }
            }
        });

        builder.show();
    }

    private void editProfile(String profileId) {
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
                    prefs.copyProfile(profileId, newId, name);
                    refreshProfileList();
                    Toast.makeText(this, "配置已复制: " + name, Toast.LENGTH_SHORT).show();
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
                    // 删除配置
                    prefs.removeProfile(profileId);

                    // 如果删除的是当前配置，自动选择第一个剩余配置（如果还有）
                    if (isDeletingCurrent) {
                        Set<String> remainingIds = prefs.getProfileIds();
                        if (!remainingIds.isEmpty()) {
                            String nextId = remainingIds.iterator().next();
                            prefs.setCurrentProfileId(nextId);
                        }
                    }

                    // 刷新列表
                    refreshProfileList();
                    Toast.makeText(this, "配置已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ======================== 导入/导出功能 ========================

    private void exportProfile(String profileId) {
        // 临时切换到目标配置以读取数据
        String originalId = prefs.getCurrentProfileId();
        prefs.setCurrentProfileId(profileId);

        // 生成协议字符串
        String protocolValue = prefs.getProtocol();

        String wssAddr = prefs.getWorkerHost();
        // 移除 wss:// 前缀（如果存在）
        if (wssAddr.startsWith("wss://")) {
            wssAddr = wssAddr.substring(6);
        }
        String prefIp = prefs.getPrefIp();
        String fallbackIp = prefs.getFallbackIp();
        String userId = prefs.getUserID();
        boolean disableEch = prefs.getDisableEch();
        int wsConn = prefs.getWsConn();
        boolean enableDynamicPool = prefs.getEnableDynamicPool();
        int dynamicPoolMax = prefs.getDynamicPoolMax();

        // X-Tunnel 配置：读取协议参数（必须在恢复当前配置之前读取）
        String xtToken = prefs.getXtToken();
        String xtRelayNodes = prefs.getXtRelayNodes();
        int xtConnections = prefs.getXtConnections();
        boolean xtDisableEch = prefs.getXtDisableEch();
        boolean xtInsecure = prefs.getXtInsecure();
        boolean xtEnableHotPair = prefs.getXtEnableHotPair();
        int xtHotPairCount = prefs.getXtHotPairCount();
        String xtIpStrategy = prefs.getXtIpStrategy();
        String xtServerAddr = prefs.getXtServerAddr();
        if (xtServerAddr.startsWith("wss://")) {
            xtServerAddr = xtServerAddr.substring(6);
        } else if (xtServerAddr.startsWith("ws://")) {
            xtServerAddr = xtServerAddr.substring(5);
        }

        // 恢复原配置
        prefs.setCurrentProfileId(originalId);

        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocolValue)) {
            // 构建 xtunnel:// URI：token/relay_nodes/connections/ech/insecure/hotpair
            StringBuilder xtQuery = new StringBuilder();
            if (!xtToken.isEmpty()) {
                xtQuery.append("token=").append(xtToken);
            }
            if (!xtRelayNodes.isEmpty()) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("relay_nodes=").append(xtRelayNodes);
            }
            if (xtConnections != Preferences.DEFAULT_XT_CONNECTIONS) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("connections=").append(xtConnections);
            }
            if (xtDisableEch) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("ech=0");
            }
            // ECH 域名与 DoH 服务器复用全局设置，不写入 URI
            if (xtInsecure) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("insecure=1");
            }
            if (xtEnableHotPair) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("hotpair=").append(xtHotPairCount);
            }
            // IP 策略：仅非默认值时写入 ips= 参数
            if (!Preferences.DEFAULT_XT_IP_STRATEGY.equals(xtIpStrategy)) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("ips=").append(xtIpStrategy);
            }
            String xtProtocol = "xtunnel://" + xtServerAddr;
            if (xtQuery.length() > 0) {
                xtProtocol += "?" + xtQuery.toString();
            }
            // 添加配置名称作为 fragment
            String xtName = prefs.getProfileName(profileId);
            try {
                xtName = java.net.URLEncoder.encode(xtName, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                // 忽略，使用原始名称
            }
            xtProtocol += "#" + xtName;
            showExportDialog(xtProtocol);
            return;
        }

        // 构建配置级查询参数：ip= 优选中转节点，fip= 出口代理 IP，user_id= 用户标识。
        StringBuilder query = new StringBuilder();
        if (!prefIp.isEmpty()) {
            query.append("ip=").append(prefIp);
        }
        if (!fallbackIp.isEmpty()) {
            if (query.length() > 0) query.append("&");
            query.append("fip=").append(fallbackIp);
        }
        if (!userId.isEmpty()) {
            if (query.length() > 0) query.append("&");
            query.append("user_id=").append(userId);
        }
        if (disableEch) {
            if (query.length() > 0) query.append("&");
            query.append("disable_ech=1");
        }
        // 连接池参数始终导出，保证分享无损
        if (query.length() > 0) query.append("&");
        query.append("ws_conn=").append(wsConn);
        query.append("&enable_dynamic_pool=").append(enableDynamicPool ? 1 : 0);
        query.append("&dynamic_pool_max=").append(dynamicPoolMax);

        String protocol = "gcm://" + wssAddr;
        if (query.length() > 0) {
            protocol += "?" + query.toString();
        }
        // 添加配置名称作为 fragment
        String profileName = prefs.getProfileName(profileId);
        // URL 编码配置名称以避免特殊字符破坏 fragment
        try {
            profileName = java.net.URLEncoder.encode(profileName, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // 忽略，使用原始名称
        }
        protocol += "#" + profileName;

        // 显示导出对话框
        showExportDialog(protocol);
    }

    private void showExportDialog(String protocol) {
        // 创建自定义对话框，包含 QR 码和协议文本
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导出配置");

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_export, null);
        TextView textProtocol = dialogView.findViewById(R.id.text_protocol);
        ImageView imageQr = dialogView.findViewById(R.id.image_qr);

        textProtocol.setText(protocol);

        // 生成 QR 码
        try {
            MultiFormatWriter writer = new MultiFormatWriter();
            BitMatrix matrix = writer.encode(protocol, BarcodeFormat.QR_CODE, 300, 300);
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.createBitmap(matrix);
            imageQr.setImageBitmap(bitmap);
        } catch (WriterException e) {
            e.printStackTrace();
            imageQr.setImageResource(android.R.drawable.ic_dialog_alert);
        }

        builder.setView(dialogView);
        builder.setPositiveButton("复制", (dialog, which) -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("GCM Protocol", protocol);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    private void importProfile() {
        // 显示导入选项对话框：手动输入或扫描 QR 码
        showImportDialog();
    }

    private void showImportDialog() {
        // 显示两个选项的对话框：手动输入和扫描 QR
        final CharSequence[] options = {"手动输入", "扫描二维码"};
        new AlertDialog.Builder(this)
                .setTitle("导入配置")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showManualInputDialog();
                    } else if (which == 1) {
                        scanQrCode();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualInputDialog() {
        final EditText input = new EditText(this);
        input.setHint("gcm://server.com?ip=1.1.1.1:443&fip=2.2.2.2&user_id=v2up#Name");
        new AlertDialog.Builder(this)
                .setTitle("导入配置")
                .setView(input)
                .setPositiveButton("确定", (dialog, whichButton) -> {
                    String protocol = input.getText().toString().trim();
                    if (!protocol.isEmpty()) {
                        importFromProtocol(protocol);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void scanQrCode() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("扫描配置二维码");
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(CustomCaptureActivity.class);
        try {
            // 使用 ScanOptions 创建 intent
            Intent intent = options.createScanIntent(this);
            startActivityForResult(intent, REQUEST_SCAN_QR);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动扫描器", Toast.LENGTH_SHORT).show();
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
        // 解析协议字符串：支持 gcm://、ech://（兼容）和 xtunnel://
        boolean isXtunnel = protocol.startsWith("xtunnel://");
        if (!isXtunnel && !protocol.startsWith("gcm://") && !protocol.startsWith("ech://")) {
            Toast.makeText(this, "无效的协议格式", Toast.LENGTH_SHORT).show();
            return;
        }
        String rest;
        if (isXtunnel) {
            rest = protocol.substring(10); // "xtunnel://" 之后（10 个字符）
        } else {
            rest = protocol.substring(6); // "gcm://" / "ech://" 之后
        }
        // 分离 fragment
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        String wssAddr;
        String query = "";
        int qmark = rest.indexOf('?');
        if (qmark >= 0) {
            wssAddr = rest.substring(0, qmark);
            query = rest.substring(qmark + 1);
        } else {
            wssAddr = rest;
        }
        // 容错：剥离 host 前的多余斜杠（旧版分享链接可能多一个 /）
        while (wssAddr.startsWith("/")) {
            wssAddr = wssAddr.substring(1);
        }
        // 确保 wss:// 前缀
        if (!wssAddr.startsWith("wss://")) {
            wssAddr = "wss://" + wssAddr;
        }
        // 拒绝缺少服务器主机的链接（如旧版 xtunnel://?token=... 无 host）
        if (wssAddr.startsWith("wss://") && wssAddr.length() <= 6) {
            Toast.makeText(this, "链接缺少服务器地址，无法导入", Toast.LENGTH_LONG).show();
            return;
        }

        // 解析查询参数：ip= 优选中转节点，fip= 出口代理 IP，user_id= 用户标识。
        // 旧文档中的 dns/domain 参数被忽略，它们现在属于全局设置。
        String prefIp = "";
        String fallbackIp = "";
        String userId = "";
        boolean disableEch = false;
        int wsConn = Preferences.DEFAULT_WS_CONN;
        boolean enableDynamicPool = false;
        int dynamicPoolMax = Preferences.DEFAULT_DYNAMIC_POOL_MAX;
        String xtToken = "";
        String xtRelayNodes = "";
        int xtConnections = Preferences.DEFAULT_XT_CONNECTIONS;
        boolean xtDisableEch = false;
        boolean xtInsecure = false;
        boolean xtEnableHotPair = false;
        int xtHotPairCount = Preferences.DEFAULT_XT_HOT_PAIR_COUNT;
        String xtIpStrategy = Preferences.DEFAULT_XT_IP_STRATEGY;
        if (!query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0];
                    String value = kv[1];
                    switch (key) {
                        case "ip":
                        case "relay":
                            // 兼容旧 relay= 参数：同样按优选中转节点处理
                            prefIp = value;
                            break;
                        case "fip":
                        case "fallbackip":
                            fallbackIp = value;
                            break;
                        case "disable_ech":
                            // 1/true/yes 表示禁用 ECH
                            disableEch = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "ws_conn":
                            try {
                                wsConn = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "enable_dynamic_pool":
                            enableDynamicPool = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "dynamic_pool_max":
                            try {
                                dynamicPoolMax = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "token":
                        case "user_id":
                            if (isXtunnel) {
                                xtToken = value;
                            } else {
                                userId = value;
                            }
                            break;
                        case "relay_nodes":
                            xtRelayNodes = value;
                            break;
                        case "connections":
                            try {
                                xtConnections = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "ech":
                            // ech=0 表示禁用 ECH（与 GCM 的 disable_ech 语义一致）
                            xtDisableEch = value.equals("0") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no");
                            break;
                        // 兼容旧 URI：domain/dns 参数忽略，复用全局设置
                        case "domain":
                        case "dns":
                            break;
                        case "insecure":
                            xtInsecure = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "hotpair":
                            // hotpair=1/true/yes 兼容旧格式（启用 1 对）；hotpair=2..8 表示启用 N 对
                            int hotPairValue = 1;
                            try {
                                hotPairValue = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                            }
                            if (hotPairValue >= 2 && hotPairValue <= Preferences.MAX_XT_HOT_PAIR_COUNT) {
                                xtEnableHotPair = true;
                                xtHotPairCount = hotPairValue;
                            } else {
                                xtEnableHotPair = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                                xtHotPairCount = 1;
                            }
                            break;
                        case "ips":
                            // IP 策略（default/4/6/4,6/6,4）；缺失回落默认，非法值忽略
                            if (Preferences.isValidXtIpStrategy(value)) {
                                xtIpStrategy = value;
                            }
                            break;
                    }
                }
            }
        }

        // 从 fragment 解码配置名称
        String defaultName = "导入节点";
        if (!fragment.isEmpty()) {
            try {
                defaultName = java.net.URLDecoder.decode(fragment, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                defaultName = fragment;
            }
        }

        // 使用这些参数创建新配置
        String newId = UUID.randomUUID().toString();
        // 询问用户配置名称
        showImportNameDialog(newId, defaultName, isXtunnel ? Preferences.PROTOCOL_X_TUNNEL : Preferences.PROTOCOL_GCM,
                wssAddr, prefIp, fallbackIp, userId, disableEch,
                wsConn, enableDynamicPool, dynamicPoolMax,
                xtToken, xtRelayNodes, xtConnections, xtDisableEch, xtInsecure, xtEnableHotPair, xtHotPairCount,
                xtIpStrategy);
    }

    private void showImportNameDialog(final String id, final String defaultName,
                                      final String protocol,
                                      final String wssAddr, final String prefIp,
                                      final String fallbackIp, final String userId,
                                      final boolean disableEch,
                                      final int wsConn, final boolean enableDynamicPool,
                                      final int dynamicPoolMax,
                                      final String xtToken, final String xtRelayNodes,
                                      final int xtConnections, final boolean xtDisableEch,
                                      final boolean xtInsecure, final boolean xtEnableHotPair,
                                      final int xtHotPairCount, final String xtIpStrategy) {
        final EditText input = new EditText(this);
        input.setText(defaultName);
        new AlertDialog.Builder(this)
                .setTitle("配置名称")
                .setView(input)
                .setPositiveButton("确定", (dialog, whichButton) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = defaultName;
                    }
                    // 添加配置
                    prefs.addProfile(id, name);

                    // 临时切换到新配置以设置参数
                    String originalId = prefs.getCurrentProfileId();
                    prefs.setCurrentProfileId(id);

                    // 设置协议与参数
                    prefs.setProtocol(protocol);
                    if (Preferences.PROTOCOL_X_TUNNEL.equals(protocol)) {
                        prefs.setXtServerAddr(wssAddr);
                        prefs.setXtToken(xtToken);
                        prefs.setXtRelayNodes(xtRelayNodes);
                        prefs.setXtConnections(xtConnections);
                        prefs.setXtDisableEch(xtDisableEch);
                        prefs.setXtInsecure(xtInsecure);
                        prefs.setXtEnableHotPair(xtEnableHotPair);
                        prefs.setXtHotPairCount(xtHotPairCount);
                        prefs.setXtIpStrategy(xtIpStrategy);
                    } else {
                        prefs.setWorkerHost(wssAddr);
                        prefs.setPrefIp(prefIp);
                        if (!fallbackIp.isEmpty()) prefs.setFallbackIp(fallbackIp);
                        prefs.setUserID(userId);
                        prefs.setDisableEch(disableEch);
                        prefs.setWsConn(wsConn);
                        prefs.setEnableDynamicPool(enableDynamicPool);
                        prefs.setDynamicPoolMax(dynamicPoolMax);
                    }

                    // 恢复原配置
                    prefs.setCurrentProfileId(originalId);

                    // 刷新列表
                    refreshProfileList();
                    Toast.makeText(this, "配置已导入", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ======================== Toolbar ========================

    private void setupToolbar() {
        // 标题 = 应用名，副标题 = 版本号（M3 Toolbar 原生两行结构，颜色走主题 attr，
        // 替代旧的硬编码白色自绘 TextView——顶栏已回归中性 surface 色）
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            toolbar.setTitle(getString(R.string.app_name));
            toolbar.setSubtitle(getString(R.string.toolbar_version, packageInfo.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            // 降级处理：只显示 APP 名称
            toolbar.setTitle(getString(R.string.app_name));
        }
        // 明暗模式已收敛到全局设置「外观」分组，Toolbar 不再提供快捷菜单
    }
}
