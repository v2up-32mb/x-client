/*
 ============================================================================
 Name        : CustomCaptureActivity.java
 Author      : Claude Code
 Description : Custom QR Code Scanner Activity with Overlay
 ============================================================================
 */

package com.x.client.app;

import android.os.Bundle;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public class CustomCaptureActivity extends CaptureActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 扫码页同样跟随调色板（Titanium/Shield 变体），setTheme 须在 super 之前
        ThemeManager.applyPaletteTo(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_custom_capture);
        return findViewById(R.id.zxing_barcode_scanner);
    }
}
