/*
 ============================================================================
 Name        : CustomCaptureActivity.java
 Author      : Claude Code
 Description : Custom QR Code Scanner Activity with Overlay
 ============================================================================
 */

package com.x.client.app;

import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public class CustomCaptureActivity extends CaptureActivity {

    private boolean isTorchOn = false;
    private DecoratedBarcodeView barcodeView;
    private MaterialButton btnFlashlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 扫码页同样跟随调色板（Titanium/Shield 变体），setTheme 须在 super 之前
        ThemeManager.applyPaletteTo(this);
        super.onCreate(savedInstanceState);

        // 设置透明系统栏与状态栏沉浸式适配
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        // 扫码全屏为暗色相机画面，强制状态栏与导航栏图标为亮色（白字/白图标），避免在浅色系统主题下不可见
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(false);
            insetsController.setAppearanceLightNavigationBars(false);
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
            ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                Insets statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars());
                v.setPadding(v.getPaddingLeft(), statusBar.top, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
        }

        // 提示文本药丸：动态适配虚拟导航栏高度，避免与底部手电筒按钮重叠
        View statusView = findViewById(R.id.zxing_status_view);
        if (statusView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(statusView, (v, insets) -> {
                Insets navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                int defaultMargin = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 108, getResources().getDisplayMetrics());
                lp.bottomMargin = defaultMargin + navBar.bottom;
                v.setLayoutParams(lp);
                return insets;
            });
        }

        btnFlashlight = findViewById(R.id.btn_flashlight);
        if (btnFlashlight != null) {
            ViewCompat.setOnApplyWindowInsetsListener(btnFlashlight, (v, insets) -> {
                Insets navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                int defaultMargin = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
                lp.bottomMargin = defaultMargin + navBar.bottom;
                v.setLayoutParams(lp);
                return insets;
            });

            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                btnFlashlight.setVisibility(View.GONE);
            } else {
                btnFlashlight.setOnClickListener(v -> {
                    if (isTorchOn) {
                        barcodeView.setTorchOff();
                    } else {
                        barcodeView.setTorchOn();
                    }
                });

                barcodeView.setTorchListener(new DecoratedBarcodeView.TorchListener() {
                    @Override
                    public void onTorchOn() {
                        isTorchOn = true;
                        updateFlashlightButton(true);
                    }

                    @Override
                    public void onTorchOff() {
                        isTorchOn = false;
                        updateFlashlightButton(false);
                    }
                });
            }
        }
    }

    private void updateFlashlightButton(boolean on) {
        if (btnFlashlight != null) {
            btnFlashlight.setText(on ? R.string.scanner_flashlight_off : R.string.scanner_flashlight_on);
            btnFlashlight.setIconResource(on ? R.drawable.ic_flashlight_off : R.drawable.ic_flashlight_on);
        }
    }

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_custom_capture);
        barcodeView = findViewById(R.id.zxing_barcode_scanner);
        return barcodeView;
    }
}
