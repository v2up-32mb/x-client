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
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class RuntimeLogActivity extends AppCompatActivity {
    private static final long REQUEST_TIMEOUT_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView textLogs;
    private TextView textStatus;
    private ScrollView scrollLogs;
    private Button btnCopy;
    private boolean receiverRegistered;
    private boolean responseReceived;
    private String currentLogs = "";

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TProxyService.ACTION_RUNTIME_LOGS.equals(intent.getAction())) {
                return;
            }
            responseReceived = true;
            renderLogs(intent.getStringExtra(TProxyService.EXTRA_RUNTIME_LOGS));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_runtime_log);

        ImageButton btnBack = findViewById(R.id.btn_log_back);
        Button btnRefresh = findViewById(R.id.btn_log_refresh);
        btnCopy = findViewById(R.id.btn_log_copy);
        textLogs = findViewById(R.id.text_runtime_logs);
        textStatus = findViewById(R.id.text_log_status);
        scrollLogs = findViewById(R.id.scroll_runtime_logs);

        btnBack.setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> requestLogs());
        btnCopy.setOnClickListener(v -> copyLogs());
        btnCopy.setEnabled(false);
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
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(logReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void requestLogs() {
        responseReceived = false;
        textStatus.setText(R.string.runtime_logs_loading);

        Intent request = new Intent(this, TProxyService.class);
        request.setAction(TProxyService.ACTION_REQUEST_RUNTIME_LOGS);
        try {
            startService(request);
        } catch (RuntimeException error) {
            renderLogs("");
            textStatus.setText(R.string.runtime_logs_unavailable);
            return;
        }

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (!responseReceived) {
                renderLogs("");
                textStatus.setText(R.string.runtime_logs_unavailable);
            }
        }, REQUEST_TIMEOUT_MS);
    }

    private void renderLogs(String logs) {
        currentLogs = logs == null ? "" : logs.trim();
        if (currentLogs.isEmpty()) {
            textLogs.setText(R.string.runtime_logs_empty);
            textStatus.setText(R.string.runtime_logs_empty);
            btnCopy.setEnabled(false);
            return;
        }

        textLogs.setText(currentLogs);
        textStatus.setText(getString(R.string.runtime_logs_line_count, countLines(currentLogs)));
        btnCopy.setEnabled(true);
        scrollLogs.post(() -> scrollLogs.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int countLines(String value) {
        int count = 1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private void copyLogs() {
        if (currentLogs.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.runtime_logs_title), currentLogs));
        Toast.makeText(this, R.string.runtime_logs_copied, Toast.LENGTH_SHORT).show();
    }
}
