/*
 ============================================================================
 Name        : ProfileListActivity.java
 Author      : Claude Code
 Description : Profile List Activity (Main Entry Point)
 ============================================================================
 */

package com.x.client.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
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

public class ProfileListActivity extends AppCompatActivity implements ProfileAdapter.OnProfileActionListener {
    private static final int REQUEST_VPN = 0;
    private static final int REQUEST_SCAN_QR = 1001;

    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private ProfileAdapter adapter;
    private Button btnStart;
    private FloatingActionButton fabMain;
    private Preferences prefs;
    private boolean pendingVpnStart = false;
    private boolean vpnStarting = false;
    private boolean statusReceiverRegistered = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable vpnStartupTimeout = () -> {
        if (!vpnStarting) {
            return;
        }
        vpnStarting = false;
        prefs.setEnable(false);
        updateStartButton();
        Toast.makeText(this, "VPN 启动超时，请重试", Toast.LENGTH_LONG).show();
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
                    break;
                case TProxyService.STATUS_STARTED:
                    mainHandler.removeCallbacks(vpnStartupTimeout);
                    vpnStarting = false;
                    prefs.setEnable(true);
                    Toast.makeText(ProfileListActivity.this, "VPN 已启动", Toast.LENGTH_SHORT).show();
                    break;
                case TProxyService.STATUS_ERROR:
                    mainHandler.removeCallbacks(vpnStartupTimeout);
                    vpnStarting = false;
                    prefs.setEnable(false);
                    String error = intent.getStringExtra(TProxyService.EXTRA_ERROR);
                    String message = error == null || error.trim().isEmpty()
                            ? "VPN 启动失败"
                            : "VPN 启动失败: " + error;
                    Toast.makeText(ProfileListActivity.this, message, Toast.LENGTH_LONG).show();
                    break;
                case TProxyService.STATUS_STOPPED:
                    mainHandler.removeCallbacks(vpnStartupTimeout);
                    vpnStarting = false;
                    prefs.setEnable(false);
                    break;
                default:
                    return;
            }
            updateStartButton();
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
        btnStart = findViewById(R.id.btn_start);
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

