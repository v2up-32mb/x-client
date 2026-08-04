/*
 ============================================================================
 Name        : SwipeRevealLayout.java
 Author      : Claude Code
 Description : Custom ViewGroup for Swipe-to-Reveal interaction (Overlay Mode)
 ============================================================================
 */

package com.gcm.client.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

public class SwipeRevealLayout extends FrameLayout {

    // 状态枚举
    public enum State {
        CLOSED,      // 关闭状态
        OPEN         // 打开状态（显示按钮）
    }

    // 子视图
    private View foregroundView;   // 前景层（内容）
    private View actionButtons;    // 操作按钮层（overlay）

    // 滑动配置
    private float actionWidth = 168f;  // 三个按钮总宽度（56dp * 3）
    private float snapThreshold = 0.3f;  // 30% 阈值（降低灵敏度）

    // 触摸处理
    private VelocityTracker velocityTracker;
    private float initialX, initialY;
    private float lastX;
    private boolean isDragging = false;
    private boolean isHorizontalSwipe = false;
    private int touchSlop;
    private int minFlingVelocity;
    private int maxFlingVelocity;

    // 动画
    private ValueAnimator animator;
    private static final int ANIMATION_DURATION = 250; // ms

    // 状态
    private State currentState = State.CLOSED;
    private boolean swipeEnabled = true;

    // 当前展开的宽度（用于裁剪）
    private float currentRevealWidth = 0f;

    // 回调
    private OnSwipeListener listener;
    private OnSwipeStartListener swipeStartListener;

    public SwipeRevealLayout(Context context) {
        this(context, null);
    }

    public SwipeRevealLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeRevealLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // 初始化触摸配置
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        minFlingVelocity = config.getScaledMinimumFlingVelocity();
        maxFlingVelocity = config.getScaledMaximumFlingVelocity();

        // 初始化 VelocityTracker
        velocityTracker = VelocityTracker.obtain();

        // 转换 dp 到 px
        float density = context.getResources().getDisplayMetrics().density;
        actionWidth = 168 * density;  // 56dp * 3 buttons
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // 查找子视图
        if (getChildCount() >= 3) {
            foregroundView = getChildAt(0);
            // getChildAt(1) 是分界线
            actionButtons = getChildAt(2);

            // 初始化按钮位置（固定在右侧）
            if (actionButtons != null) {
                actionButtons.setVisibility(View.GONE);
                // 按钮固定在右侧，不使用 translationX
                currentRevealWidth = 0f;
            }
        }

        // 启用绘制裁剪
        setWillNotDraw(false);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        // 只对按钮层应用裁剪
        if (child == actionButtons && currentRevealWidth > 0 && currentRevealWidth < actionWidth) {
            // 保存画布状态
            int saveCount = canvas.save();

            // 裁剪按钮层，只显示右侧的 currentRevealWidth 宽度
            int left = getWidth() - (int) currentRevealWidth;
            canvas.clipRect(left, 0, getWidth(), getHeight());

            // 绘制按钮层
            boolean result = super.drawChild(canvas, child, drawingTime);

            // 恢复画布状态
            canvas.restoreToCount(saveCount);
            return result;
        } else {
            // 其他子视图正常绘制
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialX = event.getX();
                initialY = event.getY();
                lastX = initialX;
                isHorizontalSwipe = false;
                velocityTracker.clear();
                velocityTracker.addMovement(event);
                // 不要在这里拦截，让事件传递到子视图
                return false;

            case MotionEvent.ACTION_MOVE:
                float deltaX = Math.abs(event.getX() - initialX);
                float deltaY = Math.abs(event.getY() - initialY);

                // 判断是否为水平滑动
                if (!isHorizontalSwipe && (deltaX > touchSlop || deltaY > touchSlop)) {
                    isHorizontalSwipe = deltaX > deltaY * 1.5f;
                }

                // 如果是水平滑动，拦截事件
                if (isHorizontalSwipe && swipeEnabled) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                velocityTracker.clear();
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!swipeEnabled) return super.onTouchEvent(event);

