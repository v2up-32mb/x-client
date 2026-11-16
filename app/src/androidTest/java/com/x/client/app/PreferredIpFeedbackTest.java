package com.x.client.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline fixtures on real Android JSON/Preferences and app code, not UI/network tests.
 * Called by the trial CI system Instrumentation; no JUnit/AndroidX/android.test dependency. */
public final class PreferredIpFeedbackTest {
    static final int TOTAL = 6;
    private final Context context;
    private Preferences profile;
    private String id;

    private PreferredIpFeedbackTest(Context context) { this.context = context; }
    private interface Scenario { void run() throws Exception; }

    interface Reporter { void passed(int number, String name); }

    public static void run(Context context) throws Exception {
        run(context, (number, name) -> { });
    }

    static void run(Context context, Reporter reporter) throws Exception {
        PreferredIpFeedbackTest suite = new PreferredIpFeedbackTest(context);
        String[] names = {"testThreeSavedIpsReplaceSixOnlyAfterValidation", "testSubsetAndNonAdoptionReasons",
                "testUnknownChangedTargetAndAdvancedConflictStayOriginal", "testNoDownloadRecommendationFalseIsNotFailure",
                "testThirdPartyCommentHiddenAndManualProvenanceRecovered", "testReadableLogDeduplicatesAndBounds"};
        Scenario[] scenarios = {suite::testThreeSavedIpsReplaceSixOnlyAfterValidation,
                suite::testSubsetAndNonAdoptionReasons, suite::testUnknownChangedTargetAndAdvancedConflictStayOriginal,
                suite::testNoDownloadRecommendationFalseIsNotFailure,
                suite::testThirdPartyCommentHiddenAndManualProvenanceRecovered,
                suite::testReadableLogDeduplicatesAndBounds};
        assertEquals(TOTAL, scenarios.length);
        assertEquals(TOTAL, names.length);
        for (int i = 0; i < scenarios.length; i++) {
            try { suite.setUp(); scenarios[i].run(); }
            finally { suite.tearDown(); }
            // A PASS is emitted only after this scenario AND its cleanup actually return.
            reporter.passed(i + 1, names[i]);
        }
    }

