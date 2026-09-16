package com.x.client.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.x.client.app.R;

/**
 * VPN Connection FAB：compact（56×56）↔ expanded（morph 状态卡）双形态核心控制。
 *
 * 交互（docs/ui-ux/refine-ux-review.md §1）：
 * - tap = 连接/断开主操作（CONNECTING/DISCONNECTING 中吞掉，展开态中仅折叠）
 * - 长按 = 展开信息卡（TalkBack：带标签辅助动作"查看连接信息"）
 * - 状态变化自动 morph 展开；终态停留 success 2s / error 3.5s 后自动 collapse
 *
 * 动画：ValueAnimator 宽 56→≤300dp、高 56→minHeight 76dp（wrap_content 适配字体缩放）、
 * 圆角 28→16dp；reduced-motion（ANIMATOR_DURATION_SCALE=0）直接跳切。
 * 状态代际计数器（generation）作废滞留回调，防止慢回调打在新状态上（review §7.2）。
 */
public class ConnectionFab extends MaterialCardView {

    /** 连接五态（UI 视觉状态；业务真相仍是 TProxyService 广播 + Preferences）。 */
    public enum State {
        /** 已断开：中性 surfaceContainer + 电源图标。 */
        DISCONNECTED,
        /** 连接中：primaryContainer + 进度（无倒计时，等终态原位换装）。 */
        CONNECTING,
        /** 已连接：successContainer + ✓，success 绿心智。 */
        CONNECTED,
        /** 断开中：中性 + 进度（无 STOPPING 广播，UI 乐观态，3s 兜底由宿主负责）。 */
        DISCONNECTING,
        /** 错误：errorContainer + !；compact 态常驻直至用户操作。 */
        ERROR
    }

    /** 主操作回调（tap：连接/断开切换）。 */
    public interface OnToggleListener {
        void onToggle();
    }

    /** 展开错误卡内"重试"按钮回调。 */
    public interface OnRetryListener {
        void onRetry();
    }

    /** 展开错误卡内"查看日志"按钮回调。 */
    public interface OnDetailsListener {
        void onDetails();
    }

    private static final long EXPAND_DURATION_MS = 250;
    private static final long COLLAPSE_DURATION_MS = 220;
    private static final long CONTENT_ENTER_DELAY_MS = 80;
    private static final long CONTENT_ENTER_DURATION_MS = 150;
    private static final long CONTENT_FADE_OUT_MS = 120;
    private static final long DWELL_SUCCESS_MS = 2000;
    private static final long DWELL_ERROR_MS = 3500;
    private static final long DWELL_DISCONNECTED_MS = 1500;

    private final ImageView iconView;
    private final CircularProgressIndicator progressView;
    private final LinearLayout textBlock;
    private final TextView titleView;
    private final TextView subtitleView;
    private final LinearLayout errorActions;

    private State state = State.DISCONNECTED;
    private boolean expanded = false;
    private int generation = 0;
    private final boolean animationsDisabled;
    private int currentBg;

    private OnToggleListener toggleListener;
    private OnRetryListener retryListener;
    private OnDetailsListener detailsListener;

    public ConnectionFab(@NonNull Context context) {
        this(context, null);
    }

