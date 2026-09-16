package com.x.client.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.DrawableRes;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.x.client.app.R;

/**
 * 空态视图：图标 + 标题 + 说明 + 可选主按钮。
 * 原则：空态是指引（research.md 原则 5），必须告诉用户下一步做什么。
 */
public class EmptyStateView extends androidx.appcompat.widget.LinearLayoutCompat {

    private final ImageView iconView;
    private final TextView titleView;
    private final TextView messageView;
    private final MaterialButton actionButton;

    public EmptyStateView(@NonNull Context context) {
        this(context, null);
    }

    public EmptyStateView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EmptyStateView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setGravity(CENTER);
        LayoutInflater.from(context).inflate(R.layout.view_empty_state, this, true);
        iconView = findViewById(R.id.empty_icon);
        titleView = findViewById(R.id.empty_title);
        messageView = findViewById(R.id.empty_message);
        actionButton = findViewById(R.id.empty_action);
    }

    public void setTitle(@Nullable CharSequence title) {
        titleView.setText(title);
        titleView.setVisibility(title == null ? GONE : VISIBLE);
    }

    public void setMessage(@Nullable CharSequence message) {
        messageView.setText(message);
        messageView.setVisibility(message == null ? GONE : VISIBLE);
    }

    /** 设置主行动按钮；text 为 null 时隐藏按钮。 */
    public void setAction(@Nullable CharSequence text, @Nullable OnClickListener listener) {
        if (text == null) {
            actionButton.setVisibility(GONE);
            actionButton.setOnClickListener(null);
            return;
        }
        actionButton.setText(text);
        actionButton.setVisibility(VISIBLE);
        actionButton.setOnClickListener(listener);
    }

    /** 替换图标（默认 ic_inbox），自动使用 onSurfaceVariant 着色。 */
    public void setIcon(@DrawableRes int drawableResId) {
        iconView.setImageResource(drawableResId);
        iconView.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(getContext(),
                                com.google.android.material.R.color.material_on_surface_emphasis_medium)));
    }
}
