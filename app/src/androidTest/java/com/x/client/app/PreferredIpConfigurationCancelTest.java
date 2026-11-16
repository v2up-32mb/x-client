package com.x.client.app;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.CompoundButton;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static com.x.client.app.PreferredIpV2Test.*;
import static com.x.client.app.PreferredIpUiRemainingTest.Anchor;
import static com.x.client.app.PreferredIpUiRemainingTest.assertWidgets;
import static com.x.client.app.PreferredIpUiRemainingTest.header;
import static com.x.client.app.PreferredIpUiRemainingTest.middleByGesture;
import static com.x.client.app.PreferredIpUiRemainingTest.nextVisible;
import static com.x.client.app.PreferredIpUiRemainingTest.select;
import static com.x.client.app.PreferredIpUiRemainingTest.verticalGesture;

/** Stage B only: actual framework configuration recreation (rotation, AppCompat night mode through
 * the app's own Settings Spinner, system font scale through the real API24 display settings page)
 * and two blocked-actor windows. Nothing writes State, Row, proof, selection flags,
 * AppCompatDelegate or Settings providers directly. */
final class PreferredIpConfigurationCancelTest {
    static final String[] NAMES = {
        "testNativeRotationRetainsSessionAndPartialQueue",
        "testLightNormalFontNativeLayout", "testDarkNormalFontNativeLayout",
        "testLightLargeFontNativeLayout", "testDarkLargeFontNativeLayout",
        "testPollAndStoreBlockedCancelRemainsIndependent",
        "testAdmittedStartReturnDoesNotBlockSelection"
    };
    private static final int LARGEST = 0, RESTORE = 1;
    /** Real result rows for the configuration matrix; enough for a non-boundary native anchor. */
    private static final int MATRIX_ROWS = 21;
    private static final String REMAINING = "104.16.2.1:443";

