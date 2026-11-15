package com.x.client.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/** System-only runner for six offline scenarios. Does not launch an Activity, VPN or probe. */
public final class PreferredIpFeedbackInstrumentation extends Instrumentation {
    private int passed;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        result.putInt("preferred_ip_total", PreferredIpFeedbackTest.TOTAL);
        result.putInt("preferred_ip_api", Build.VERSION.SDK_INT);
        try {
            if (Build.VERSION.SDK_INT != 24 || !"com.x.client.app.trial".equals(getTargetContext().getPackageName())) {
                throw new AssertionError("This offline suite requires API 24 and the isolated trial app");
            }
            // Let the real Application finish onCreate before exercising its app classes.
            // If startup crashes/hangs, CI sees no complete success report (and has a timeout).
            waitForIdleSync();
            PreferredIpFeedbackTest.run(getTargetContext(), (number, name) -> {
                if (number != passed + 1) throw new AssertionError("Unexpected scenario order");
                passed = number;
                Bundle status = new Bundle();
                status.putString("preferred_ip_case", "PASS " + number + "/" + PreferredIpFeedbackTest.TOTAL + " " + name);
                sendStatus(0, status);
            });
            if (passed != PreferredIpFeedbackTest.TOTAL) throw new AssertionError("Incomplete suite");
            result.putInt("preferred_ip_passed", passed);
            result.putString("preferred_ip_result", "PASS 6/6");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // AssertionError/LinkageError are not Exceptions. Crashes outside this thread are
            // also failures: CI requires all six PASS records AND the final successful bundle/code.
            result.putInt("preferred_ip_passed", passed);
            result.putString("preferred_ip_result", "FAIL " + passed + "/" + PreferredIpFeedbackTest.TOTAL);
            result.putString("stream", Log.getStackTraceString(failure));
            Log.e("PreferredIpFeedback", "Offline scenario failed", failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
