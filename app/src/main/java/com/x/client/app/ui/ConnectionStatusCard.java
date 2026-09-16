package com.x.client.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.x.client.app.R;

/**
 * 连接状态卡片（主页主操作，唯一）。
 *
 * 四态视觉语言（全 App 唯一依据，见 docs/ui-ux/redesign-plan.md §6.5）：
 * DISCONNECTED = 中性 surfaceContainer；CONNECTING = primaryContainer + 进度；
 * CONNECTED = successContainer（绿=已连接的用户心智）；ERROR = errorContainer。
 *
 * 点击行为由宿主（ProfileListActivity）决定：连接/断开切换。
 */
public class ConnectionStatusCard extends MaterialCardView {

    /** 连接四态（颜色语义的唯一枚举来源）。 */
    public enum State {
        /** 已断开：中性色，图标熄灭（描边语义）。 */
        DISCONNECTED,
        /** 连接中：品牌蓝容器色 + 进度指示。 */
        CONNECTING,
        /** 已连接：语义成功绿。 */
        CONNECTED,
        /** 错误：语义错误红。 */
        ERROR
    }

    private final ImageView iconView;
    private final ProgressBar progressView;
    private final TextView titleView;
    private final TextView subtitleView;

    public ConnectionStatusCard(@NonNull Context context) {
        this(context, null);
    }

    public ConnectionStatusCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.materialCardViewStyle);
    }

    public ConnectionStatusCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_connection_status_card, this, true);
        setClickable(true);
        setFocusable(true);
        iconView = findViewById(R.id.status_icon);
        progressView = findViewById(R.id.status_progress);
        titleView = findViewById(R.id.status_title);
        subtitleView = findViewById(R.id.status_subtitle);
        setState(State.DISCONNECTED,
                context.getString(R.string.status_disconnected),
                context.getString(R.string.status_tap_to_connect));
    }

    /**
     * 设置状态、标题与副文案。
     *
     * @param state    连接四态之一
     * @param title    状态动词化文案（如"已连接/正在连接…"）
     * @param subtitle 上下文副文案（如当前 Profile 名 + 服务器地址）
     */
    public void setState(@NonNull State state, @NonNull CharSequence title, @Nullable CharSequence subtitle) {
        int cardColor;
        int titleColor;
        int subtitleColor;
        int iconTint;

        switch (state) {
            case CONNECTING:
                cardColor = attr(R.attr.colorPrimaryContainer);
                titleColor = attr(R.attr.colorOnPrimaryContainer);
                subtitleColor = attr(R.attr.colorOnPrimaryContainer);
                iconTint = attr(R.attr.colorPrimary);
                iconView.setImageResource(R.drawable.ic_power);
                iconView.setVisibility(VISIBLE);
                progressView.setVisibility(VISIBLE);
                progressView.setIndeterminateTintList(
                        ColorStateList.valueOf(attr(R.attr.colorPrimary)));
                break;
            case CONNECTED:
                cardColor = color(R.color.md_success_container);
                titleColor = color(R.color.md_on_success_container);
                subtitleColor = color(R.color.md_on_success_container);
                iconTint = color(R.color.md_success);
                iconView.setImageResource(R.drawable.ic_check_circle);
                iconView.setVisibility(VISIBLE);
                progressView.setVisibility(GONE);
                break;
            case ERROR:
                cardColor = attr(R.attr.colorErrorContainer);
                titleColor = attr(R.attr.colorOnErrorContainer);
                subtitleColor = attr(R.attr.colorOnErrorContainer);
                iconTint = attr(R.attr.colorOnErrorContainer);
                iconView.setImageResource(R.drawable.ic_error);
                iconView.setVisibility(VISIBLE);
                progressView.setVisibility(GONE);
                break;
            case DISCONNECTED:
            default:
                cardColor = attr(R.attr.colorSurfaceContainer);
                titleColor = attr(R.attr.colorOnSurface);
                subtitleColor = attr(R.attr.colorOnSurfaceVariant);
                iconTint = attr(R.attr.colorOnSurfaceVariant);
                iconView.setImageResource(R.drawable.ic_power);
                iconView.setVisibility(VISIBLE);
                progressView.setVisibility(GONE);
                break;
        }

        setCardBackgroundColor(cardColor);
        titleView.setTextColor(titleColor);
        subtitleView.setTextColor(subtitleColor);
        iconView.setImageTintList(ColorStateList.valueOf(iconTint));
        titleView.setText(title);
        subtitleView.setText(subtitle);
    }

    private int attr(int attrResId) {
        return MaterialColors.getColor(this, attrResId);
    }

    /** 自定义语义色（md_*）直接引用颜色资源，night 变体由 values-night/colors.xml 承接。 */
    private int color(int colorResId) {
        return ContextCompat.getColor(getContext(), colorResId);
    }

    /** 供宿主更新副文案而不改变状态（如切换当前 Profile）。 */
    public void setSubtitle(@Nullable CharSequence subtitle) {
        subtitleView.setText(subtitle);
    }
}