        velocityTracker.addMovement(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 取消正在进行的动画
                if (animator != null && animator.isRunning()) {
                    animator.cancel();
                }
                lastX = event.getX();
                isDragging = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                float currentX = event.getX();
                float deltaX = currentX - lastX;

                // 通知开始滑动（用于关闭其他打开的项）
                if (swipeStartListener != null && Math.abs(deltaX) > touchSlop) {
                    swipeStartListener.onSwipeStart(this);
                }

                // 根据当前状态决定滑动行为
                if (currentState == State.CLOSED) {
                    // 关闭状态：只允许左滑（按钮从右侧滑入）
                    if (deltaX < 0) {
                        // 当前 translationX 是正值（在屏幕外）
                        // 左滑时减少 translationX，让按钮滑入
                        float currentTranslation = getTranslationOffset();
                        float newTranslation = currentTranslation + deltaX;
                        // 限制范围：0（完全显示）到 actionWidth（完全隐藏）
                        newTranslation = Math.max(0, Math.min(actionWidth, newTranslation));
                        setTranslationOffset(newTranslation);
                    }
                } else {
                    // 打开状态：只允许右滑关闭
                    if (deltaX > 0) {
                        float currentTranslation = getTranslationOffset();
                        float newTranslation = currentTranslation + deltaX;
                        newTranslation = Math.max(0, Math.min(actionWidth, newTranslation));
                        setTranslationOffset(newTranslation);
                    }
                }

                lastX = currentX;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;

                // 计算速度
                velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                float velocityX = velocityTracker.getXVelocity();

                // 确定目标状态
                float currentOffset = getTranslationOffset();
                State targetState = calculateTargetState(currentOffset, velocityX);

                // 动画到目标位置
                snapToState(targetState);

                velocityTracker.clear();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean isClickInActionButtons(MotionEvent event) {
        if (actionButtons == null || actionButtons.getVisibility() != View.VISIBLE) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        // 按钮固定在右侧，可见宽度为 currentRevealWidth
        float buttonLeft = getWidth() - currentRevealWidth;
        float buttonRight = getWidth();
        float buttonTop = 0;
        float buttonBottom = actionButtons.getHeight();

        return x >= buttonLeft && x <= buttonRight && y >= buttonTop && y <= buttonBottom;
    }

    private float getTranslationOffset() {
        // 返回当前隐藏的宽度（actionWidth - currentRevealWidth）
        return actionWidth - currentRevealWidth;
    }

    private void setTranslationOffset(float offset) {
        if (actionButtons == null) return;

        // offset: 0（完全显示）到 actionWidth（完全隐藏）
        // 转换为 currentRevealWidth: actionWidth（完全显示）到 0（完全隐藏）
        offset = Math.max(0, Math.min(actionWidth, offset));
        currentRevealWidth = actionWidth - offset;

        // 更新可见性和透明度
        if (currentRevealWidth > 0) {
            actionButtons.setVisibility(View.VISIBLE);
            // currentRevealWidth 越大，按钮越可见
            float alpha = currentRevealWidth / actionWidth;
            actionButtons.setAlpha(alpha);
            // 触发重绘以应用裁剪
            invalidate();
        } else {
            actionButtons.setVisibility(View.GONE);
        }
    }

    private State calculateTargetState(float offset, float velocity) {
        // offset: 0 = 完全显示，actionWidth = 完全隐藏

        // 高速滑动：根据速度方向决定
        if (Math.abs(velocity) > minFlingVelocity) {
            if (velocity < 0) {
                return State.OPEN;  // 左滑，打开
            } else {
                return State.CLOSED;  // 右滑，关闭
            }
        }

        // 低速滑动：根据阈值决定
        // 如果滑出的距离（actionWidth - offset）超过阈值，则打开
        float revealedWidth = actionWidth - offset;
        return revealedWidth >= actionWidth * snapThreshold ? State.OPEN : State.CLOSED;
    }

    private void snapToState(State targetState) {
        float targetOffset = (targetState == State.OPEN) ? 0 : actionWidth;
        animateToPosition(targetOffset, targetState);
    }

    private void animateToPosition(float targetOffset, State targetState) {
        float startOffset = getTranslationOffset();

        animator = ValueAnimator.ofFloat(startOffset, targetOffset);
        animator.setDuration(ANIMATION_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());

        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            setTranslationOffset(value);
        });

        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                currentState = targetState;
                if (listener != null) {
                    if (targetState == State.CLOSED) {
                        listener.onClosed();
                    } else {
                        listener.onOpened(targetState);
                    }
                }
            }
        });

        animator.start();
    }

    // 公共方法

    public void close() {
        close(true);
    }

    public void close(boolean animated) {
        if (animated) {
            snapToState(State.CLOSED);
        } else {
            setTranslationOffset(actionWidth);
            currentState = State.CLOSED;
        }
    }

    public boolean isOpened() {
        return currentState != State.CLOSED;
    }

    public State getState() {
        return currentState;
    }

    public void setSwipeEnabled(boolean enabled) {
        this.swipeEnabled = enabled;
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    public void setOnSwipeStartListener(OnSwipeStartListener listener) {
        this.swipeStartListener = listener;
    }

    // 回调接口
    public interface OnSwipeListener {
        void onOpened(State state);
        void onClosed();
    }

    public interface OnSwipeStartListener {
        void onSwipeStart(SwipeRevealLayout layout);
    }
}
