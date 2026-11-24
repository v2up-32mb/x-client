/*
 ============================================================================
 Name        : UpdateManager.java
 Description : In-App Version Detection and Download Update System
 ============================================================================
 */

package com.x.client.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用版本检测、下载升级管理及存储清理服务。
 * 支持与 VPN SOCKS5 代理路由联动、架构专属 APK 匹配、安全断点/异常清理以及 TV D-pad 焦点适配。
 */
public class UpdateManager {

    private static final String GITHUB_REPO_API = "https://api.github.com/repos/v2up-32mb/x-client/releases/latest";
    private static final String DIR_NAME = "apk_updates";
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final Activity activity;
    private final Preferences prefs;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    private AlertDialog progressDialog;
    private LinearProgressIndicator progressIndicator;
    private TextView textPercent;
    private TextView textBytes;

    public static class ReleaseAsset {
        public final String name;
        public final long size;
        public final String downloadUrl;

        public ReleaseAsset(String name, long size, String downloadUrl) {
            this.name = name;
            this.size = size;
            this.downloadUrl = downloadUrl;
        }
    }

    public static class ReleaseInfo {
        public final String tagName;
        public final String name;
        public final String body;
        public final List<ReleaseAsset> assets;

        public ReleaseInfo(String tagName, String name, String body, List<ReleaseAsset> assets) {
            this.tagName = tagName;
            this.name = name;
            this.body = body;
            this.assets = assets;
        }
    }

    public UpdateManager(Activity activity) {
        this.activity = activity;
        this.prefs = new Preferences(activity);
    }