        // 添加滚动监听器，滚动时关闭打开的项
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    adapter.closeAllItems();
                }
            }
        });

        // 设置 FAB 点击事件
        fabMain.setOnClickListener(v -> showFabMenu());

        // 设置启动按钮点击事件
        btnStart.setOnClickListener(v -> toggleVpn());

        // 加载配置列表
        refreshProfileList();

        // 更新启动按钮状态
        updateStartButton();

    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新列表（从编辑页返回时）
        refreshProfileList();
        updateStartButton();
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
        super.onStop();
    }

    private void refreshProfileList() {
        List<Preferences.ProfileInfo> profiles = prefs.getProfileList();
        String selectedId = prefs.getCurrentProfileId();
        adapter.setProfiles(profiles, selectedId);
    }

    private void updateStartButton() {
        if (vpnStarting) {
            btnStart.setEnabled(false);
            btnStart.setText("启动中...");
            btnStart.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF9800));
            return;
        }
        btnStart.setEnabled(true);
        boolean isVpnRunning = prefs.getEnable();
        if (isVpnRunning) {
            btnStart.setText("停止");
            btnStart.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336)); // Red
        } else {
            btnStart.setText("启动");
            btnStart.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50)); // Green
        }
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
        if (vpnStarting) {
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
        updateStartButton();
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
            updateStartButton();
            Toast.makeText(this, "无法启动 VPN 服务: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopVpn() {
        mainHandler.removeCallbacks(vpnStartupTimeout);
        vpnStarting = false;
        prefs.setEnable(false);
        try {
            Intent intent = new Intent(this, TProxyService.class);
            startService(intent.setAction(TProxyService.ACTION_DISCONNECT));
        } catch (Exception e) {
            // 忽略服务停止异常
        }

        updateStartButton();
    }

    @Override
    public void onProfileClick(String profileId) {
        // 只在配置真正改变时才刷新列表
        String currentId = prefs.getCurrentProfileId();
        if (profileId == null || !profileId.equals(currentId)) {
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

        // X-Tunnel 配置：读取协议参数
        String xtToken = prefs.getXtToken();
        String xtRelayNodes = prefs.getXtRelayNodes();
        int xtConnections = prefs.getXtConnections();
        boolean xtEnableEch = prefs.getXtEnableEch();
        String xtEchDomain = prefs.getXtEchDomain();
        String xtDnsServer = prefs.getXtDnsServer();
        boolean xtInsecure = prefs.getXtInsecure();
        boolean xtEnableHotPair = prefs.getXtEnableHotPair();

        // 恢复原配置
        prefs.setCurrentProfileId(originalId);

        if (Preferences.PROTOCOL_X_TUNNEL.equals(protocolValue)) {
            // 构建 xtunnel:// URI：token/relay_nodes/connections/ech/domain/dns/insecure/hotpair
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
            if (!xtEnableEch) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("ech=0");
            }
            if (!xtEchDomain.isEmpty() && !xtEchDomain.equals("cloudflare-ech.com")) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("domain=").append(xtEchDomain);
            }
            if (!xtDnsServer.isEmpty() && !xtDnsServer.equals("https://doh.pub/dns-query")) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("dns=").append(xtDnsServer);
            }
            if (xtInsecure) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("insecure=1");
            }
            if (xtEnableHotPair) {
                if (xtQuery.length() > 0) xtQuery.append("&");
                xtQuery.append("hotpair=1");
            }
            String xtProtocol = "xtunnel://" + wssAddr;
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
            rest = protocol.substring(9); // "xtunnel://" 之后
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
        // 确保 wss:// 前缀
        if (!wssAddr.startsWith("wss://")) {
            wssAddr = "wss://" + wssAddr;
        }

        // 解析查询参数：ip= 优选中转节点，fip= 出口代理 IP，user_id= 用户标识。
        // 旧文档中的 dns/domain 参数被忽略，它们现在属于全局设置。
        String prefIp = "";
        String fallbackIp = "";
        String userId = "";
        boolean disableEch = false;
        String xtToken = "";
        String xtRelayNodes = "";
        int xtConnections = Preferences.DEFAULT_XT_CONNECTIONS;
        boolean xtEnableEch = true;
        String xtEchDomain = "";
        String xtDnsServer = "";
        boolean xtInsecure = false;
        boolean xtEnableHotPair = false;
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
                        case "token":
                        case "user_id":
                            userId = value;
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
                            xtEnableEch = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "domain":
                            xtEchDomain = value;
                            break;
                        case "dns":
                            xtDnsServer = value;
                            break;
                        case "insecure":
                            xtInsecure = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                            break;
                        case "hotpair":
                            xtEnableHotPair = value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
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
                xtToken, xtRelayNodes, xtConnections, xtEnableEch, xtEchDomain, xtDnsServer, xtInsecure, xtEnableHotPair);
    }

    private void showImportNameDialog(final String id, final String defaultName,
                                      final String protocol,
                                      final String wssAddr, final String prefIp,
                                      final String fallbackIp, final String userId,
                                      final boolean disableEch,
                                      final String xtToken, final String xtRelayNodes,
                                      final int xtConnections, final boolean xtEnableEch,
                                      final String xtEchDomain, final String xtDnsServer,
                                      final boolean xtInsecure, final boolean xtEnableHotPair) {
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
                    // 检查重复名称
                    if (prefs.profileNameExists(name, null)) {
                        Toast.makeText(this, "配置名称已存在", Toast.LENGTH_SHORT).show();
                        return;
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
                        prefs.setXtEnableEch(xtEnableEch);
                        prefs.setXtEchDomain(xtEchDomain);
                        prefs.setXtDnsServer(xtDnsServer);
                        prefs.setXtInsecure(xtInsecure);
                        prefs.setXtEnableHotPair(xtEnableHotPair);
                    } else {
                        prefs.setWorkerHost(wssAddr);
                        prefs.setPrefIp(prefIp);
                        if (!fallbackIp.isEmpty()) prefs.setFallbackIp(fallbackIp);
                        prefs.setUserID(userId);
                        prefs.setDisableEch(disableEch);
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
        // 设置 Toolbar 标题（APP 名称 + 版本号，版本号使用次级文本样式）
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String appName = getString(R.string.app_name);
            String version = packageInfo.versionName;

            // 使用 SpannableString 设置不同的文本样式
            android.text.SpannableString spannableString = new android.text.SpannableString(appName + "\n" + version);

            // 版本号使用较小的字号和较低的透明度
            int versionStart = appName.length() + 1; // 跳过 appName 和 \n
            int versionEnd = spannableString.length();
            float versionSize = 12f; // 较小的字号
            float versionAlpha = 0.6f; // 较低的透明度

            // 设置相对字体大小
            spannableString.setSpan(
                new android.text.style.RelativeSizeSpan(versionSize / 16f), // 16 是默认大小
                versionStart,
                versionEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            // 设置透明度
            int color = getResources().getColor(android.R.color.white);
            int alphaColor = (Math.round(versionAlpha * 255) << 24) | (color & 0x00FFFFFF);
            spannableString.setSpan(
                new android.text.style.ForegroundColorSpan(alphaColor),
                versionStart,
                versionEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            TextView titleView = new TextView(this);
            titleView.setText(spannableString);
            titleView.setTextSize(16);
            titleView.setTextColor(getResources().getColor(android.R.color.white));
            toolbar.addView(titleView);
        } catch (PackageManager.NameNotFoundException e) {
            // 降级处理：只显示 APP 名称
            TextView titleView = new TextView(this);
            titleView.setText(getString(R.string.app_name));
            titleView.setTextSize(16);
            titleView.setTextColor(getResources().getColor(android.R.color.white));
            toolbar.addView(titleView);
        }

        toolbar.inflateMenu(R.menu.menu_main);
        updateThemeIcon();
        toolbar.setOnMenuItemClickListener(item -> {
            int mode;
            if (item.getItemId() == R.id.theme_system) {
                mode = Preferences.THEME_SYSTEM;
            } else if (item.getItemId() == R.id.theme_light) {
                mode = Preferences.THEME_LIGHT;
            } else if (item.getItemId() == R.id.theme_dark) {
                mode = Preferences.THEME_DARK;
            } else {
                return false;
            }
            ThemeManager.setMode(this, mode);
            return true;
        });
    }

    private void updateThemeIcon() {
        int mode = prefs.getThemeMode();
        MenuItem themeItem = toolbar.getMenu().findItem(R.id.action_theme);
        if (mode == Preferences.THEME_LIGHT) {
            themeItem.setIcon(R.drawable.ic_light_mode);
        } else if (mode == Preferences.THEME_DARK) {
            themeItem.setIcon(R.drawable.ic_dark_mode);
        } else {
            themeItem.setIcon(R.drawable.ic_system_mode);
        }
    }
}