    private static void assertTrue(boolean value) { if (!value) throw new AssertionError("expected true"); }
    private static void assertFalse(boolean value) { assertTrue(!value); }
    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", actual " + actual);
    }

    private void setUp() throws Exception {
        id = "preferred-feedback-" + System.nanoTime();
        profile = new Preferences(context).forProfile(id);
        profile.setProtocol(Preferences.PROTOCOL_X_TUNNEL);
        profile.setXtServerAddr("wss://node.example.com/path");
        profile.setXtConnections(3);
        profile.setXtRelayNodes("1.1.1.1:443,1.0.0.1:443,2.2.2.2:443,3.3.3.3:443,4.4.4.4:443,5.5.5.5:443");
        profile.setXtAdvancedParams("");
    }

    private void tearDown() {
        // Only remove this test's temporary scoped keys; never touch real profiles or the pool.
        android.content.SharedPreferences prefs = context.getSharedPreferences(Preferences.PREFS_NAME, 0);
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.endsWith("_" + id)) edit.remove(key);
        assertTrue(edit.commit());
    }

    private JSONObject pool() throws Exception {
        JSONObject pool = PreferredIpStore.empty().put("enabled", true);
        JSONArray selected = new JSONArray(), addresses = new JSONArray();
        for (String address : new String[]{"104.16.1.1:443", "104.16.1.2:443", "104.16.1.3:443"}) {
            selected.put(new JSONObject().put("address", address));
            addresses.put(address);
        }
        pool.put("selected", selected);
        pool.getJSONObject("profile_modes").put(id, "global");
        pool.getJSONObject("validations").put(id, new JSONObject().put("addresses", addresses)
                .put("target_key", PreferredIpResolver.fingerprint(PreferredIpResolver.target(profile, PreferredIpResolver.baseParams(profile)))));
        return pool;
    }

    public void testThreeSavedIpsReplaceSixOnlyAfterValidation() throws Exception {
        JSONObject pool = pool(), params = PreferredIpResolver.baseParams(profile);
        JSONObject validation = pool.getJSONObject("validations").getJSONObject(id);
        pool.getJSONObject("validations").remove(id);
        assertFalse(PreferredIpResolver.apply(profile, params, pool).applied);
        assertEquals(profile.getXtRelayNodes(), params.getString("relay_nodes"));
        pool.getJSONObject("validations").put(id, validation);
        PreferredIpResolver.Decision decision = PreferredIpResolver.apply(profile, params, pool);
        assertTrue(decision.applied);
        assertEquals("104.16.1.1:443,104.16.1.2:443,104.16.1.3:443", params.getString("relay_nodes"));
        assertTrue(decision.summary.contains("= 9"));
        assertEquals(6, profile.getXtRelayNodes().split(",").length); // original is untouched
        pool.getJSONArray("selected").put(pool.getJSONArray("selected").getJSONObject(0));
        pool.getJSONObject("validations").getJSONObject(id).put("addresses", new JSONArray()
                .put("104.16.1.3:443").put("104.16.1.2:443").put("104.16.1.1:443"));
        PreferredIpResolver.apply(profile, params, pool);
        assertEquals("104.16.1.1:443,104.16.1.2:443,104.16.1.3:443", params.getString("relay_nodes")); // ordered, deduplicated
    }

    public void testSubsetAndNonAdoptionReasons() throws Exception {
        JSONObject pool = pool();
        pool.getJSONObject("validations").getJSONObject(id).put("addresses", new JSONArray().put("104.16.1.2:443").put("8.8.8.8:443"));
        JSONObject params = PreferredIpResolver.baseParams(profile);
        assertTrue(PreferredIpResolver.apply(profile, params, pool).summary.contains("= 3"));
        assertEquals("104.16.1.2:443", params.getString("relay_nodes"));
        pool.put("enabled", false);
        params = PreferredIpResolver.baseParams(profile);
        assertFalse(PreferredIpResolver.apply(profile, params, pool).applied);
        assertEquals(profile.getXtRelayNodes(), params.getString("relay_nodes"));
        pool.put("enabled", true);
        pool.getJSONObject("profile_modes").put(id, "independent");
        assertFalse(PreferredIpResolver.apply(profile, params, pool).applied);
        pool.getJSONObject("profile_modes").put(id, "default");
        PreferredIpResolver.apply(profile, params, pool);
        assertEquals("", params.getString("relay_nodes"));
    }

    public void testUnknownChangedTargetAndAdvancedConflictStayOriginal() throws Exception {
        JSONObject pool = pool();
        profile.setXtServerAddr("wss://different.example.com");
        JSONObject params = PreferredIpResolver.baseParams(profile);
        assertFalse(PreferredIpResolver.apply(profile, params, pool).applied);
        assertEquals(profile.getXtRelayNodes(), params.getString("relay_nodes"));
        pool.getJSONObject("validations").remove(id);
        assertFalse(PreferredIpResolver.apply(profile, params, pool).applied);
        profile.setXtAdvancedParams("{\"relay_nodes\":\"8.8.8.8:443\",\"connections\":3}");
        params = PreferredIpResolver.baseParams(profile);
        assertTrue(PreferredIpResolver.apply(profile, params, pool).reason.contains("冲突"));
        assertEquals("8.8.8.8:443", params.getString("relay_nodes"));
    }

    public void testNoDownloadRecommendationFalseIsNotFailure() throws Exception {
        JSONObject result = new JSONObject().put("status", "valid").put("http_success", 5)
                .put("colo", "HKG").put("recommended", false).put("download_state", "untested");
        assertTrue(PreferredIpController.compatible(result));
        result.put("status", "failed").put("error", "TLS certificate failure");
        assertFalse(PreferredIpController.compatible(result));
    }

    public void testThirdPartyCommentHiddenAndManualProvenanceRecovered() throws Exception {
        String id = PreferredIpIds.opaque("list", "https://list.example/ips.txt");
        JSONObject manual = new JSONObject().put("address", "1.1.1.1:443").put("source_kind", "manual")
                .put("source_id", "manual").put("comment", "我的备注");
        JSONObject third = new JSONObject(manual.toString()).put("source_kind", "third_party")
                .put("source_id", id).put("source_ids", new JSONArray().put(id).put("manual"))
                .put("source_kinds", new JSONArray().put("third_party").put("manual"))
                .put("comment", "第三方碎片 / 我的备注");
        JSONObject data = PreferredIpStore.empty();
        data.getJSONArray("sources").put(new JSONObject().put("id", id).put("url", "https://list.example/ips.txt")
                .put("candidates", new JSONArray().put(third)));
        assertEquals("", PreferredIpPresentation.comment(third, data));
        data.getJSONArray("sources").put(new JSONObject().put("id", "manual")
                .put("candidates", new JSONArray().put(manual)));
        assertEquals("我的备注", PreferredIpPresentation.comment(third, data));
        assertEquals("我的备注", PreferredIpPresentation.comment(manual, data));
        assertEquals("https://list.example/ips.txt\n手动粘贴", PreferredIpPresentation.sources(third, data));
        assertFalse(PreferredIpPresentation.sources(third, data).contains(id));
        assertEquals("第三方碎片 / 我的备注", third.getString("comment"));
        assertTrue(PreferredIpPresentation.sources(third, PreferredIpStore.empty()).contains("URL 映射已缺失"));
        data.getJSONArray("sources").getJSONObject(1).getJSONArray("candidates")
                .put(new JSONObject(manual.toString()).put("comment", "第二条备注")).put(manual);
        assertEquals("我的备注 / 第二条备注", PreferredIpPresentation.comment(third, data));
    }

    public void testReadableLogDeduplicatesAndBounds() throws Exception {
        PreferredIpLog log = new PreferredIpLog();
        assertEquals("", log.text());
        JSONObject snapshot = new JSONObject().put("id", "task-1").put("operation", "test")
                .put("phase", "latency").put("state", "running").put("completed", 1).put("total", 3)
                .put("warnings", new JSONArray().put("ECH fallback"));
        log.snapshot(snapshot);
        String once = log.text();
        log.snapshot(snapshot);
        assertEquals(once, log.text());
        assertTrue(once.contains("HTTPS"));
        for (int i = 0; i < 1000; i++) log.add("event-" + i);
        assertTrue(log.text().split("\n").length <= PreferredIpLog.MAX_LINES);
        assertTrue(log.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PreferredIpLog.MAX_BYTES);
        assertTrue(log.text().contains("event-999"));
    }
}
