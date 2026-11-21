package com.x.client.app;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeLogActivity extends BaseActivity {
    private static final long REQUEST_TIMEOUT_MS = 2_000L;

    private enum LogFilterMode {
        ALL,
        WARN_ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderVersion = new AtomicInteger(0);

    private MaterialToolbar toolbar;
    private MaterialButton btnRefresh;
    private MaterialButton btnCopy;
    private MaterialButton btnClear;
    private ChipGroup chipGroupFilter;
    private Chip chipFilterAll;
    private Chip chipFilterWarnError;
    private MaterialCardView cardLogContainer;
    private ScrollView scrollLogs;
    private TextView textLogs;

    private boolean receiverRegistered;
    private boolean isLoading;
    private Runnable timeoutRunnable;
    private String rawLogs = "";
    private LogFilterMode filterMode = LogFilterMode.ALL;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TProxyService.ACTION_RUNTIME_LOGS.equals(intent.getAction())) {
                return;
            }
            onLogsReceived(intent.getStringExtra(TProxyService.EXTRA_RUNTIME_LOGS));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_runtime_log);

        if (DeviceUtils.isTv(this)) {
            int safeH = (int) (32 * getResources().getDisplayMetrics().density);
            int safeV = (int) (20 * getResources().getDisplayMetrics().density);
            findViewById(android.R.id.content).setPadding(safeH, safeV, safeH, safeV);
        }

        toolbar = findViewById(R.id.toolbar);
        btnRefresh = findViewById(R.id.btn_log_refresh);
        btnCopy = findViewById(R.id.btn_log_copy);
        btnClear = findViewById(R.id.btn_log_clear);
        chipGroupFilter = findViewById(R.id.chip_group_filter);
        chipFilterAll = findViewById(R.id.chip_filter_all);
        chipFilterWarnError = findViewById(R.id.chip_filter_warn_error);
        cardLogContainer = findViewById(R.id.card_log_container);
        scrollLogs = findViewById(R.id.scroll_runtime_logs);
        textLogs = findViewById(R.id.text_runtime_logs);

        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(R.string.runtime_logs_title);
        toolbar.setSubtitle(R.string.runtime_logs_loading);

        btnRefresh.setOnClickListener(v -> requestLogs());
        btnCopy.setOnClickListener(v -> copyLogs());
        btnClear.setOnClickListener(v -> clearLogs());

        btnCopy.setEnabled(false);
        btnClear.setEnabled(false);

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean warnOnly = checkedIds.contains(R.id.chip_filter_warn_error);
            filterMode = warnOnly ? LogFilterMode.WARN_ERROR : LogFilterMode.ALL;
            applyFilterAndRender(false);
        });

        setupTvNavigation();
    }

    private void setupTvNavigation() {
        if (!DeviceUtils.isTv(this)) {
            return;
        }

        textLogs.setTextIsSelectable(false);
        textLogs.setFocusable(false);
        scrollLogs.setFocusable(true);

        int outlineVariant = MaterialColors.getColor(this, R.attr.xOutlineVariant, 0xFF737373);
        int primary = MaterialColors.getColor(this, R.attr.xPrimary, 0xFF2563EB);
        int strokeWidthFocused = (int) (2 * getResources().getDisplayMetrics().density);
        int strokeWidthNormal = (int) (1 * getResources().getDisplayMetrics().density);

        scrollLogs.setOnFocusChangeListener((v, hasFocus) -> {
            cardLogContainer.setStrokeColor(hasFocus ? primary : outlineVariant);
            cardLogContainer.setStrokeWidth(hasFocus ? strokeWidthFocused : strokeWidthNormal);
        });

        int scrollStep = (int) (120 * getResources().getDisplayMetrics().density);
        scrollLogs.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                    if (scrollLogs.getScrollY() <= 0) {
                        View target = chipFilterWarnError.isChecked() ? chipFilterWarnError : chipFilterAll;
                        target.requestFocus();
                        return true;
                    }
                    scrollLogs.smoothScrollBy(0, -scrollStep);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                    View child = scrollLogs.getChildAt(0);
                    if (child != null) {
                        int max = child.getBottom() - (scrollLogs.getHeight() + scrollLogs.getScrollY());
                        if (max > 0) {
                            scrollLogs.smoothScrollBy(0, Math.min(scrollStep, max));
                            return true;
                        }
                    }
                }
            }
            return false;
        });

        TvFocusHelper.applyFocusFeedback(btnRefresh, btnCopy, btnClear, chipFilterAll, chipFilterWarnError);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    logReceiver,
                    new IntentFilter(TProxyService.ACTION_RUNTIME_LOGS),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            receiverRegistered = true;
        }
        requestLogs();
    }

    @Override
    protected void onStop() {
        isLoading = false;
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(logReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        renderVersion.incrementAndGet();
        parseExecutor.shutdownNow();
        super.onDestroy();
    }

    private void requestLogs() {
        if (isLoading) {
            return;
        }
        isLoading = true;
        toolbar.setSubtitle(R.string.runtime_logs_loading);

        Intent request = new Intent(this, TProxyService.class);
        request.setAction(TProxyService.ACTION_REQUEST_RUNTIME_LOGS);
        try {
            startService(request);
        } catch (RuntimeException error) {
            handleLoadFailure();
            return;
        }

        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = () -> {
            if (isLoading) {
                handleLoadFailure();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS);
    }

    private void onLogsReceived(String logs) {
        isLoading = false;
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        this.rawLogs = logs == null ? "" : logs.trim();
        applyFilterAndRender(true);
    }

    private void handleLoadFailure() {
        isLoading = false;
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (rawLogs.isEmpty()) {
            renderEmptyState();
            toolbar.setSubtitle(R.string.runtime_logs_unavailable);
        } else {
            toolbar.setSubtitle(R.string.runtime_logs_unavailable);
            Toast.makeText(this, R.string.runtime_logs_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogs() {
        this.rawLogs = "";
        renderVersion.incrementAndGet();
        textLogs.setText(R.string.runtime_logs_empty);
        toolbar.setSubtitle(R.string.runtime_logs_cleared);
        btnCopy.setEnabled(false);
        btnClear.setEnabled(false);
        Toast.makeText(this, R.string.runtime_logs_cleared, Toast.LENGTH_SHORT).show();
    }

    private void copyLogs() {
        CharSequence text = textLogs.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.runtime_logs_title), text.toString()));
            Toast.makeText(this, R.string.runtime_logs_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderEmptyState() {
        textLogs.setText(R.string.runtime_logs_empty);
        toolbar.setSubtitle(R.string.runtime_logs_empty);
        btnCopy.setEnabled(false);
        btnClear.setEnabled(false);
    }

    private void applyFilterAndRender(boolean autoScrollToBottom) {
        final int version = renderVersion.incrementAndGet();
        final String sourceLogs = this.rawLogs;
        final LogFilterMode mode = this.filterMode;

        if (sourceLogs == null || sourceLogs.trim().isEmpty()) {
            renderEmptyState();
            return;
        }

        final int errorColor = MaterialColors.getColor(this, R.attr.xError, 0xFFDC2626);
        final int warnColor = MaterialColors.getColor(this, R.attr.mdWarning, 0xFFD97706);

        parseExecutor.execute(() -> {
            if (version != renderVersion.get()) {
                return;
            }

            SpannableStringBuilder ssb = new SpannableStringBuilder();
            int totalLineCount = 0;
            int matchedLineCount = 0;

            int len = sourceLogs.length();
            int lineStart = 0;

            while (lineStart < len) {
                if (version != renderVersion.get()) {
                    return;
                }

                int lineEnd = sourceLogs.indexOf('\n', lineStart);
                if (lineEnd == -1) {
                    lineEnd = len;
                }

                String line = sourceLogs.substring(lineStart, lineEnd);
                totalLineCount++;

                boolean isErr = isErrorLine(line);
                boolean isWrn = !isErr && isWarnLine(line);

                if (mode == LogFilterMode.ALL || isErr || isWrn) {
                    matchedLineCount++;
                    int appendStart = ssb.length();
                    ssb.append(line).append('\n');

                    if (isErr) {
                        ssb.setSpan(new ForegroundColorSpan(errorColor), appendStart, ssb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (isWrn) {
                        ssb.setSpan(new ForegroundColorSpan(warnColor), appendStart, ssb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }

                lineStart = lineEnd + 1;
            }

            final int finalTotal = totalLineCount;
            final int finalMatched = matchedLineCount;
            final CharSequence finalSpannable = ssb;

            mainHandler.post(() -> {
                if (version != renderVersion.get() || isFinishing() || isDestroyed()) {
                    return;
                }
                displayRenderResult(finalSpannable, finalTotal, finalMatched, autoScrollToBottom);
            });
        });
    }

    private void displayRenderResult(CharSequence formattedText, int totalLines, int matchedLines, boolean autoScroll) {
        btnClear.setEnabled(totalLines > 0);

        if (matchedLines == 0) {
            if (filterMode == LogFilterMode.WARN_ERROR) {
                textLogs.setText(R.string.runtime_logs_empty_filter);
                toolbar.setSubtitle(getString(R.string.runtime_logs_filtered_line_count, 0, totalLines));
            } else {
                textLogs.setText(R.string.runtime_logs_empty);
                toolbar.setSubtitle(R.string.runtime_logs_empty);
            }
            btnCopy.setEnabled(false);
        } else {
            textLogs.setText(formattedText);
            btnCopy.setEnabled(true);
            if (filterMode == LogFilterMode.ALL) {
                toolbar.setSubtitle(getString(R.string.runtime_logs_ready_line_count, totalLines));
            } else {
                toolbar.setSubtitle(getString(R.string.runtime_logs_filtered_line_count, matchedLines, totalLines));
            }
        }

        if (autoScroll && matchedLines > 0) {
            scrollLogs.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    scrollLogs.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        } else {
            scrollLogs.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    scrollLogs.scrollTo(0, 0);
                }
            });
        }
    }

    private static boolean isErrorLine(String line) {
        return line.contains("[E]")
                || line.contains("[ERROR]")
                || line.contains("ERROR")
                || line.contains("Exception")
                || line.contains("Fatal")
                || line.contains("失败")
                || line.contains("错误")
                || line.contains("意外停止");
    }

    private static boolean isWarnLine(String line) {
        return line.contains("[W]")
                || line.contains("[WARN]")
                || line.contains("WARN")
                || line.contains("WARNING")
                || line.contains("警告")
                || line.contains("超时");
    }
}