    static void run(Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpConfigurationCancelTest::testNativeRotationRetainsSessionAndPartialQueue,
            PreferredIpConfigurationCancelTest::testLightNormalFontNativeLayout,
            PreferredIpConfigurationCancelTest::testDarkNormalFontNativeLayout,
            PreferredIpConfigurationCancelTest::testLightLargeFontNativeLayout,
            PreferredIpConfigurationCancelTest::testDarkLargeFontNativeLayout,
            PreferredIpConfigurationCancelTest::testPollAndStoreBlockedCancelRemainsIndependent,
            PreferredIpConfigurationCancelTest::testAdmittedStartReturnDoesNotBlockSelection };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length
                + PreferredIpV2ContinuationTest.NAMES.length + PreferredIpUiRemainingTest.NAMES.length + n + 1, NAMES[n]);
        }
    }

    interface Match { boolean matches(AccessibilityNodeInfo node); }
    interface LiveMatch { boolean matches(PreferredIpActivity activity); }

    private static void walk(AccessibilityNodeInfo node, Match match, List<AccessibilityNodeInfo> hits, int depth) {
        if (depth > 40 || node == null) return;
        if (node.isVisibleToUser() && match.matches(node)) hits.add(AccessibilityNodeInfo.obtain(node));
        for (int n = 0; n < node.getChildCount(); n++) {
            AccessibilityNodeInfo child = node.getChild(n);
            if (child != null) try { walk(child, match, hits, depth + 1); } finally { child.recycle(); }
        }
    }

    private static void recycle(List<AccessibilityNodeInfo> nodes) { for (AccessibilityNodeInfo node : nodes) node.recycle(); }

    private static AccessibilityNodeInfo first(List<AccessibilityNodeInfo> hits) {
        if (hits.isEmpty()) return null;
        for (int n = 1; n < hits.size(); n++) hits.get(n).recycle();
        return hits.get(0);
    }

    private static String text(AccessibilityNodeInfo node) {
        CharSequence value = node.getText();
        return value == null ? "" : value.toString();
    }

    private static boolean settings(AccessibilityNodeInfo node) {
        return "com.android.settings".contentEquals(node.getPackageName() == null ? "" : node.getPackageName());
    }

    private static int night(int mode) {
        return mode == Preferences.THEME_DARK ? Configuration.UI_MODE_NIGHT_YES
            : mode == Preferences.THEME_LIGHT ? Configuration.UI_MODE_NIGHT_NO : -1;
    }

    private static int delegateMode(int mode) {
        return mode == Preferences.THEME_DARK ? AppCompatDelegate.MODE_NIGHT_YES
            : mode == Preferences.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private static int labelRes(int mode) {
        return mode == Preferences.THEME_DARK ? R.string.theme_dark
            : mode == Preferences.THEME_LIGHT ? R.string.theme_light : R.string.theme_system;
    }

    /** Tracks every actual replacement Activity, drives real system configuration and restores it.
     * Rotation uses ActivityThread recreation through setRequestedOrientation, night mode uses the
     * app's own Settings Spinner, font scale uses the real API24 system font page. */
    static final class ConfigurationGuard implements AutoCloseable {
        final Fixture f;
        final PreferredIpUiRemainingTest.NativeAccess ui;
        final PreferredIpFeedbackInstrumentation runner;
        final PreferredIpFeedbackInstrumentation.Created savedHook;
        final List<Activity> created = new ArrayList<>();
        final float originalFont;
        final int originalTheme, originalNight;
        int originalOrientation = -1, originalRequested = -1;
        int restoreOption = -1, restoreCount = -1;
        String restoreLabel = "";
        float originalProgress;
        String toolbarResource;
        boolean orientationKnown, changedOrientation, themeTouched, fontKnown, external, closed;

        ConfigurationGuard(Fixture f) throws Exception {
            this.f = f;
            ui = new PreferredIpUiRemainingTest.NativeAccess(f); // real UiAutomation, restored by its own close
            originalFont = Settings.System.getFloat(f.target.getContentResolver(), Settings.System.FONT_SCALE, 1f); // read only
            Object persisted = f.original.get(Preferences.THEME_MODE);
            originalTheme = persisted instanceof Integer ? (Integer) persisted : Preferences.THEME_SYSTEM;
            originalNight = AppCompatDelegate.getDefaultNightMode();
            runner = (PreferredIpFeedbackInstrumentation) f.i;
            savedHook = runner.afterActivityCreate;
            f.resources.add(this); // closes BEFORE NativeAccess restores serviceInfo
            f.main(() -> runner.afterActivityCreate = activity -> {
                created.add(activity);
                if (savedHook != null) savedHook.afterCreate(activity);
            });
        }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            f.failures.attempt(f.backend::drain);
            boolean safe = f.controller == null;
            if (!safe) {
                final PreferredIpController controller = f.controller;
                try {
                    ExecutorService control = (ExecutorService) field(controller, "control");
                    controller.pause("configuration cleanup");
                    until(() -> control.submit(() -> field(controller, "active") == null
                        && !(Boolean) field(controller, "preparingDisk")
                        && (Integer) field(controller, "selectionPending") == 0).get(10, TimeUnit.SECONDS),
                        "configuration cleanup matching drain");
                    f.actorDrain();
                    safe = true;
                } catch (Throwable failure) { f.failures.add(failure); }
            }
            if (safe) {
                f.failures.attempt(() -> { if (external) returnApp(); });
                f.failures.attempt(this::finishSettings);
                f.failures.attempt(() -> { if (themeTouched) theme(originalTheme); });
                f.failures.attempt(() -> {
                    if (fontKnown && Settings.System.getFloat(f.target.getContentResolver(), Settings.System.FONT_SCALE, 1f) != originalFont)
                        eq(originalFont, driveFont(RESTORE));
                });
                f.failures.attempt(() -> {
                    if (orientationKnown && changedOrientation) restoreOrientation();
                });
                f.failures.attempt(this::finishSettings);
                f.failures.attempt(() -> eq(originalFont, Settings.System.getFloat(f.target.getContentResolver(), Settings.System.FONT_SCALE, 1f)));
                f.failures.attempt(() -> eq(originalFont, f.onMain(() -> f.activity.getResources().getConfiguration().fontScale)));
                f.failures.attempt(() -> eq(originalTheme, new Preferences(f.target).getThemeMode()));
                f.failures.attempt(() -> eq(delegateMode(originalTheme), (Integer) AppCompatDelegate.getDefaultNightMode()));
                f.failures.attempt(() -> eq(originalNight, (Integer) delegateMode(originalTheme)));
            } else {
                f.failures.add(new IllegalStateException("configuration restore withheld under live actors; fail closed"));
            }
            f.failures.attempt(this::current);
            try { f.main(() -> runner.afterActivityCreate = savedHook); }
            catch (Throwable failure) { f.failures.add(failure); }
        }

        // ---------------------------------------------------------------- real Activity identity

        PreferredIpActivity attached() {
            try {
                return f.onMain(() -> {
                    Object listener = field(f.controller, "listener");
                    return listener instanceof PreferredIpActivity ? (PreferredIpActivity) listener : null;
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        PreferredIpActivity awaitLive(LiveMatch match, String why) throws Exception {
            final PreferredIpActivity[] found = new PreferredIpActivity[1];
            until(() -> f.onMain(() -> {
                Object listener = field(f.controller, "listener");
                if (!(listener instanceof PreferredIpActivity)) return false;
                PreferredIpActivity live = (PreferredIpActivity) listener;
                if (live.isDestroyed() || !match.matches(live)) return false;
                found[0] = live; return true;
            }), why);
            f.main(() -> f.activity = found[0]);
            return found[0];
        }

        PreferredIpActivity current() throws Exception { return awaitLive(activity -> true, "real foreground PreferredIpActivity attach"); }

        int instanceCount() throws Exception {
            return f.onMain(() -> {
                Set<Activity> ids = new HashSet<>();
                for (Activity activity : created) if (activity instanceof PreferredIpActivity) ids.add(activity);
                return ids.size();
            });
        }

        void rememberOrientation() throws Exception {
            PreferredIpActivity live = current();
            originalOrientation = f.onMain(() -> live.getResources().getConfiguration().orientation);
            originalRequested = f.onMain(live::getRequestedOrientation);
            orientationKnown = true;
        }

        PreferredIpActivity rotateTo(int orientation) throws Exception {
            final PreferredIpActivity old = current();
            int before = f.onMain(() -> old.getResources().getConfiguration().orientation);
            check(before != orientation, "rotation target differs from the real Configuration");
            orientationKnown = true; changedOrientation = true;
            int requested = orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            f.main(() -> old.setRequestedOrientation(requested));
            until(() -> f.onMain(old::isDestroyed), "ActivityThread destroyed the old instance for a real rotation");
            awaitLive(activity -> activity != old, "new rotated Activity instance attached");
            eq(orientation, f.onMain(() -> f.activity.getResources().getConfiguration().orientation));
            check(f.onMain(() -> field(f.activity, "controller")) == f.controller, "retained Controller across rotation");
            check(instanceCount() >= 2, "two distinct real Activity instances recorded");
            f.settle(); f.publish(); f.draw();
            return f.activity;
        }

        void restoreOrientation() throws Exception {
            PreferredIpActivity live = current();
            if (f.onMain(() -> live.getResources().getConfiguration().orientation) != originalOrientation) rotateTo(originalOrientation);
            live = current();
            if (f.onMain(live::getRequestedOrientation) != originalRequested) {
                f.main(() -> live.setRequestedOrientation(originalRequested));
                f.i.waitForIdleSync();
                ui.automation.waitForIdle(100, 2000);
                current();
            }
            until(() -> {
                PreferredIpActivity activity = attached();
                return activity != null && !activity.isDestroyed()
                    && f.onMain(() -> activity.getResources().getConfiguration().orientation) == originalOrientation;
            }, "restored rotation Configuration");
            eq(originalRequested, f.onMain(() -> f.activity.getRequestedOrientation()));
        }

        // ---------------------------------------------------------------- real nodes

        List<AccessibilityNodeInfo> allRoots() {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            for (AccessibilityWindowInfo window : ui.automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) roots.add(root);
            }
            if (roots.isEmpty()) {
                AccessibilityNodeInfo root = ui.automation.getRootInActiveWindow();
                if (root != null) roots.add(root);
            }
            return roots;
        }

        List<AccessibilityNodeInfo> freshRoots(Set<Integer> before) {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            for (AccessibilityWindowInfo window : ui.automation.getWindows()) {
                if (before.contains(window.getId())) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) roots.add(root);
            }
            return roots;
        }

        Set<Integer> windowIds() {
            Set<Integer> ids = new HashSet<>();
            for (AccessibilityWindowInfo window : ui.automation.getWindows()) ids.add(window.getId());
            return ids;
        }

        List<AccessibilityNodeInfo> collect(List<AccessibilityNodeInfo> roots, Match match) {
            List<AccessibilityNodeInfo> hits = new ArrayList<>();
            for (AccessibilityNodeInfo root : roots) walk(root, match, hits, 0);
            return hits;
        }

        AccessibilityNodeInfo findAny(Match match) {
            List<AccessibilityNodeInfo> roots = allRoots();
            try { return first(collect(roots, match)); } finally { recycle(roots); }
        }

        AccessibilityNodeInfo findFresh(Set<Integer> before, Match match) {
            List<AccessibilityNodeInfo> roots = freshRoots(before);
            try { return first(collect(roots, match)); } finally { recycle(roots); }
        }

        AccessibilityNodeInfo requireNode(Match match, String what) throws Exception {
            final AccessibilityNodeInfo[] node = new AccessibilityNodeInfo[1];
            until(() -> { node[0] = findAny(match); return node[0] != null; }, "real native node: " + what);
            return node[0];
        }

        void clickNode(AccessibilityNodeInfo node, String what) throws Exception {
            AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
            try {
                for (int level = 0; level < 5; level++) {
                    if (target.isClickable() || (target.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0) {
                        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK), "native click " + what);
                        f.i.waitForIdleSync();
                        ui.automation.waitForIdle(100, 2000);
                        return;
                    }
                    AccessibilityNodeInfo parent = target.getParent();
                    check(parent != null, "native clickable ancestor for " + what);
                    target.recycle(); target = parent;
                }
                throw new AssertionError("native clickable ancestor for " + what);
            } finally { target.recycle(); }
        }

        static boolean containsText(AccessibilityNodeInfo node, String value, int depth) {
            if (node == null || depth > 6) return false;
            CharSequence text = node.getText(), description = node.getContentDescription();
            if ((text != null && value.contentEquals(text)) || (description != null && value.contentEquals(description))) return true;
            for (int n = 0; n < node.getChildCount(); n++) {
                AccessibilityNodeInfo child = node.getChild(n);
                if (child == null) continue;
                try { if (containsText(child, value, depth + 1)) return true; } finally { child.recycle(); }
            }
            return false;
        }

        static boolean labelledBy(AccessibilityNodeInfo node, String title) {
            AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
            try {
                for (int level = 0; level < 5; level++) {
                    if (containsText(current, title, 0)) return true;
                    AccessibilityNodeInfo parent = current.getParent();
                    if (parent == null) return false;
                    current.recycle(); current = parent;
                }
                return false;
            } finally { current.recycle(); }
        }

        // ---------------------------------------------------------------- real night mode via Settings Spinner

        void theme(int mode) throws Exception {
            if (external) returnApp();
            current();
            if (new Preferences(f.target).getThemeMode() == mode) { verifyTheme(mode); return; }
            themeTouched = true;
            f.cover = f.i.startActivitySync(new Intent(f.target, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until(() -> f.onMain(() -> field(f.controller, "listener") == null), "SettingsActivity covers the real page");
            String spinner = f.onMain(() -> f.cover.getResources().getResourceName(R.id.spinner_theme_mode));
            AccessibilityNodeInfo control = requireNode(node -> spinner.equals(node.getViewIdResourceName()), "Settings theme Spinner");
            Set<Integer> before = windowIds();
            try { clickNode(control, "Settings theme Spinner"); } finally { control.recycle(); }
            final String label = f.onMain(() -> f.cover.getString(labelRes(mode)));
            final AccessibilityNodeInfo[] option = new AccessibilityNodeInfo[1];
            until(() -> {
                option[0] = findFresh(before, node -> label.contentEquals(text(node)));
                if (option[0] == null) option[0] = findAny(node -> label.contentEquals(text(node)));
                return option[0] != null;
            }, "real Spinner popup option " + label);
            try { clickNode(option[0], "theme option " + label); } finally { option[0].recycle(); }
            until(() -> new Preferences(f.target).getThemeMode() == mode, "native Spinner committed the real theme preference");
            finishSettings();
            returnApp();
            finishSettings();
            int expected = night(mode);
            if (expected != -1) {
                awaitLive(activity -> (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == expected,
                    "applied night Configuration on the live app Activity");
            } else current();
            f.settle(); f.publish(); f.draw();
            verifyTheme(mode);
        }

        void verifyTheme(int mode) throws Exception {
            eq(mode, new Preferences(f.target).getThemeMode());
            int expected = night(mode);
            if (expected != -1) {
                eq(expected, f.onMain(() -> f.activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
            }
            eq(delegateMode(mode), (Integer) AppCompatDelegate.getDefaultNightMode());
        }

        void finishSettings() throws Exception {
            final SettingsActivity[] live = new SettingsActivity[1];
            for (int attempt = 0; attempt < 100; attempt++) {
                f.main(() -> {
                    live[0] = null;
                    for (int n = created.size() - 1; n >= 0; n--) {
                        Activity activity = created.get(n);
                        if (activity instanceof SettingsActivity && !activity.isDestroyed() && !activity.isFinishing()) {
                            live[0] = (SettingsActivity) activity; return;
                        }
                    }
                    if (f.cover instanceof SettingsActivity && !f.cover.isDestroyed() && !f.cover.isFinishing()) live[0] = (SettingsActivity) f.cover;
                });
                if (live[0] != null) break;
                if (attempt > 0 && appForeground()) return;
                ui.automation.waitForIdle(20, 300);
                f.i.waitForIdleSync();
            }
            if (live[0] == null) return;
            f.main(live[0]::finish);
            until(() -> f.onMain(live[0]::isDestroyed), "SettingsActivity real onDestroy");
        }

        String toolbarResource() throws Exception {
            if (toolbarResource == null) toolbarResource = f.onMain(() -> f.activity.getResources().getResourceName(R.id.preferred_toolbar));
            return toolbarResource;
        }

        /** The real app page is active (not the covered Settings window or a leftover popup). */
        boolean appForeground() throws Exception {
            final String resource = toolbarResource();
            AccessibilityNodeInfo root = ui.automation.getRootInActiveWindow();
            if (root == null) return false;
            try {
                List<AccessibilityNodeInfo> hits = collect(Collections.singletonList(root), node -> resource.equals(node.getViewIdResourceName()));
                recycle(hits);
                return !hits.isEmpty();
            } finally { root.recycle(); }
        }

        void returnApp() throws Exception {
            for (int n = 0; n < 8; n++) {
                if (appForeground()) {
                    external = false;
                    f.i.waitForIdleSync();
                    ui.automation.waitForIdle(100, 10000);
                    f.i.waitForIdleSync();
                    current();
                    return;
                }
                check(ui.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), "real system Back to the app");
                ui.automation.waitForIdle(100, 10000);
                f.i.waitForIdleSync();
            }
            throw new AssertionError("real system settings did not return to the app page");
        }

        // ---------------------------------------------------------------- real system font page

        String fontTitle() throws Exception {
            Context settings = f.target.createPackageContext("com.android.settings", 0);
            for (String name : new String[]{"title_font_size", "font_size_title", "font_size"}) {
                int id = settings.getResources().getIdentifier(name, "string", "com.android.settings");
                if (id == 0) continue;
                String value = settings.getResources().getString(id);
                if (value != null && !value.trim().isEmpty()) return value;
            }
            return null;
        }

        /** The actual API24 font-size control: either the single-choice size list or a discrete slider. */
        static final class FontControl implements AutoCloseable {
            final List<AccessibilityNodeInfo> options;
            final List<String> labels;
            final int checked;
            final AccessibilityNodeInfo slider;
            final AccessibilityNodeInfo.RangeInfo range;
            FontControl(List<AccessibilityNodeInfo> options, List<String> labels, int checked,
                        AccessibilityNodeInfo slider, AccessibilityNodeInfo.RangeInfo range) {
                this.options = options; this.labels = labels; this.checked = checked; this.slider = slider; this.range = range;
            }
            public void close() {
                if (options != null) recycle(options);
                if (slider != null) slider.recycle();
            }
        }

        FontControl openFontControl() throws Exception {
            if (external) returnApp();
            final String title = fontTitle();
            check(title != null, "API24 AOSP Settings exposes the real font-size title resource");
            external = true;
            f.main(() -> {
                try { f.activity.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); }
                catch (ActivityNotFoundException missing) { throw new AssertionError("API24 native display settings unavailable", missing); }
            });
            ui.automation.waitForIdle(100, 10000);
            f.i.waitForIdleSync();
            Set<Integer> before = windowIds();
            AccessibilityNodeInfo entry = null;
            for (int n = 0; n < 12 && entry == null; n++) {
                entry = findAny(node -> settings(node) && title.contentEquals(text(node)));
                if (entry != null) break;
                AccessibilityNodeInfo scroll = requireNode(node -> settings(node) && node.isScrollable(), "display settings list");
                try { check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "native display settings scroll"); }
                finally { scroll.recycle(); }
                ui.automation.waitForIdle(100, 2000);
                f.i.waitForIdleSync();
            }
            check(entry != null, "real API24 font-size preference on the display settings page");
            try { clickNode(entry, "font-size preference"); } finally { entry.recycle(); }
            ui.automation.waitForIdle(100, 2000);
            f.i.waitForIdleSync();
            final FontControl[] found = new FontControl[1];
            until(() -> { found[0] = detectFontControl(title, before); return found[0] != null; },
                "real API24 font-size options or slider after the native preference click");
            return found[0];
        }

        FontControl detectFontControl(String title, Set<Integer> before) {
            List<AccessibilityNodeInfo> roots = freshRoots(before);
            try {
                for (AccessibilityNodeInfo root : roots) {
                    if (!containsText(root, title, 0)) continue;
                    FontControl control = controlIn(root, false, title);
                    if (control != null) return control;
                }
            } finally { recycle(roots); }
            List<AccessibilityNodeInfo> all = allRoots();
            try {
                for (AccessibilityNodeInfo root : all) {
                    if (!containsText(root, title, 0)) continue;
                    FontControl control = controlIn(root, true, title);
                    if (control != null) return control;
                }
            } finally { recycle(all); }
            return null;
        }

        FontControl controlIn(AccessibilityNodeInfo root, boolean sameWindow, String title) {
            if (!sameWindow) {
                List<AccessibilityNodeInfo> options = collect(Collections.singletonList(root),
                    node -> node.isCheckable() && !text(node).isEmpty());
                List<String> labels = new ArrayList<>();
                for (AccessibilityNodeInfo node : options) labels.add(text(node));
                if (options.size() >= 2) {
                    List<Integer> order = new ArrayList<>();
                    for (int n = 0; n < options.size(); n++) order.add(n);
                    Collections.sort(order, new Comparator<Integer>() {
                        public int compare(Integer left, Integer right) {
                            Rect a = new Rect(), b = new Rect();
                            options.get(left).getBoundsInScreen(a); options.get(right).getBoundsInScreen(b);
                            return Integer.compare(a.top, b.top);
                        }
                    });
                    List<AccessibilityNodeInfo> sorted = new ArrayList<>();
                    List<String> sortedLabels = new ArrayList<>();
                    int checked = -1;
                    for (int n = 0; n < order.size(); n++) {
                        AccessibilityNodeInfo node = options.get(order.get(n));
                        sorted.add(node); sortedLabels.add(labels.get(order.get(n)));
                        if (checked < 0 && node.isChecked()) checked = n;
                    }
                    return new FontControl(sorted, sortedLabels, checked, null, null);
                }
                recycle(options);
            }
            List<AccessibilityNodeInfo> sliders = collect(Collections.singletonList(root),
                node -> "android.widget.SeekBar".contentEquals(node.getClassName()) && node.getRangeInfo() != null
                    && (!sameWindow || labelledBy(node, title)));
            for (int n = 0; n < sliders.size(); n++) {
                AccessibilityNodeInfo node = sliders.get(n);
                if (node.getRangeInfo() != null) {
                    sliders.remove(n);
                    recycle(sliders);
                    return new FontControl(null, null, -1, node, node.getRangeInfo());
                }
            }
            recycle(sliders);
            return null;
        }

        void rememberFont() throws Exception {
            FontControl control = openFontControl();
            try {
                if (control.options != null) {
                    check(control.checked >= 0, "the real system font list marks its current size");
                    restoreOption = control.checked;
                    restoreLabel = control.labels.get(control.checked);
                    restoreCount = control.options.size();
                } else {
                    originalProgress = control.range.getCurrent();
                }
                fontKnown = true;
            } finally { control.close(); }
            returnApp();
            eq(originalFont, Settings.System.getFloat(f.target.getContentResolver(), Settings.System.FONT_SCALE, 1f));
            eq(originalFont, f.onMain(() -> f.activity.getResources().getConfiguration().fontScale));
        }

        float driveFont(int choice) throws Exception {
            FontControl control = openFontControl();
            try {
                if (control.options != null) {
                    eq(restoreCount, control.options.size());
                    int index = choice == LARGEST ? control.options.size() - 1 : restoreOption;
                    check(index >= 0 && index < control.options.size(), "real system font option index");
                    if (choice == LARGEST) {
                        check(control.checked != index, "real largest system font differs from the current size");
                    } else {
                        eq(restoreLabel, control.labels.get(index));
                    }
                    clickNode(control.options.get(index), "system font option " + control.labels.get(index));
                } else {
                    float value = choice == LARGEST ? control.range.getMax() : originalProgress;
                    check(value >= control.range.getMin() && value <= control.range.getMax(), "real system font slider range");
                    if (choice == LARGEST) check(control.range.getCurrent() != value, "real largest system font differs from the current size");
                    setProgress(control, value);
                }
            } finally { control.close(); }
            returnApp();
            float system = Settings.System.getFloat(f.target.getContentResolver(), Settings.System.FONT_SCALE, 1f);
            awaitLive(activity -> activity.getResources().getConfiguration().fontScale == system,
                "real app Configuration.fontScale after the system font page");
            f.settle(); f.publish(); f.draw();
            eq(system, f.onMain(() -> f.activity.getResources().getConfiguration().fontScale));
            return system;
        }

        void setProgress(FontControl control, float value) throws Exception {
            boolean supported = false;
            for (AccessibilityNodeInfo.AccessibilityAction action : control.slider.getActionList()) {
                if (action.getId() == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) supported = true;
            }
            check(supported, "API24 system font slider supports ACTION_SET_PROGRESS");
            if (control.slider.getRangeInfo().getCurrent() == value) return;
            Bundle arguments = new Bundle();
            arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
            check(control.slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments),
                "real system font slider action");
            f.i.waitForIdleSync();
            ui.automation.waitForIdle(100, 2000);
        }

        float font(boolean large) throws Exception {
            if (!fontKnown) rememberFont();
            if (large) {
                float scale = driveFont(LARGEST);
                check(scale > originalFont, "real system font page enlarged the actual Configuration.fontScale");
                return scale;
            }
            float scale = driveFont(RESTORE);
            eq(originalFont, scale);
            return scale;
        }
    }

    // ---------------------------------------------------------------- real scan helper

    static void scanAll(Fixture f) throws Exception {
        check(f.controller == null, "seed candidates before launch");
        JSONArray candidates = new JSONArray().put(candidate(X)).put(candidate(Y));
        for (int n = 1; n <= MATRIX_ROWS - 3; n++) candidates.put(candidate("104.16.2." + n + ":443"));
        candidates.put(candidate(V6));
        f.store.update(root -> { root.put("candidates", candidates); root.getJSONArray("sources").getJSONObject(0).put("candidates", candidates); });
        f.backend.holdProbeAddress = V6;
        f.launch(); f.tab(1); header(f); f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task last;
        do { last = f.backend.next("probe"); } while (PreferredIpPresentation.find(last.request.getJSONArray("candidates"), V6) == null);
        await(last.polled, "matrix final batch remains admitted");
        last.replies.add(f.backend.snapshot(last, "running", false, true, 0));
        until(() -> { f.publish(); return f.state().completed == MATRIX_ROWS && f.state().busy; }, "all matrix rows before matching drain");
        header(f); f.click(f.view(R.id.preferred_primary)); await(last.cancelled, "native matrix Pause");
        last.replies.add(f.backend.snapshot(last, "cancelled", true, true, 0));
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == MATRIX_ROWS; }, "matrix matching drain");
        f.backend.holdProbeAddress = ""; f.stopCycle(); f.actorDrain();
    }

    // ---------------------------------------------------------------- G4 cases

    static void testNativeRotationRetainsSessionAndPartialQueue(Fixture f) throws Exception {
        ConfigurationGuard config = new ConfigurationGuard(f);
        check(f.controller == null, "seed candidates before launch");
        JSONArray candidates = new JSONArray().put(candidate(X)).put(candidate(Y));
        for (int n = 1; n <= 10; n++) if (!REMAINING.equals("104.16.2." + n + ":443")) candidates.put(candidate("104.16.2." + n + ":443"));
        candidates.put(candidate(REMAINING)).put(candidate(V6));
        final int rows = candidates.length();
        f.store.update(root -> { root.put("candidates", candidates); root.getJSONArray("sources").getJSONObject(0).put("candidates", candidates); });
        f.backend.holdProbe = true;
        f.launch(); f.tab(1); header(f);
        config.rememberOrientation(); // after the real Activity exists, before any rotation
        final String inputResource = f.onMain(() -> f.activity.getResources().getResourceName(R.id.preferred_concurrency_input));
        AccessibilityNodeInfo input = config.requireNode(node -> inputResource.equals(node.getViewIdResourceName()), "concurrency input");
        try {
            Bundle value = new Bundle();
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "16");
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "native concurrency field");
        } finally { input.recycle(); }
        f.settle(); f.publish();
        f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task task = f.backend.next("probe"); await(task.polled, "rotation poll blocked");
        JSONObject partial = f.backend.snapshot(task, "running", false, false, 0), full = f.backend.snapshot(task, "running", false, true, 0);
        for (int n = 0; n < rows; n++) {
            JSONObject row = full.getJSONArray("results").getJSONObject(n);
            if (!REMAINING.equals(row.getString("address"))) partial.getJSONArray("results").put(n, row);
        }
        partial.put("completed", rows - 1);
        task.replies.add(partial); task.awaitPoll(2); f.settle(); f.publish();
        eq(rows - 1, f.state().completed);
        select(f, X, true);
        f.show(V6); f.click(f.widget(V6, R.id.preferred_row_heading)); f.publish(); f.draw();
        check(f.controller.expanded.contains(V6), "IPv6 fold before rotation");
        JSONObject pool = f.store.read();
        final PreferredIpActivity old = f.activity;
        int original = f.onMain(() -> old.getResources().getConfiguration().orientation);
        int target = original == Configuration.ORIENTATION_PORTRAIT ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        config.rotateTo(target);
        await(task.cancelled, "real rotation onStop Cancel while the poll is blocked");
        check(old.isDestroyed() && f.activity != old, "ActivityThread recreation, not a lifecycle callback simulation");
        task.replies.add(new JSONObject(partial.toString()).put("state", "cancelled"));
        task.awaitPoll(3); f.settle(); f.publish();
        check(f.state().busy && f.state().paused, "cancelled without worker_done keeps the network slot");
        eq(2L, f.backend.startAttempts.get());
        task.replies.add(new JSONObject(partial.toString()).put("state", "cancelled").put("worker_done", true));
        until(() -> { f.publish(); return !f.state().busy; }, "matching rotated worker drain");
        eq(rows - 1, f.state().completed);
        eq(2L, f.backend.startAttempts.get()); // matching worker_done never auto-starts a new task
        check(f.controller.expanded.contains(V6), "fold retained by address in the new instance");
        for (String key : new String[]{"selected", "validations", "enabled", "selection_version"})
            eq(pool.get(key).toString(), f.store.read().get(key).toString());
        f.show(V6); eq(View.VISIBLE, f.onMain(() -> f.rowWidget(V6, R.id.preferred_row_detail).getVisibility()));
        check(f.rowText(V6).replace("\u200b", "").contains(V6), "rotation keeps the full IPv6 address and port");
        f.show(X); eq(true, f.checked(X));
        f.backend.holdProbe = false; header(f); f.click(f.view(R.id.preferred_primary));
        Task continued = f.backend.next("probe");
        JSONArray resumed = continued.request.getJSONArray("candidates");
        eq(1, resumed.length()); eq(REMAINING, resumed.getJSONObject(0).getString("address"));
        eq(task.sessionId, continued.sessionId); eq(task.roundId, continued.roundId);
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == rows; }, "manual Continue completes only the remaining IP");
        f.actorDrain();
        f.show(X); eq(true, f.checked(X));
    }

    static void testLightNormalFontNativeLayout(Fixture f) throws Exception { layoutMatrix(f, Preferences.THEME_LIGHT, false); }
    static void testDarkNormalFontNativeLayout(Fixture f) throws Exception { layoutMatrix(f, Preferences.THEME_DARK, false); }
    static void testLightLargeFontNativeLayout(Fixture f) throws Exception { layoutMatrix(f, Preferences.THEME_LIGHT, true); }
    static void testDarkLargeFontNativeLayout(Fixture f) throws Exception { layoutMatrix(f, Preferences.THEME_DARK, true); }

    static void layoutMatrix(Fixture f, int theme, boolean large) throws Exception {
        ConfigurationGuard config = new ConfigurationGuard(f);
        scanAll(f);
        config.rememberOrientation();
        select(f, X, true);
        f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.publish(); f.draw();
        check(f.controller.expanded.contains(X), "native fold before the configuration change");
        long version = f.store.read().getLong("selection_version");
        config.theme(theme);
        float scale = config.font(large);
        eq(scale, f.onMain(() -> f.activity.getResources().getConfiguration().fontScale));
        eq(theme == Preferences.THEME_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO,
            f.onMain(() -> f.activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
        // Selection and fold are address identity, so they must survive the real recreation.
        f.show(X); eq(true, f.checked(X)); check(f.controller.expanded.contains(X), "fold retained by address");
        f.show(V6);
        f.onMain(() -> {
            View row = f.rowWidget(V6, 0);
            View heading = row.findViewById(R.id.preferred_row_heading), choice = row.findViewById(R.id.preferred_choice);
            float density = f.activity.getResources().getDisplayMetrics().density;
            check(choice.getWidth() >= 48 * density && choice.getHeight() >= 48 * density, "48dp choice under theme/font");
            check(heading.getHeight() >= 48 * density, "48dp heading under theme/font");
            check(textIn(row).replace("\u200b", "").contains(V6), "IPv6 address and port intact under theme/font");
            return null;
        });
        AtomicLong scrolled = new AtomicLong(), recycled = new AtomicLong();
        RecyclerView.OnScrollListener observation = new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recycler, int dx, int dy) { scrolled.addAndGet(Math.abs(dy)); }
        };
        RecyclerView.RecyclerListener recycling = holder -> recycled.incrementAndGet();
        f.main(() -> { f.list().addOnScrollListener(observation); f.list().addRecyclerListener(recycling); });
        f.resources.add(() -> f.main(() -> { f.list().removeOnScrollListener(observation); f.list().removeRecyclerListener(recycling); }));
        middleByGesture(f);
        check(scrolled.get() > 0, "real vertical native motion after the configuration change");
        Anchor anchor = new Anchor(f);
        String secondary = nextVisible(f, anchor);
        f.click(f.widget(secondary, R.id.preferred_row_heading)); f.publish(); f.draw();
        anchor.assertPreserved(f); // Exact production anchor contract; no margin compensation or tolerance.
        assertWidgets(f);
        for (int n = 0; n < 16; n++) {
            final boolean[] visible = {false};
            f.main(() -> { for (int c = 0; c < f.list().getChildCount(); c++) if (X.equals(f.list().getChildAt(c).getTag())) visible[0] = true; });
            if (visible[0]) break;
            verticalGesture(f, false);
            assertWidgets(f);
        }
        eq(true, f.checked(X));
        check(recycled.get() > 0, "real recycle during native scrolling");
        check(f.controller.expanded.contains(V6), "scrolling keeps the IPv6 fold");
        eq(version, f.store.read().getLong("selection_version"));
    }

    // ---------------------------------------------------------------- G6 remaining cases

    static JSONObject partialX(Fixture f, Task task) throws Exception {
        JSONObject partial = f.backend.snapshot(task, "running", false, false, 0);
        partial.getJSONArray("results").put(0, f.backend.snapshot(task, "running", false, true, 0).getJSONArray("results").getJSONObject(0));
        partial.put("completed", 1);
        return partial;
    }

    static void testPollAndStoreBlockedCancelRemainsIndependent(Fixture f) throws Exception {
        f.backend.holdProbe = true; f.launch(); f.tab(1); header(f);
        f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task task = f.backend.next("probe"); await(task.polled, "real poll entered");
        JSONObject partial = partialX(f, task);
        task.replies.add(partial); task.awaitPoll(2); f.settle(); f.publish();
        eq(1, f.state().completed);
        f.stopCycle();
        f.show(X);
        header(f); // layout/header settled before the Store monitor is held; no layout inside the blocked window.
        File file = new File(f.dir, "preferred-ip-v1.json");
        byte[] before = bytes(file);
        long version = f.store.read().getLong("selection_version");
        synchronized (storeMonitor()) {
            View choice = f.widget(X, R.id.preferred_choice);
            f.main(() -> { check(choice.performClick(), "native selection while the poll is blocked"); eq(true, ((CompoundButton) choice).isChecked()); });
            fence(f.control);
            eq(1, field(f.controller, "selectionPending"));
            check(Arrays.equals(before, bytes(file)), "selection transaction is blocked on the real Store monitor");
            f.click(f.view(R.id.preferred_primary));
            await(task.cancelled, "independent Cancel while both the poll and the Store are blocked");
            fence(f.control);
            check((Boolean) field(f.controller, "stopRequested"), "control path responded while the selection was still queued");
            eq(1, field(f.controller, "selectionPending"));
            check(Arrays.equals(before, bytes(file)), "no selection bytes were committed inside the blocked window");
            eq(2L, f.backend.startAttempts.get());
        }
        f.settle(); f.publish(); f.draw();
        eq(version + 1, f.store.read().getLong("selection_version"));
        check(selected(f.store.read(), X), "short selection transaction commits after the Store is released while the poll is still held");
        task.replies.add(new JSONObject(partial.toString()).put("state", "cancelled"));
        task.awaitPoll(3); f.settle(); f.publish();
        check(f.state().busy && f.state().paused, "cancelled without worker_done keeps the slot");
        task.replies.add(new JSONObject(partial.toString()).put("state", "cancelled").put("worker_done", true));
        until(() -> { f.publish(); return !f.state().busy; }, "simultaneous blockers drain the matching worker");
        eq(1, f.state().completed);
        f.backend.holdProbe = false; header(f); f.click(f.view(R.id.preferred_primary));
        Task next = f.backend.next("probe");
        eq(2, next.request.getJSONArray("candidates").length());
        check(PreferredIpPresentation.find(next.request.getJSONArray("candidates"), X) == null, "completed X is not retried");
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == 3; }, "manual Continue completes the remaining IPs");
        f.show(X); eq(true, f.checked(X));
    }

    static void testAdmittedStartReturnDoesNotBlockSelection(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true);
        eq(1L, f.store.read().getLong("selection_version"));
        eq(false, f.state().metered);
        f.backend.holdDownload = true; f.backend.startReturn = f.gates.create();
        f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.publish(); f.draw();
        f.click(f.rowWidget(X, R.id.preferred_row_download));
        Task task = f.backend.next("download");
        f.backend.startReturn.awaitEnter();
        f.publish();
        eq("", field(f.controller, "taskId"));
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        select(f, Y, true);
        eq(2L, f.store.read().getLong("selection_version"));
        check(selected(f.store.read(), X) && selected(f.store.read(), Y), "real selections persist while Start is admitted");
        check(f.store.read().getBoolean("enabled"), "same-target validation finishes on the short actor");
        eq(true, f.checked(Y));
        eq("", field(f.controller, "taskId"));
        eq(1L, task.cancelled.getCount()); // no cancel is possible before the admitted ID returns
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        f.show(X); f.click(f.rowWidget(X, R.id.preferred_row_download));
        fence(f.control);
        check((Boolean) field(f.controller, "stopRequested"), "native Cancel intent recorded before the returned ID");
        eq(1L, task.cancelled.getCount());
        f.backend.startReturn.close();
        await(task.cancelled, "Cancel is reissued once the admitted ID returns");
        await(task.polled, "download poll entered after the delayed return");
        task.replies.add(f.backend.snapshot(task, "cancelled", false, false, 3 * 1048576L));
        task.awaitPoll(2); f.settle();
        eq(52428800L, f.store.read().getJSONObject("download_ledger").getLong("reserved"));
        task.replies.add(f.backend.snapshot(task, "cancelled", true, false, 3 * 1048576L));
        until(() -> { f.publish(); return !f.state().busy; }, "admitted download matching exit and accounting");
        JSONObject ledger = f.store.read().getJSONObject("download_ledger");
        eq(3 * 1048576L, ledger.getLong("used")); eq(0L, ledger.getLong("reserved"));
        eq(2L, f.store.read().getLong("selection_version"));
        f.show(X); eq(true, f.checked(X)); f.show(Y); eq(true, f.checked(Y));
    }
}
