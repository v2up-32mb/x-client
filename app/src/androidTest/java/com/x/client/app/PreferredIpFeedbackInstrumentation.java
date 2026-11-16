package com.x.client.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/** System-only API24 runner: six data regressions plus native UI/AtomicFile v2 cases.
 * Network fixture assertions are NOT public-network/ECHAccepted/VPN authentication evidence. */
public final class PreferredIpFeedbackInstrumentation extends Instrumentation {
    private static final int TOTAL = PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length + PreferredIpV2ContinuationTest.NAMES.length + PreferredIpUiRemainingTest.NAMES.length + PreferredIpConfigurationCancelTest.NAMES.length + PreferredIpContextRemainingTest.NAMES.length;
    interface Created { void afterCreate(Activity activity); }
    Created afterActivityCreate;
    @Override public void callActivityOnCreate(Activity activity, Bundle saved) {
        super.callActivityOnCreate(activity, saved);
        Created observer = afterActivityCreate;
        if (observer != null) observer.afterCreate(activity);
    }
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
        result.putInt("preferred_ip_total", TOTAL);
        result.putInt("preferred_ip_api", Build.VERSION.SDK_INT);
        try {
            if (Build.VERSION.SDK_INT != 24 || !"com.x.client.app.trial".equals(getTargetContext().getPackageName())) {
                throw new AssertionError("This offline suite requires API 24 and the isolated trial app");
            }
            // Let the real Application finish onCreate before exercising its app classes.
            // If startup crashes/hangs, CI sees no complete success report (and has a timeout).
            waitForIdleSync();
            PreferredIpFeedbackTest.Reporter reporter = (number, name) -> {
                if (number != passed + 1) throw new AssertionError("Unexpected scenario order");
                passed = number;
                Bundle status = new Bundle();
                status.putString("preferred_ip_case", "PASS " + number + "/" + TOTAL + " " + name);
                sendStatus(0, status);
            };
            PreferredIpFeedbackTest.run(getTargetContext(), reporter);
            PreferredIpV2Test.run(this, reporter);
            PreferredIpV2ContinuationTest.run(this, reporter);
            PreferredIpUiRemainingTest.run(this, reporter);
            PreferredIpConfigurationCancelTest.run(this, reporter);
            PreferredIpContextRemainingTest.run(this, reporter);
            if (passed != TOTAL) throw new AssertionError("Incomplete suite");
            result.putInt("preferred_ip_passed", passed);
            result.putString("preferred_ip_result", "PASS " + TOTAL + "/" + TOTAL);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // AssertionError/LinkageError are not Exceptions. Crashes outside this thread are
            // failures too: CI requires ALL named PASS records and the final successful bundle/code.
            result.putInt("preferred_ip_passed", passed);
            result.putString("preferred_ip_result", "FAIL " + passed + "/" + TOTAL);
            result.putString("stream", Log.getStackTraceString(failure));
            Log.e("PreferredIpFeedback", "Offline scenario failed", failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
