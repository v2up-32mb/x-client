/*
 ============================================================================
 Name        : EmptyStateView.java
 Author      : Antigravity
 Description : Material 3 Enhanced Empty State View with Direct Actions
 ============================================================================
 */

package com.x.client.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;

import com.google.android.material.button.MaterialButton;
import com.x.client.app.R;

public class EmptyStateView extends LinearLayoutCompat {

    public interface OnEmptyStateActionListener {
        void onImportFromClipboard();
        void onScanQrCode();
        void onAddManual();
    }

    private final ImageView iconView;
    private final TextView titleView;
    private final TextView messageView;
    private final MaterialButton btnClipboard;
    private final MaterialButton btnScanQr;
    private final MaterialButton btnAddManual;
    private final MaterialButton actionButton;

    private OnEmptyStateActionListener actionListener;

    public EmptyStateView(@NonNull Context context) {
        this(context, null);
    }

    public EmptyStateView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EmptyStateView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setGravity(android.view.Gravity.CENTER);
        LayoutInflater.from(context).inflate(R.layout.view_empty_state, this, true);

        iconView = findViewById(R.id.empty_icon);
        titleView = findViewById(R.id.empty_title);
        messageView = findViewById(R.id.empty_message);
        btnClipboard = findViewById(R.id.btn_empty_clipboard);
        btnScanQr = findViewById(R.id.btn_empty_scan_qr);
        btnAddManual = findViewById(R.id.btn_empty_add_manual);
        actionButton = findViewById(R.id.empty_action);

        if (btnClipboard != null) {
            btnClipboard.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onImportFromClipboard();
            });
        }
        if (btnScanQr != null) {
            btnScanQr.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onScanQrCode();
            });
        }
        if (btnAddManual != null) {
            btnAddManual.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onAddManual();
            });
        }
    }

    public void setOnEmptyStateActionListener(OnEmptyStateActionListener listener) {
        this.actionListener = listener;
    }

    public void setTitle(@Nullable CharSequence title) {
        if (titleView != null) {
            titleView.setText(title);
            titleView.setVisibility(title == null ? GONE : VISIBLE);
        }
    }

    public void setMessage(@Nullable CharSequence message) {
        if (messageView != null) {
            messageView.setText(message);
            messageView.setVisibility(message == null ? GONE : VISIBLE);
        }
    }

    public void setAction(@Nullable CharSequence text, @Nullable OnClickListener listener) {
        if (actionButton != null) {
            if (text == null) {
                actionButton.setVisibility(GONE);
                actionButton.setOnClickListener(null);
            } else {
                actionButton.setText(text);
                actionButton.setVisibility(VISIBLE);
                actionButton.setOnClickListener(listener);
            }
        }
    }
}