    public ConnectionFab(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.materialCardViewStyle);
    }

    public ConnectionFab(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_connection_fab, this, true);
        iconView = findViewById(R.id.fab_icon);
        progressView = findViewById(R.id.fab_progress);
        textBlock = findViewById(R.id.fab_text_block);
        titleView = findViewById(R.id.fab_title);
        subtitleView = findViewById(R.id.fab_subtitle);
        errorActions = findViewById(R.id.fab_error_actions);

        animationsDisabled = readAnimatorScale() == 0f;

        // 与 Add FAB 视觉一致：compact 恒主色 container、56dp、elevation 一致
        currentBg = attr(com.google.android.material.R.attr.colorPrimaryContainer);
        setCardBackgroundColor(currentBg);
        setRadius(dp(28));
        setCardElevation(dp(6));
        setClickable(true);
        setFocusable(true);
        setLongClickable(true);

        setOnClickListener(v -> onTapped());
        setOnLongClickListener(v -> {
            if (!expanded) {
                expand();
            }
            return true;
        });
        setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (!expanded) {
                    // 长按动作带标签，TalkBack 动作菜单可直接读出用途（review §1）
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_LONG_CLICK,
                            context.getString(R.string.acc_view_connection_info)));
                }
            }
        });

        MaterialButton retry = findViewById(R.id.fab_retry);
        MaterialButton details = findViewById(R.id.fab_details);
        retry.setOnClickListener(v -> {
            collapse();
            if (retryListener != null) retryListener.onRetry();
        });
        details.setOnClickListener(v -> {
            if (detailsListener != null) detailsListener.onDetails();
        });
    }

    // ================================ 对外 API ================================

    public void setOnToggleListener(@Nullable OnToggleListener listener) {
        toggleListener = listener;
    }

    public void setOnRetryListener(@Nullable OnRetryListener listener) {
        retryListener = listener;
    }

    public void setOnDetailsListener(@Nullable OnDetailsListener listener) {
        detailsListener = listener;
    }

    public State getState() {
        return state;
    }

    /**
     * 状态统一入口：视觉换装 + 文案更新 + morph 语义。
     * - 进行中态（CONNECTING/DISCONNECTING）进入时自动展开，不设倒计时（review §2.1）；
     * - 展开中进入终态：原位换装 + 按终态停留后自动折叠；
     * - 未展开进入终态：仅换 compact 态（ERROR 常驻，不超时回落，review §7.1）；
     * - 状态不变时为 no-op（不打断进行中的 dwell）。
     */
    public void setState(@NonNull State newState, @Nullable CharSequence title,
                         @Nullable CharSequence subtitle) {
        boolean wasExpanded = expanded;
        boolean stateChanged = (newState != this.state);
        if (!stateChanged && !wasExpanded) {
            // 纯被动同步且无变化：避免 gen++ 打断进行中的停留计时
            return;
        }
        if (stateChanged) {
            generation++;
        }
        state = newState;
        applyVisuals(newState);
        titleView.setText(title);
        subtitleView.setText(subtitle);
        errorActions.setVisibility(newState == State.ERROR ? VISIBLE : GONE);

        if (newState == State.CONNECTING || newState == State.DISCONNECTING) {
            if (!wasExpanded) {
                expand();
            }
        } else if (wasExpanded && stateChanged) {
            scheduleCollapse(dwellFor(newState));
        }
    }

    // ================================ 点击/长按 ================================

    private void onTapped() {
        if (expanded) {
            // 展开态 tap 仅折叠（错误卡内重试走专用按钮，避免误触重连，review §7.1）
            collapse();
            return;
        }
        if (state == State.CONNECTING || state == State.DISCONNECTING) {
            // 进行中吞掉 tap（review §7.3：断开方向防抖 + 与服务清理竞态）
            return;
        }
        if (toggleListener != null) {
            toggleListener.onToggle();
        }
    }

    // ================================ morph 动画 ================================

    private void expand() {
        if (expanded) {
            return;
        }
        expanded = true;
        generation++;
        final int gen = generation;

        int targetWidth = Math.min(dp(300),
                getResources().getDisplayMetrics().widthPixels - dp(32));
        // 图标在行尾（贴住原 FAB 位置），文字块在行首；向左展开时文字可用宽扣除图标+间距
        int textAvail = targetWidth - dp(16 + 24 + 12 + 16);
        textBlock.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(Math.max(dp(80), textAvail),
                        android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED));
        int targetHeight = Math.max(dp(76), textBlock.getMeasuredHeight() + dp(24));
        int startW = dp(56), startH = dp(56);

        textBlock.setVisibility(VISIBLE);
        textBlock.animate().cancel();

        if (animationsDisabled) {
            getLayoutParams().width = targetWidth;
            getLayoutParams().height = targetHeight;
            setRadius(dp(16));
            setCardBackgroundColor(bgColorFor(state, true));
            currentBg = bgColorFor(state, true);
            textBlock.setAlpha(1f);
            textBlock.setTranslationX(0);
            requestLayout();
            return;
        }

        animateBgTo(bgColorFor(state, true));

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration((long) (EXPAND_DURATION_MS * animatorScale()));
        anim.setInterpolator(new FastOutSlowInInterpolator());
        anim.addUpdateListener(a -> {
            if (gen != generation || !isAttachedToWindow()) {
                a.cancel();
                return;
            }
            float t = a.getAnimatedFraction();
            getLayoutParams().width = (int) (startW + (targetWidth - startW) * t);
            getLayoutParams().height = (int) (startH + (targetHeight - startH) * t);
            setRadius(dp(28) + (dp(16) - dp(28)) * t);
            requestLayout();
        });
        anim.start();

        // 右锥定下向左展开：内容从图标侧（右侧）被推入新空间，LIT 与 RTL 镜像
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        textBlock.setAlpha(0f);
        textBlock.setTranslationX(rtl ? -dp(12) : dp(12));
        textBlock.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay((long) (CONTENT_ENTER_DELAY_MS * animatorScale()))
                .setDuration((long) (CONTENT_ENTER_DURATION_MS * animatorScale()))
                .start();
    }

    /** 收起展开卡（列表拖动滚动等外部折叠触发点，review §2.2）。 */
    public void collapse() {
        if (!expanded) {
            return;
        }
        expanded = false;
        generation++;
        final int gen = generation;

        textBlock.animate().cancel();
        if (animationsDisabled) {
            resetCompact();
            return;
        }

        int startW = Math.max(getWidth(), dp(56));
        int startH = Math.max(getHeight(), dp(56));
        float startRadius = getRadius();
        animateBgTo(bgColorFor(state, false));
        textBlock.animate()
                .alpha(0f)
                .setDuration((long) (CONTENT_FADE_OUT_MS * animatorScale()))
                .start();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration((long) (COLLAPSE_DURATION_MS * animatorScale()));
        anim.setInterpolator(new FastOutSlowInInterpolator());
        anim.addUpdateListener(a -> {
            if (gen != generation || !isAttachedToWindow()) {
                a.cancel();
                return;
            }
            float t = a.getAnimatedFraction();
            getLayoutParams().width = (int) (startW + (dp(56) - startW) * t);
            getLayoutParams().height = (int) (startH + (dp(56) - startH) * t);
            setRadius(startRadius + (dp(28) - startRadius) * t);
            requestLayout();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (gen == generation) {
                    resetCompact();
                }
            }
        });
        anim.start();
    }

    private void resetCompact() {
        textBlock.setVisibility(GONE);
        textBlock.setAlpha(1f);
        textBlock.setTranslationX(0);
        getLayoutParams().width = dp(56);
        getLayoutParams().height = dp(56);
        setRadius(dp(28));
        setCardBackgroundColor(bgColorFor(state, false));
        currentBg = bgColorFor(state, false);
        requestLayout();
    }

    private void scheduleCollapse(long delay) {
        if (isTouchExploration()) {
            // TalkBack 触摸探索下冻结自动折叠，避免内容未被读完即消失（review §6）
            return;
        }
        final int gen = generation;
        postDelayed(() -> {
            if (gen == generation && expanded && isAttachedToWindow()) {
                collapse();
            }
        }, delay);
    }

    private long dwellFor(State s) {
        if (s == State.CONNECTED) {
            return DWELL_SUCCESS_MS;
        }
        if (s == State.ERROR) {
            return DWELL_ERROR_MS;
        }
        return DWELL_DISCONNECTED_MS;
    }

    // ================================ 视觉映射 ================================

    /** 背景色：compact 恒为主色 container（与 Add FAB 一致，用户反馈修正）；展开卡按状态语义着色。 */
    private int bgColorFor(State s, boolean isExpanded) {
        if (!isExpanded) {
            return attr(com.google.android.material.R.attr.colorPrimaryContainer);
        }
        switch (s) {
            case CONNECTED:
                return color(R.color.md_success_container);
            case ERROR:
                return attr(com.google.android.material.R.attr.colorErrorContainer);
            case DISCONNECTED:
                return attr(com.google.android.material.R.attr.colorSurfaceContainer);
            default: // CONNECTING / DISCONNECTING
                return attr(com.google.android.material.R.attr.colorPrimaryContainer);
        }
    }

    /** 图标 tint：compact 藏蓝 container 前景系；展开卡按状态前景系。 */
    private int iconTintFor(State s, boolean isExpanded) {
        if (!isExpanded) {
            switch (s) {
                case CONNECTED:
                    return color(R.color.md_success);
                case ERROR:
                    return attr(com.google.android.material.R.attr.colorError);
                case CONNECTING:
                case DISCONNECTING:
                    return attr(com.google.android.material.R.attr.colorPrimary);
                default:
                    return attr(com.google.android.material.R.attr.colorOnPrimaryContainer);
            }
        }
        switch (s) {
            case CONNECTED:
                return color(R.color.md_success);
            case ERROR:
                return attr(com.google.android.material.R.attr.colorOnErrorContainer);
            case DISCONNECTED:
                return attr(com.google.android.material.R.attr.colorOnSurfaceVariant);
            default:
                return attr(com.google.android.material.R.attr.colorPrimary);
        }
    }

    /** 展开卡标题/副文案前景（compact 下文字隐藏，仅保持一致不闪烁）。 */
    private int titleColorFor(State s, boolean isExpanded) {
        if (!isExpanded) {
            return attr(com.google.android.material.R.attr.colorOnPrimaryContainer);
        }
        switch (s) {
            case CONNECTED:
                return color(R.color.md_on_success_container);
            case ERROR:
                return attr(com.google.android.material.R.attr.colorOnErrorContainer);
            case DISCONNECTED:
                return attr(com.google.android.material.R.attr.colorOnSurface);
            default:
                return attr(com.google.android.material.R.attr.colorOnPrimaryContainer);
        }
    }

    private void animateBgTo(int target) {
        if (currentBg == target) {
            return;
        }
        ValueAnimator bgAnim = ValueAnimator.ofArgb(currentBg, target);
        bgAnim.setDuration((long) (EXPAND_DURATION_MS * animatorScale()));
        bgAnim.addUpdateListener(a -> {
            currentBg = (int) a.getAnimatedValue();
            setCardBackgroundColor(currentBg);
        });
        bgAnim.start();
    }

    private void applyVisuals(State s) {
        boolean showProgress = (s == State.CONNECTING || s == State.DISCONNECTING);
        int cardColor = bgColorFor(s, expanded);
        int titleColor = titleColorFor(s, expanded);
        int subtitleColor = (expanded && s == State.DISCONNECTED)
                ? attr(com.google.android.material.R.attr.colorOnSurfaceVariant) : titleColor;
        int iconTint = iconTintFor(s, expanded);
        String desc;

        switch (s) {
            case CONNECTING:
                desc = getContext().getString(R.string.acc_vpn_connecting);
                break;
            case CONNECTED:
                desc = getContext().getString(R.string.acc_disconnect);
                break;
            case DISCONNECTING:
                desc = getContext().getString(R.string.acc_vpn_disconnecting);
                break;
            case ERROR:
                desc = getContext().getString(R.string.acc_vpn_error);
                break;
            case DISCONNECTED:
            default:
                desc = getContext().getString(R.string.acc_connect);
                break;
        }

        currentBg = cardColor;
        setCardBackgroundColor(cardColor);
        titleView.setTextColor(titleColor);
        subtitleView.setTextColor(subtitleColor);
        iconView.setImageTintList(android.content.res.ColorStateList.valueOf(iconTint));
        progressView.setIndicatorColor(iconTint);
        progressView.setTrackColor(android.graphics.Color.TRANSPARENT);
        iconView.setVisibility(showProgress ? GONE : VISIBLE);
        progressView.setVisibility(showProgress ? VISIBLE : GONE);
        // 错误卡内按钮前景与 onErrorContainer 对齐（对比度达标）；非错误卡用主色
        int actionColor = (s == State.ERROR && expanded) ? titleColor
                : attr(com.google.android.material.R.attr.colorPrimary);
        ((MaterialButton) findViewById(R.id.fab_retry)).setTextColor(actionColor);
        ((MaterialButton) findViewById(R.id.fab_details)).setTextColor(actionColor);
        setContentDescription(desc);
        // 广播异步到达时状态变化可被 TalkBack 播报（review §6.2）
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    // ================================ 工具 ================================

    private int attr(int attrResId) {
        return MaterialColors.getColor(this, attrResId);
    }

    private int color(int colorResId) {
        return ContextCompat.getColor(getContext(), colorResId);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private float readAnimatorScale() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                return Settings.Global.getFloat(getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            }
        } catch (Exception ignored) {
        }
        return 1f;
    }

    private float animatorScale() {
        return animationsDisabled ? 1f : readAnimatorScale();
    }

    private boolean isTouchExploration() {
        AccessibilityManager am =
                (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isTouchExplorationEnabled();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 作废所有挂起的形态/折叠回调，防止操作已分离的 view（review §7.4）
        generation++;
    }
}
