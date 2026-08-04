/*
 ============================================================================
 Name        : CustomCaptureActivity.java
 Author      : Claude Code
 Description : Custom QR Code Scanner Activity with Overlay
 ============================================================================
 */

package com.gcm.client.app;

import android.os.Bundle;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public class CustomCaptureActivity extends CaptureActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_custom_capture);
        return findViewById(R.id.zxing_barcode_scanner);
    }
}