    /**
     * 打开支持 301/302 重定向的手动网络连接。
     * 当 VPN 运行中（prefs.getEnable() 为 true）时，使用未解析地址经过本地 SOCKS5 代理路由。
     */
    public static HttpURLConnection openConnectionWithRedirects(String initialUrl, Preferences prefs, String versionName) throws IOException {
        String currentUrl = initialUrl;
        int redirects = 0;

        while (redirects < MAX_REDIRECTS) {
            Proxy proxy = Proxy.NO_PROXY;
            if (prefs != null && prefs.getEnable()) {
                SocketAddress proxyAddress = InetSocketAddress.createUnresolved(
                        prefs.getSocksAddress(),
                        prefs.getSocksPort()
                );
                proxy = new Proxy(Proxy.Type.SOCKS, proxyAddress);
            }

            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "X-Client-Android/" + (versionName != null ? versionName : "1.0"));
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                    || responseCode == 307
                    || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("HTTP redirect missing Location header");
                }
                URL nextUrl = new URL(url, location);
                currentUrl = nextUrl.toExternalForm();
                redirects++;
            } else {
                return conn;
            }
        }
        throw new IOException("Too many redirects: " + redirects);
    }

    /**
     * 检查版本更新。
     *
     * @param manual 是否由用户主动点击触发（若为 true 则在无更新或失败时弹出 Toast 提示）
     */
    public void checkUpdate(boolean manual) {
        if (manual) {
            Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        }

        new Thread(() -> {
            try {
                PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                String currentVersion = pInfo.versionName;

                HttpURLConnection conn = openConnectionWithRedirects(GITHUB_REPO_API, prefs, currentVersion);
                int code = conn.getResponseCode();
                if (code == 403) {
                    conn.disconnect();
                    activity.runOnUiThread(() -> {
                        if (manual && !activity.isFinishing() && !activity.isDestroyed()) {
                            Toast.makeText(activity, R.string.update_rate_limit, Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect();
                    activity.runOnUiThread(() -> {
                        if (manual && !activity.isFinishing() && !activity.isDestroyed()) {
                            Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                String jsonStr;
                try (InputStream in = conn.getInputStream()) {
                    jsonStr = readStreamToString(in);
                } finally {
                    conn.disconnect();
                }

                JSONObject releaseJson = new JSONObject(jsonStr);
                String tagName = releaseJson.optString("tag_name", "");
                String releaseName = releaseJson.optString("name", tagName);
                String body = releaseJson.optString("body", "");
                JSONArray assetsArray = releaseJson.optJSONArray("assets");

                List<ReleaseAsset> assetList = new ArrayList<>();
                if (assetsArray != null) {
                    for (int i = 0; i < assetsArray.length(); i++) {
                        JSONObject a = assetsArray.getJSONObject(i);
                        String aName = a.optString("name", "");
                        long aSize = a.optLong("size", 0);
                        String aUrl = a.optString("browser_download_url", "");
                        assetList.add(new ReleaseAsset(aName, aSize, aUrl));
                    }
                }

                ReleaseInfo releaseInfo = new ReleaseInfo(tagName, releaseName, body, assetList);

                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;

                    if (!isNewerVersion(releaseInfo.tagName, currentVersion)) {
                        if (manual) {
                            Toast.makeText(activity, R.string.update_latest_already, Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }

                    ReleaseAsset matched = findBestMatchedAsset(releaseInfo.assets);
                    if (matched == null) {
                        if (manual) {
                            Toast.makeText(activity, R.string.update_no_matched_asset, Toast.LENGTH_LONG).show();
                        }
                        return;
                    }

                    showUpdateDialog(releaseInfo, matched);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (manual && !activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "UpdateCheckThread").start();
    }

    /**
     * 弹出版本更新详情对话框。
     */
    private void showUpdateDialog(ReleaseInfo releaseInfo, ReleaseAsset asset) {
        String sizeFormatted = Formatter.formatFileSize(activity, asset.size);
        String versionDetails = activity.getString(R.string.update_dialog_version_label, releaseInfo.tagName, sizeFormatted);

        StringBuilder sb = new StringBuilder();
        sb.append(versionDetails).append("\n\n");
        if (releaseInfo.body != null && !releaseInfo.body.trim().isEmpty()) {
            sb.append(releaseInfo.body.trim());
        } else {
            sb.append(activity.getString(R.string.update_no_release_notes));
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_dialog_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.update_btn_update_now, (d, which) -> startDownload(asset))
                .setNegativeButton(R.string.update_btn_later, null)
                .create();

        dialog.show();

        Button btnPositive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button btnNegative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (btnPositive != null) {
            btnPositive.setFocusable(true);
            TvFocusHelper.applyFocusFeedback(btnPositive);
            btnPositive.requestFocus();
        }
        if (btnNegative != null) {
            btnNegative.setFocusable(true);
            TvFocusHelper.applyFocusFeedback(btnNegative);
        }
    }

    /**
     * 启动安装包下载与进度弹窗。
     */
    private void startDownload(ReleaseAsset asset) {
        isCancelled.set(false);

        // 编程式构建 Material 3 下载进度弹窗
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(activity, 24);
        int padTop = dp(activity, 16);
        int padBottom = dp(activity, 8);
        container.setPadding(padH, padTop, padH, padBottom);

        progressIndicator = new LinearProgressIndicator(activity);
        progressIndicator.setIndeterminate(false);
        progressIndicator.setMax(100);
        progressIndicator.setProgress(0);
        container.addView(progressIndicator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout infoRow = new LinearLayout(activity);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpInfo.topMargin = dp(activity, 12);
        infoRow.setLayoutParams(lpInfo);

        textPercent = new TextView(activity);
        textPercent.setText("0%");
        textPercent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textPercent.setTextColor(MaterialColors.getColor(container, R.attr.xOnSurface, 0xFF000000));
        infoRow.addView(textPercent, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));

        textBytes = new TextView(activity);
        textBytes.setText("0 B / " + Formatter.formatFileSize(activity, asset.size));
        textBytes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textBytes.setTextColor(MaterialColors.getColor(container, R.attr.xOnSurfaceVariant, 0xFF666666));
        infoRow.addView(textBytes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        container.addView(infoRow);

        progressDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_downloading_title)
                .setView(container)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, which) -> isCancelled.set(true))
                .create();

        progressDialog.show();

        Button btnCancel = progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (btnCancel != null) {
            btnCancel.setFocusable(true);
            TvFocusHelper.applyFocusFeedback(btnCancel);
            btnCancel.requestFocus();
        }

        new Thread(() -> {
            File updateDir = activity.getExternalFilesDir(DIR_NAME);
            if (updateDir == null) {
                activity.runOnUiThread(() -> {
                    dismissProgressDialog();
                    Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            if (!updateDir.exists()) {
                updateDir.mkdirs();
            }

            // 存储卫生：下载前清理历史残留的 .download 和 .apk 文件
            File[] existing = updateDir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    if (f.getName().endsWith(".download") || f.getName().endsWith(".apk")) {
                        f.delete();
                    }
                }
            }

            File finalApk = new File(updateDir, asset.name);
            File tempFile = new File(updateDir, asset.name + ".download");

            boolean success = false;
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;

            try {
                PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                String versionName = pInfo.versionName;

                conn = openConnectionWithRedirects(asset.downloadUrl, prefs, versionName);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error code: " + code);
                }

                long totalBytes = asset.size > 0 ? asset.size : conn.getContentLengthLong();
                in = new BufferedInputStream(conn.getInputStream());
                out = new FileOutputStream(tempFile);

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int read;
                long lastUpdate = 0;

                while ((read = in.read(buffer)) != -1) {
                    if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 100 || downloaded == totalBytes) {
                        lastUpdate = now;
                        final long curDownloaded = downloaded;
                        final int prog = totalBytes > 0 ? (int) (curDownloaded * 100 / totalBytes) : 0;
                        activity.runOnUiThread(() -> updateDownloadProgress(prog, curDownloaded, totalBytes));
                    }
                }
                out.flush();

                // 校验实际下载字节数与 Release asset 大小一致
                if (asset.size > 0 && tempFile.length() != asset.size) {
                    throw new IOException("Size mismatch: expected " + asset.size + ", actual " + tempFile.length());
                }

                if (!tempFile.renameTo(finalApk)) {
                    throw new IOException("Failed to rename temporary file to APK");
                }

                success = true;
            } catch (Exception e) {
                // 失败或取消
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
                if (out != null) {
                    try { out.close(); } catch (Exception ignored) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
                // 存储卫生：用户取消、中断或异常时在 finally 块中立即删除 .download 临时文件
                if (!success && tempFile.exists()) {
                    tempFile.delete();
                }
            }

            final boolean finalSuccess = success;
            activity.runOnUiThread(() -> {
                dismissProgressDialog();
                if (finalSuccess) {
                    installApk(activity, finalApk);
                } else {
                    if (isCancelled.get()) {
                        Toast.makeText(activity, R.string.update_download_cancelled, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }, "UpdateDownloadThread").start();
    }

    private void updateDownloadProgress(int progress, long downloadedBytes, long totalBytes) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (progressIndicator != null) {
            progressIndicator.setProgress(progress);
        }
        if (textPercent != null) {
            textPercent.setText(progress + "%");
        }
        if (textBytes != null) {
            String downloadedStr = Formatter.formatFileSize(activity, downloadedBytes);
            String totalStr = Formatter.formatFileSize(activity, totalBytes);
            textBytes.setText(downloadedStr + " / " + totalStr);
        }
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 调用系统安装器安装 APK。
     */
    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) return;

        // Android 8.0+ 检查未知应用安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.update_install_permission_title)
                        .setMessage(R.string.update_install_permission_desc)
                        .setPositiveButton(R.string.update_install_permission_settings, (d, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName()));
                                activity.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return;
            }
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.update_install_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 冷启动异步清理：清理 apk_updates 目录下残留的 .download 文件及已安装或更旧版本的 APK。
     */
    public static void cleanupStaleApksAsync(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                File updateDir = appContext.getExternalFilesDir(DIR_NAME);
                if (updateDir == null || !updateDir.exists()) return;

                PackageManager pm = appContext.getPackageManager();
                PackageInfo currentInfo = pm.getPackageInfo(appContext.getPackageName(), 0);
                long currentVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? currentInfo.getLongVersionCode()
                        : currentInfo.versionCode;

                File[] files = updateDir.listFiles();
                if (files == null) return;

                for (File file : files) {
                    if (file.getName().endsWith(".download")) {
                        file.delete();
                    } else if (file.getName().endsWith(".apk")) {
                        PackageInfo archiveInfo = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
                        if (archiveInfo != null) {
                            long archiveVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                    ? archiveInfo.getLongVersionCode()
                                    : archiveInfo.versionCode;
                            if (archiveVersionCode <= currentVersionCode) {
                                file.delete();
                            }
                        } else {
                            // 损坏或无法解析的 APK 残留直接删除
                            file.delete();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "UpdateCleanupThread").start();
    }

    /**
     * 根据设备架构（Build.SUPPORTED_ABIS）从 Release 资产列表中匹配最佳 APK。
     * 优先级：设备 ABI 专属包（arm64-v8a, armeabi-v7a, x86_64, x86）-> universal 通用包 -> 列表第一个 .apk。
     */
    public static ReleaseAsset findBestMatchedAsset(List<ReleaseAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return null;
        }

        List<ReleaseAsset> apkAssets = new ArrayList<>();
        for (ReleaseAsset asset : assets) {
            if (asset.name != null && asset.name.toLowerCase().endsWith(".apk")) {
                apkAssets.add(asset);
            }
        }
        if (apkAssets.isEmpty()) {
            return null;
        }

        String[] supportedAbis = Build.SUPPORTED_ABIS;
        if (supportedAbis != null) {
            for (String abi : supportedAbis) {
                for (ReleaseAsset asset : apkAssets) {
                    if (matchesAbi(asset.name, abi)) {
                        return asset;
                    }
                }
            }
        }

        // 回落到 universal 通用包
        for (ReleaseAsset asset : apkAssets) {
            if (asset.name.toLowerCase().contains("universal")) {
                return asset;
            }
        }

        // 终极回落到第一个 .apk 资产
        return apkAssets.get(0);
    }

    public static boolean matchesAbi(String assetName, String abi) {
        if (assetName == null || abi == null) return false;
        String lowerName = assetName.toLowerCase();
        String lowerAbi = abi.toLowerCase();

        if ("x86".equals(lowerAbi)) {
            return (lowerName.contains("-x86-") || lowerName.contains("-x86.") || lowerName.contains("_x86_") || lowerName.contains("_x86."))
                    && !lowerName.contains("x86_64") && !lowerName.contains("x86-64");
        }
        return lowerName.contains(lowerAbi);
    }

    /**
     * 语义化版本号对比。
     *
     * @return 若 remoteTag 大于 currentVersion 则返回 true
     */
    public static boolean isNewerVersion(String remoteTag, String currentVersion) {
        if (remoteTag == null || currentVersion == null) return false;
        String cleanRemote = remoteTag.trim();
        if (cleanRemote.startsWith("v") || cleanRemote.startsWith("V")) {
            cleanRemote = cleanRemote.substring(1);
        }
        String cleanCurrent = currentVersion.trim();
        if (cleanCurrent.startsWith("v") || cleanCurrent.startsWith("V")) {
            cleanCurrent = cleanCurrent.substring(1);
        }

        String[] remoteParts = cleanRemote.split("-")[0].split("\\.");
        String[] currentParts = cleanCurrent.split("-")[0].split("\\.");

        int length = Math.max(remoteParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int r = 0;
            int c = 0;
            if (i < remoteParts.length) {
                try {
                    r = Integer.parseInt(remoteParts[i]);
                } catch (NumberFormatException ignored) {}
            }
            if (i < currentParts.length) {
                try {
                    c = Integer.parseInt(currentParts[i]);
                } catch (NumberFormatException ignored) {}
            }
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    private static String readStreamToString(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static int dp(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
