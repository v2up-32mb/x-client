package com.x.client.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.x.client.app.R;

/**
 * 错误横幅：失败终态的容器（替代"只弹 Toast"反模式）。
 * 提供"重试"与"查看日志"两个恢复动作（research-ux B6 / Outline C5a）。
 */
public class ErrorBanner extends MaterialCardView {

    private final TextView messageView;
    private final MaterialButton retryButton;
    private final MaterialButton detailsButton;

    public ErrorBanner(@NonNull Context context) {
        this(context, null);
    }

    public ErrorBanner(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.materialCardViewStyle);
    }

    public ErrorBanner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_error_banner, this, true);
        setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(getContext(), R.color.x_error_container));
        messageView = findViewById(R.id.error_banner_message);
        retryButton = findViewById(R.id.error_banner_retry);
        detailsButton = findViewById(R.id.error_banner_details);
        int onContainer = androidx.core.content.ContextCompat.getColor(getContext(), R.color.x_on_error_container);
        messageView.setTextColor(onContainer);
        // TextButton 在 errorContainer 上需用 onErrorContainer 前景保证对比度
        retryButton.setTextColor(ColorStateList.valueOf(onContainer));
        detailsButton.setTextColor(ColorStateList.valueOf(onContainer));
        retryButton.setRippleColor(ColorStateList.valueOf(onContainer));
    }

    public void setMessage(@Nullable CharSequence message) {
        messageView.setText(message);
    }

    public void setOnRetryListener(@Nullable OnClickListener listener) {
        retryButton.setOnClickListener(listener);
        retryButton.setVisibility(listener == null ? GONE : VISIBLE);
    }

    public void setOnDetailsListener(@Nullable OnClickListener listener) {
        detailsButton.setOnClickListener(listener);
        detailsButton.setVisibility(listener == null ? GONE : VISIBLE);
    }
}
