/*
 ============================================================================
 Name        : ScannerOverlayView.java
 Author      : Claude Code
 Description : Custom Scanner Overlay with Semi-transparent Mask
 ============================================================================
 */

package com.x.client.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import com.journeyapps.barcodescanner.ViewfinderView;

public class ScannerOverlayView extends ViewfinderView {
    private Paint maskPaint;
    private Paint framePaint;
    private Paint cornerPaint;
    // 默认值与 values/colors.xml 的 scanner_* token 一致；
    // 实际取值来自 custom_barcode_scanner.xml 的 app:scanner* 属性（token 化，见 C9）
    private int maskColor = 0x80000000; // 半透明黑色
    private int frameColor = 0xFFFFFFFF; // 白色边框
    private int cornerColor = 0xFF00C853; // 绿色角标
    private int lineColor = 0x8000E676; // 半透明绿色扫描线
    private int cornerLength = 60; // 角标长度
    private int cornerWidth = 8; // 角标宽度

    public ScannerOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            android.content.res.TypedArray ta = context.obtainStyledAttributes(attrs,
                    com.x.client.app.R.styleable.ScannerOverlayView);
            maskColor = ta.getColor(com.x.client.app.R.styleable.ScannerOverlayView_scannerMaskColor, maskColor);
            frameColor = ta.getColor(com.x.client.app.R.styleable.ScannerOverlayView_scannerFrameColor, frameColor);
            cornerColor = ta.getColor(com.x.client.app.R.styleable.ScannerOverlayView_scannerCornerColor, cornerColor);
            lineColor = ta.getColor(com.x.client.app.R.styleable.ScannerOverlayView_scannerLineColor, lineColor);
            ta.recycle();
        }
        init();
    }

    private void init() {
        maskPaint = new Paint();
        maskPaint.setColor(maskColor);
        maskPaint.setStyle(Paint.Style.FILL);

        framePaint = new Paint();
        framePaint.setColor(frameColor);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(2);

        cornerPaint = new Paint();
        cornerPaint.setColor(cornerColor);
        cornerPaint.setStyle(Paint.Style.FILL);
        cornerPaint.setStrokeWidth(cornerWidth);

        // 转换 dp 到 px
        float density = getContext().getResources().getDisplayMetrics().density;
        cornerLength = (int) (cornerLength * density);
        cornerWidth = (int) (cornerWidth * density);
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (framingRect == null) {
            return;
        }

        Rect frame = framingRect;
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // 绘制半透明遮罩（扫描框外的区域）
        // 上方
        canvas.drawRect(0, 0, width, frame.top, maskPaint);
        // 左侧
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, maskPaint);
        // 右侧
        canvas.drawRect(frame.right, frame.top, width, frame.bottom, maskPaint);
        // 下方
        canvas.drawRect(0, frame.bottom, width, height, maskPaint);

        // 绘制扫描框边框
        canvas.drawRect(frame, framePaint);

        // 绘制四个角的绿色标记
        // 左上角
        canvas.drawRect(frame.left, frame.top, frame.left + cornerLength, frame.top + cornerWidth, cornerPaint);
        canvas.drawRect(frame.left, frame.top, frame.left + cornerWidth, frame.top + cornerLength, cornerPaint);

        // 右上角
        canvas.drawRect(frame.right - cornerLength, frame.top, frame.right, frame.top + cornerWidth, cornerPaint);
        canvas.drawRect(frame.right - cornerWidth, frame.top, frame.right, frame.top + cornerLength, cornerPaint);

        // 左下角
        canvas.drawRect(frame.left, frame.bottom - cornerWidth, frame.left + cornerLength, frame.bottom, cornerPaint);
        canvas.drawRect(frame.left, frame.bottom - cornerLength, frame.left + cornerWidth, frame.bottom, cornerPaint);

        // 右下角
        canvas.drawRect(frame.right - cornerLength, frame.bottom - cornerWidth, frame.right, frame.bottom, cornerPaint);
        canvas.drawRect(frame.right - cornerWidth, frame.bottom - cornerLength, frame.right, frame.bottom, cornerPaint);

        // 绘制扫描线（可选）
        drawScanLine(canvas, frame);

        // 请求重绘以实现动画效果
        postInvalidateDelayed(16);
    }

    private void drawScanLine(Canvas canvas, Rect frame) {
        // 简单的扫描线动画
        long currentTime = System.currentTimeMillis();
        int scanLineTop = frame.top + (int) ((currentTime / 10) % frame.height());

        Paint scanLinePaint = new Paint();
        scanLinePaint.setColor(lineColor); // 半透明绿色（token）
        scanLinePaint.setStrokeWidth(4);

        canvas.drawLine(frame.left, scanLineTop, frame.right, scanLineTop, scanLinePaint);
    }
}
