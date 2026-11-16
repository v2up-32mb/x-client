package com.x.client.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.x.client.app.PreferredIpV2Test.*;

/** Stage A only: normal native recycling versus deliberately held/late renderer windows.
 * Network rows always enter through Backend.poll; no Row/State/proof/holder flag injection. */
final class PreferredIpUiRemainingTest {
    static final String[] NAMES = {
        "testNativeVerticalRecycleRestoresSelectionAndFold",
        "testHeldRemovedTouchNoPositionIdentity", "testHeldRemovedAccessibilityNoPositionIdentity",
        "testSameRowsAddReadFailureHasNoDiff", "testSameRowsRemoveReadFailureHasNoDiff",
        "testQueuedContentDiffUsesLatestSelection", "testCalculatedContentDiffUsesLatestSelection",
        "testQueuedReorderDiffUsesLatestSelection", "testCalculatedReorderDiffUsesLatestSelection",
        "testQueuedContentDiffAfterReadFailure", "testCalculatedContentDiffAfterReadFailure",
        "testNativeTouchUpDefersMovesFoldAnchor", "testNativeTouchCancelDefersMovesFoldAnchor",
        "testNativeAccessibilityFocusDefersMovesAndClicks"
    };
    static void run(android.app.Instrumentation i, PreferredIpFeedbackTest.Reporter reporter) throws Exception {
        Case[] cases = { PreferredIpUiRemainingTest::testNativeVerticalRecycleRestoresSelectionAndFold,
            PreferredIpUiRemainingTest::testHeldRemovedTouchNoPositionIdentity, PreferredIpUiRemainingTest::testHeldRemovedAccessibilityNoPositionIdentity,
            PreferredIpUiRemainingTest::testSameRowsAddReadFailureHasNoDiff, PreferredIpUiRemainingTest::testSameRowsRemoveReadFailureHasNoDiff,
            PreferredIpUiRemainingTest::testQueuedContentDiffUsesLatestSelection, PreferredIpUiRemainingTest::testCalculatedContentDiffUsesLatestSelection,
            PreferredIpUiRemainingTest::testQueuedReorderDiffUsesLatestSelection, PreferredIpUiRemainingTest::testCalculatedReorderDiffUsesLatestSelection,
            PreferredIpUiRemainingTest::testQueuedContentDiffAfterReadFailure, PreferredIpUiRemainingTest::testCalculatedContentDiffAfterReadFailure,
            PreferredIpUiRemainingTest::testNativeTouchUpDefersMovesFoldAnchor, PreferredIpUiRemainingTest::testNativeTouchCancelDefersMovesFoldAnchor,
            PreferredIpUiRemainingTest::testNativeAccessibilityFocusDefersMovesAndClicks };
        eq(NAMES.length, cases.length);
        for (int n = 0; n < cases.length; n++) {
            try (Fixture f = new Fixture(i)) { cases[n].run(f); }
            reporter.passed(PreferredIpFeedbackTest.TOTAL + PreferredIpV2Test.NAMES.length + PreferredIpV2ContinuationTest.NAMES.length + n + 1, NAMES[n]);
        }
    }
    @SuppressWarnings("unchecked") static List<PreferredIpController.Row> rows(Fixture f) throws Exception {
        return f.onMain(() -> (List<PreferredIpController.Row>) field(f.list().getAdapter(), "rows")); // Read-only identity, needed to identify actual old Diff apply.
    }
    static List<String> order(List<PreferredIpController.Row> rows) {
        List<String> addresses = new ArrayList<>(); for (PreferredIpController.Row row : rows) addresses.add(row.address); return addresses;
    }
    static PreferredIpController.Row row(List<PreferredIpController.Row> rows, String address) {
        for (PreferredIpController.Row row : rows) if (address.equals(row.address)) return row;
        throw new AssertionError("missing actual Row " + address);
    }
    static void ioWithoutMain(Fixture f) throws Exception {
        for (int n = 0; n < 3; n++) { fence(f.control); fence(f.selection); fence(f.control); }
        eq(0, f.control.submit(() -> field(f.controller, "selectionPending")).get(10, TimeUnit.SECONDS));
    }
    static void publishWithoutMain(Fixture f) throws Exception {
        f.control.submit(() -> { try { invoke(f.controller, "publish", new Class<?>[0]); } catch (Exception e) { throw new AssertionError(e); } }).get(10, TimeUnit.SECONDS);
    }
    static void header(Fixture f) throws Exception {
        f.awaitAdapterIdle(); f.main(() -> f.list().scrollToPosition(0)); f.draw();
        check(f.onMain(() -> f.view(R.id.preferred_primary) != null && f.view(R.id.preferred_primary).isShown()), "native header available");
    }
    static void select(Fixture f, String address, boolean value) throws Exception {
        f.show(address); eq(!value, f.checked(address)); f.click(f.widget(address, R.id.preferred_choice));
        f.settle(); f.publish(); f.draw(); eq(value, f.checked(address)); eq(value, selected(f.store.read(), address));
    }
    static void assertWidgets(Fixture f) throws Exception {
        f.main(() -> {
            try {
                List<PreferredIpController.Row> model = rows(f); PreferredIpController.State state = f.state();
                Set<Long> ids = new HashSet<>();
                for (int n = 0; n < f.list().getChildCount(); n++) {
                    View view = f.list().getChildAt(n); RecyclerView.ViewHolder holder = f.list().getChildViewHolder(view);
                    int position = holder.getBindingAdapterPosition(); if (position <= 0 || position > model.size()) continue;
                    String address = model.get(position - 1).address;
                    eq(address, view.getTag()); eq(address, field(holder, "address"));
                    eq(address, view.findViewById(R.id.preferred_choice).getTag()); eq(address, view.findViewById(R.id.preferred_row_heading).getTag());
                    eq(selected(state.data, address), ((CompoundButton) view.findViewById(R.id.preferred_choice)).isChecked());
                    eq(f.list().getAdapter().getItemId(position), holder.getItemId()); check(ids.add(holder.getItemId()), "unique visible address stable ID");
                }
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
    static void awaitHeld(Fixture f, List<String> expected) throws Exception {
        ExecutorService differ = f.onMain(() -> (ExecutorService) field(f.activity, "differ"));
        until(() -> {
            fence(differ); f.main(() -> { });
            return f.onMain(() -> {
                if ((Boolean) field(f.list().getAdapter(), "diffRunning")) return false;
                List<PreferredIpController.Row> held = rows(f); if (!order(held).equals(expected)) return false;
                for (PreferredIpController.Row r : held) {
                    PreferredIpController.Row latest = null;
                    for (PreferredIpController.Row candidate : f.state().rows) if (r.address.equals(candidate.address)) latest = candidate;
                    if (latest != null && !sameRender(r, latest)) return false;
                    // A removed held row intentionally has no latest model; its widget/NO_POSITION is asserted separately.
                }
                return true;
            });
        }, "actual held order/content (not forced latest sorted order)");
    }
    static final int DENSE_COUNT = 31;
    static void denseSource(Fixture f) throws Exception {
        // Initial source fixture only, before a Controller exists; no selected/proof/State writes.
        check(f.controller == null, "seed candidates before launch");
        JSONArray candidates = new JSONArray().put(candidate(X)).put(candidate(Y));
        for (int n = 1; n <= 28; n++) candidates.put(candidate("104.16.2." + n + ":443")); candidates.put(candidate(V6));
        f.store.update(root -> { root.put("candidates", candidates); root.getJSONArray("sources").getJSONObject(0).put("candidates", candidates); });
    }
    static void middleByGesture(Fixture f) throws Exception {
        for (int n = 0; n < 24; n++) {
            int position = f.onMain(() -> ((LinearLayoutManager) f.list().getLayoutManager()).findFirstVisibleItemPosition());
            int count = rows(f).size();
            if (position > 5 && position < count - 5) return;
            verticalGesture(f, position <= 5);
        }
        throw new AssertionError("native gestures did not establish non-boundary anchor");
    }
    static void selectVisible(Fixture f, String address) throws Exception {
        eq(false, f.checked(address)); f.click(f.widget(address, R.id.preferred_choice)); f.settle(); f.publish(); f.draw();
        eq(true, f.checked(address)); check(selected(f.store.read(), address), "visible reference selection committed");
    }
    static String nextVisible(Fixture f, Anchor anchor) throws Exception {
        String address = rows(f).get(anchor.position).address;
        check(f.onMain(() -> f.rowWidget(address, R.id.preferred_row_heading).isShown()), "secondary actual visible heading"); return address;
    }
    static void denseComplete(Fixture f) throws Exception {
        denseSource(f); f.backend.holdProbeAddress = V6; f.launch(); f.tab(1); header(f); f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task last;
        do { last = f.backend.next("probe"); } while (PreferredIpPresentation.find(last.request.getJSONArray("candidates"), V6) == null);
        await(last.polled, "last dense batch remains admitted"); last.replies.add(f.backend.snapshot(last, "running", false, true, 0));
        until(() -> { f.publish(); return f.state().completed == DENSE_COUNT && f.state().busy; }, "all dense reference rows before final drain");
        header(f); f.click(f.view(R.id.preferred_primary)); await(last.cancelled, "native dense Pause"); last.replies.add(f.backend.snapshot(last, "cancelled", true, true, 0));
        until(() -> { f.publish(); return !f.state().busy; }, "dense matching drain"); f.backend.holdProbeAddress = ""; f.stopCycle(); f.actorDrain(); middleByGesture(f);
    }
    static final class Anchor {
        final String address; final int top, position, padding, margin, decoratedTop, height; final long id;
        Anchor(Fixture f) throws Exception {
            Object[] values = f.onMain(() -> {
                LinearLayoutManager layout = (LinearLayoutManager) f.list().getLayoutManager(); int p = layout.findFirstVisibleItemPosition();
                check(p > 0, "anchor must be an actual result address, not header");
                View v = layout.findViewByPosition(p); RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
                return new Object[]{v.getTag(), v.getTop(), f.list().getChildViewHolder(v).getItemId(), p, f.list().getPaddingTop(), lp.topMargin, layout.getDecoratedTop(v), v.getHeight()};
            });
            address = (String) values[0]; top = (Integer) values[1]; id = (Long) values[2]; position = (Integer) values[3]; padding = (Integer) values[4]; margin = (Integer) values[5]; decoratedTop = (Integer) values[6]; height = (Integer) values[7];
        }
        void assertPreserved(Fixture f) throws Exception {
            f.onMain(() -> {
                View v = f.rowWidget(address, 0); LinearLayoutManager layout = (LinearLayoutManager) f.list().getLayoutManager(); RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
                String diagnostic = "anchor " + address + " before[pos=" + position + ",top=" + top + ",padding=" + padding + ",margin=" + margin + ",decorated=" + decoratedTop + ",height=" + height + ",id=" + id
                    + "] after[pos=" + f.list().getChildViewHolder(v).getBindingAdapterPosition() + ",top=" + v.getTop() + ",padding=" + f.list().getPaddingTop() + ",margin=" + lp.topMargin + ",decorated=" + layout.getDecoratedTop(v) + ",height=" + v.getHeight() + ",id=" + f.list().getChildViewHolder(v).getItemId() + "]";
                check(top == v.getTop() && id == f.list().getChildViewHolder(v).getItemId() && height == v.getHeight(), diagnostic); return null;
            });
        }
    }
    /** Actual native motion events. A held pointer is not an artificial touched flag. */
    static final class Touch implements AutoCloseable {
        final Fixture f; final long down; final float x, y; boolean released;
        Touch(Fixture f) throws Exception {
            this.f = f; down = SystemClock.uptimeMillis(); x = 1; y = f.onMain(() -> f.list().getHeight() / 3f);
            f.resources.add(this); dispatch(MotionEvent.ACTION_DOWN, y); dispatch(MotionEvent.ACTION_MOVE, y - 1);
            check(f.onMain(() -> (Boolean) field(f.activity, "touched")), "production received native DOWN/MOVE");
        }
        void dispatch(int action, float at) {
            f.main(() -> {
                MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, at, 0); e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { f.list().dispatchTouchEvent(e); } finally { e.recycle(); }
            });
        }
        void end(int action) { if (!released) { dispatch(action, y - 1); released = true; } }
        public void close() { end(MotionEvent.ACTION_CANCEL); }
    }
    static void verticalGesture(Fixture f, boolean forward) throws Exception {
        float[] point = f.onMain(() -> {
            int[] at = new int[2]; f.list().getLocationOnScreen(at);
            return new float[]{at[0] + f.list().getWidth() / 2f, at[1] + f.list().getHeight() * (forward ? .8f : .2f), at[1] + f.list().getHeight() * (forward ? .2f : .8f)};
        });
        long down = SystemClock.uptimeMillis();
        try {
            for (int n = 0; n <= 12; n++) {
                int action = n == 0 ? MotionEvent.ACTION_DOWN : n == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1] + (point[2] - point[1]) * n / 12f, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { f.i.sendPointerSync(event); } finally { event.recycle(); }
            }
        } finally {
            MotionEvent cancel = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, point[0], point[2], 0);
            try { f.i.sendPointerSync(cancel); } finally { cancel.recycle(); }
        }
        until(() -> f.onMain(() -> f.list().getScrollState() == RecyclerView.SCROLL_STATE_IDLE), "native vertical gesture settled");
        f.draw();
    }
    /** Genuine UiAutomation nodes/events, never sendAccessibilityEvent or ordinary view focus. */
    static final class NativeAccess implements AutoCloseable {
        interface Match { boolean matches(AccessibilityNodeInfo node); }
        final Fixture f; final UiAutomation automation; final AccessibilityServiceInfo original; String focusedAddress = ""; int focusedId; boolean transientWindow;
        NativeAccess(Fixture f) throws Exception {
            this.f = f; automation = f.i.getUiAutomation(); original = automation.getServiceInfo(); check(original != null, "real UiAutomation service");
            f.resources.add(this); configure(false);
        }
        void configure(boolean focus) {
            Parcel parcel = Parcel.obtain(); AccessibilityServiceInfo copy;
            try { original.writeToParcel(parcel, 0); parcel.setDataPosition(0); copy = AccessibilityServiceInfo.CREATOR.createFromParcel(parcel); }
            finally { parcel.recycle(); }
            copy.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            if (focus) {
                check((copy.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) != 0, "native service can request exploration");
                copy.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
                copy.eventTypes |= AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;
                copy.notificationTimeout = 0;
            }
            automation.setServiceInfo(copy);
        }
        AccessibilityNodeInfo find(Match match) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow(); if (root == null) return null;
            List<AccessibilityNodeInfo> found = new ArrayList<>();
            try { walk(root, match, found); check(found.size() <= 1, "unique actual native node"); return found.isEmpty() ? null : AccessibilityNodeInfo.obtain(found.get(0)); }
            finally { root.recycle(); for (AccessibilityNodeInfo node : found) node.recycle(); }
        }
        void walk(AccessibilityNodeInfo node, Match match, List<AccessibilityNodeInfo> found) {
            if (node.isVisibleToUser() && match.matches(node)) found.add(AccessibilityNodeInfo.obtain(node));
            for (int n = 0; n < node.getChildCount(); n++) {
                AccessibilityNodeInfo child = node.getChild(n); if (child != null) try { walk(child, match, found); } finally { child.recycle(); }
            }
        }
        AccessibilityNodeInfo require(Match match) throws Exception {
            final AccessibilityNodeInfo[] node = new AccessibilityNodeInfo[1];
            until(() -> { node[0] = find(match); return node[0] != null; }, "real native node available"); return node[0];
        }
        void clickText(String label) throws Exception {
            Match text = n -> label.contentEquals(n.getText() == null ? "" : n.getText());
            AccessibilityNodeInfo node = find(n -> text.matches(n) && (n.isClickable() || (n.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0));
            if (node == null) node = require(text);
            try {
                for (int level = 0; !node.isClickable() && (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) == 0; level++) {
                    check(level < 4, "native text has an actionable ancestor"); AccessibilityNodeInfo parent = node.getParent();
                    check(parent != null, "native menu item ancestor");
                    if (!parent.isVisibleToUser()) { parent.recycle(); throw new AssertionError("native menu item ancestor not visible"); }
                    node.recycle(); node = parent;
                }
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "native ACTION_CLICK " + label);
            } finally { node.recycle(); }
            f.i.waitForIdleSync();
        }
        void clickId(String resource) throws Exception {
            AccessibilityNodeInfo node = require(n -> resource.equals(n.getViewIdResourceName()));
            try { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "native dialog/button click"); } finally { node.recycle(); }
            f.i.waitForIdleSync();
        }
        void scrollToText(String label) throws Exception {
            for (int n = 0; n < 12; n++) {
                AccessibilityNodeInfo target = find(node -> label.contentEquals(node.getText() == null ? "" : node.getText()) && node.isClickable());
                if (target != null) { target.recycle(); return; }
                AccessibilityNodeInfo scroll = require(node -> node.isScrollable() && "android.widget.ScrollView".contentEquals(node.getClassName()));
                try { check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "native source ScrollView scroll"); } finally { scroll.recycle(); }
                f.i.waitForIdleSync();
            }
            throw new AssertionError("source action never became visible: " + label);
        }
        void edit(String text) throws Exception {
            AccessibilityNodeInfo edit = require(AccessibilityNodeInfo::isEditable);
            Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            try { check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "native text entry"); } finally { edit.recycle(); }
        }
        AccessibilityNodeInfo rowNode(String address, int id) throws Exception {
            String resource = f.onMain(() -> f.activity.getResources().getResourceName(id));
            return require(n -> resource.equals(n.getViewIdResourceName()) && n.getContentDescription() != null && n.getContentDescription().toString().contains(address));
        }
        void rowClick(String address, int id) throws Exception {
            AccessibilityNodeInfo node = rowNode(address, id);
            try { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "actual row accessibility click"); } finally { node.recycle(); }
            f.settle(); f.publish();
        }
        void focus(String address, int id, boolean acquire) throws Exception {
            AccessibilityNodeInfo node = rowNode(address, id); int window = node.getWindowId();
            int type = acquire ? AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED : AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;
            try {
                AccessibilityEvent event = automation.executeAndWaitForEvent(() -> check(node.performAction(acquire ? AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS : AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS), "native focus action"),
                    e -> e.getEventType() == type && e.getWindowId() == window, 10000);
                try { eq(type, event.getEventType()); } finally { event.recycle(); }
            } finally { node.recycle(); }
            f.main(() -> { });
            eq(acquire, f.onMain(() -> f.rowWidget(address, id).isAccessibilityFocused()));
            eq(acquire ? address : "", f.onMain(() -> field(f.activity, "accessibilityAddress")));
            focusedAddress = acquire ? address : ""; focusedId = id;
        }
        public void close() {
            try {
                f.failures.attempt(() -> { if (!focusedAddress.isEmpty() && !f.onMain(() -> f.activity.isDestroyed())) focus(focusedAddress, focusedId, false); });
                f.failures.attempt(() -> { if (transientWindow) check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "native cleanup of unfinished popup/dialog"); });
            } finally { automation.setServiceInfo(original); }
        }
    }
    static void editSource(Fixture f, NativeAccess access, String text) throws Exception {
        int parses = f.backend.parseInputs.size();
        f.tab(0); access.scrollToText("编辑手动列表"); access.transientWindow = true; access.clickText("编辑手动列表"); access.edit(text); access.clickId("android:id/button1"); access.transientWindow = false;
        fence((ExecutorService) field(f.controller, "sourceIO")); fence(f.control); f.publish();
        check(f.state().parse != null, "production parsed preview from actual editor");
        eq(parses + 1, f.backend.parseInputs.size()); eq(text, f.backend.parseInputs.get(parses));
        String accept = f.onMain(() -> f.activity.getString(R.string.preferred_accept)); access.scrollToText(accept); access.clickText(accept);
        for (int n = 0; n < 3; n++) { fence(f.control); fence((ExecutorService) field(f.controller, "sourceIO")); fence(f.control); }
        f.publish(); check(f.state().parse == null, "production source confirmation settled");
    }
    static void restart(Fixture f, NativeAccess access, int count) throws Exception {
        String overflow = f.onMain(() -> f.activity.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description));
        AccessibilityNodeInfo button = access.require(n -> overflow.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()) && n.isClickable());
        access.transientWindow = true;
        try { check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "actual toolbar overflow"); } finally { button.recycle(); }
        access.clickText("重新开始测试"); access.clickId("android:id/button1"); access.transientWindow = false;
        Task prepare = f.backend.next("prepare"); eq(count, prepare.request.getJSONArray("candidates").length());
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == count; }, "native confirmed new scan complete");
        f.actorDrain();
        Task batch;
        while ((batch = f.backend.started.poll()) != null) {
            eq("probe", batch.request.getString("operation")); eq(prepare.sessionId, batch.sessionId); eq(prepare.roundId, batch.roundId);
            check(batch.consumedDone, "every automatically dispatched batch really exited");
        }
        f.tab(1); f.publish(); f.draw();
    }
    static void testNativeVerticalRecycleRestoresSelectionAndFold(Fixture f) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); NativeAccess access = new NativeAccess(f);
        StringBuilder text = new StringBuilder(X).append('\n').append(Y).append('\n');
        for (int n = 1; n <= 28; n++) text.append("104.16.2.").append(n).append(":443\n"); text.append(V6);
        editSource(f, access, text.toString()); restart(f, access, 31);
        f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw();
        long xId = f.stableId(X), version = f.store.read().getLong("selection_version");
        IdentityHashMap<RecyclerView.ViewHolder, String> recycled = new IdentityHashMap<>(); AtomicLong recycledX = new AtomicLong(), reused = new AtomicLong(), scroll = new AtomicLong();
        RecyclerView.RecyclerListener recycling = h -> { Object tag = h.itemView.getTag(); if (tag instanceof String) { recycled.put(h, (String) tag); if (X.equals(tag)) recycledX.incrementAndGet(); } };
        RecyclerView.OnChildAttachStateChangeListener attachment = new RecyclerView.OnChildAttachStateChangeListener() {
            public void onChildViewAttachedToWindow(View view) { RecyclerView.ViewHolder h = f.list().getChildViewHolder(view); String before = recycled.get(h); if (before != null && !before.equals(view.getTag())) reused.incrementAndGet(); }
            public void onChildViewDetachedFromWindow(View view) { }
        };
        RecyclerView.OnScrollListener scrolling = new RecyclerView.OnScrollListener() { public void onScrolled(RecyclerView list, int dx, int dy) { scroll.addAndGet(Math.abs(dy)); } };
        f.main(() -> { f.list().addRecyclerListener(recycling); f.list().addOnChildAttachStateChangeListener(attachment); f.list().addOnScrollListener(scrolling); });
        f.resources.add(() -> f.main(() -> { f.list().removeRecyclerListener(recycling); f.list().removeOnChildAttachStateChangeListener(attachment); f.list().removeOnScrollListener(scrolling); }));
        for (int n = 0; n < 12 && recycledX.get() == 0; n++) { verticalGesture(f, true); assertWidgets(f); }
        check(scroll.get() > 0 && recycledX.get() > 0, "actual vertical motion and X recycling, not scrollToPosition");
        check(reused.get() > 0, "actual recycled holder rebound to another address");
        for (int n = 0; n < 16; n++) {
            boolean visible = f.onMain(() -> { for (int c = 0; c < f.list().getChildCount(); c++) if (X.equals(f.list().getChildAt(c).getTag())) return true; return false; });
            if (visible) break; verticalGesture(f, false); assertWidgets(f);
        }
        eq(xId, f.stableId(X)); eq(true, f.checked(X)); check(f.controller.expanded.contains(X), "fold survives recycle by address");
        eq(version, f.store.read().getLong("selection_version")); f.show(V6); check(f.rowText(V6).replace("\u200b", "").contains(V6), "IPv6 bind retains canonical full port");
    }
    static void testHeldRemovedTouchNoPositionIdentity(Fixture f) throws Exception { heldRemoved(f, false); }
    static void testHeldRemovedAccessibilityNoPositionIdentity(Fixture f) throws Exception { heldRemoved(f, true); }
    static void heldRemoved(Fixture f, boolean accessibility) throws Exception {
        f.pausedComplete(); f.selectX(true); f.stopCycle(); NativeAccess access = new NativeAccess(f);
        editSource(f, access, Y); eq(1, f.store.read().getJSONArray("candidates").length()); check(selected(f.store.read(), X), "source edit keeps saved X");
        restart(f, access, 1); f.show(X); f.click(f.widget(X, R.id.preferred_row_heading)); f.settle(); f.publish(); f.draw(); f.show(X);
        RecyclerView.ViewHolder old = f.onMain(() -> f.list().getChildViewHolder(f.rowWidget(X, 0))); View oldView = f.onMain(() -> old.itemView); View choice = f.widget(X, R.id.preferred_choice);
        long id = f.stableId(X), version = f.store.read().getLong("selection_version"); Anchor anchor = new Anchor(f); List<String> heldOrder = order(rows(f));
        Touch touch = null;
        if (accessibility) { access.configure(true); access.focus(X, R.id.preferred_row_heading, true); } else touch = new Touch(f);
        if (accessibility) access.rowClick(X, R.id.preferred_choice); else f.click(choice);
        f.settle(); f.publish(); awaitHeld(f, heldOrder); f.drawWithoutAdapterDrain();
        check(!selected(f.store.read(), X), "real uncheck removed selected X"); eq(false, f.store.read().getBoolean("enabled"));
        check(f.state().rows.stream().noneMatch(r -> X.equals(r.address)), "desired State truly removes X after new source/session");
        check(order(rows(f)).contains(X), "production deferMoves genuinely holds removed old row");
        eq(false, f.checked(X)); eq(id, f.stableId(X)); anchor.assertPreserved(f); check(f.controller.expanded.contains(X), "held fold preserved");
        if (accessibility) eq(true, f.onMain(() -> f.rowWidget(X, R.id.preferred_row_heading).isAccessibilityFocused()));
        CountDownLatch removed = new CountDownLatch(1);
        RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() { public void onItemRangeRemoved(int p, int n) { removed.countDown(); } };
        f.main(() -> f.list().getAdapter().registerAdapterDataObserver(observer));
        f.resources.add(() -> f.main(() -> { f.list().suppressLayout(false); f.list().getAdapter().unregisterAdapterDataObserver(observer); }));
        if (accessibility) {
            f.main(() -> f.list().suppressLayout(true));
            access.focus(X, R.id.preferred_row_heading, false);
        } else {
            Touch pointer = touch;
            f.main(() -> { pointer.end(MotionEvent.ACTION_CANCEL); f.list().suppressLayout(true); });
        }
        await(removed, "real production Diff removal after native interaction release"); f.main(() -> { });
        long settledVersion = f.store.read().getLong("selection_version"); eq(version + 1, settledVersion);
        f.main(() -> {
            eq(RecyclerView.NO_POSITION, old.getBindingAdapterPosition()); eq(X, oldView.getTag());
            check(choice.performClick(), "old native NO_POSITION checkbox click");
        });
        f.settle(); eq(0, field(f.controller, "selectionPending")); eq(settledVersion, f.store.read().getLong("selection_version"));
        eq(0, f.store.read().getJSONArray("selected").length()); f.main(() -> f.list().suppressLayout(false)); f.publish(); f.draw();
        check(order(rows(f)).stream().noneMatch(X::equals), "actual layout removes old ghost row");
        access.configure(false); editSource(f, access, X + "\n" + Y); restart(f, access, 2); f.show(X); eq(false, f.checked(X));
        check(f.controller.expanded.contains(X), "restored address gets its own fold, not Y's"); select(f, X, true); f.show(Y); eq(false, f.checked(Y));
        eq(X, f.store.read().getJSONArray("selected").getJSONObject(0).getString("address")); eq(settledVersion + 1, f.store.read().getLong("selection_version"));
    }
    /** All non-X addresses complete first in one legal N=32 batch; X alone remains resumable. */
    static void progressive(Fixture f) throws Exception {
        denseSource(f); f.backend.holdProbe = true; f.launch(); f.tab(1); header(f);
        NativeAccess input = new NativeAccess(f); String resource = f.onMain(() -> f.activity.getResources().getResourceName(R.id.preferred_concurrency_input));
        AccessibilityNodeInfo editor = input.require(n -> resource.equals(n.getViewIdResourceName())); Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "32");
        try { check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "actual concurrency input 32"); } finally { editor.recycle(); }
        f.settle(); f.publish(); f.click(f.view(R.id.preferred_primary)); f.backend.next("prepare");
        Task task = f.backend.next("probe"); await(task.polled, "progressive poll"); eq(DENSE_COUNT, task.request.getJSONArray("candidates").length());
        JSONObject partial = f.backend.snapshot(task, "running", false, false, 0), full = f.backend.snapshot(task, "running", false, true, 0);
        for (int n = 1; n < DENSE_COUNT; n++) partial.getJSONArray("results").put(n, full.getJSONArray("results").getJSONObject(n)); partial.put("completed", DENSE_COUNT - 1);
        task.replies.add(partial); task.awaitPoll(2); f.settle(); f.publish(); header(f); f.click(f.view(R.id.preferred_primary)); await(task.cancelled, "progressive Pause");
        task.replies.add(new JSONObject(partial.toString()).put("state", "cancelled").put("worker_done", true));
        until(() -> { f.publish(); return !f.state().busy && f.state().completed == DENSE_COUNT - 1; }, "matching progressive drain"); f.stopCycle(); f.actorDrain(); middleByGesture(f);
        eq(Y, rows(f).get(0).address); check(row(f.state().rows, Y).selectable, "Y naturally eligible before late update");
    }
    static Task resumeHeld(Fixture f) throws Exception {
        header(f); f.click(f.view(R.id.preferred_primary)); Task task = f.backend.next("probe"); await(task.polled, "remaining probe poll");
        eq(1, task.request.getJSONArray("candidates").length()); eq(X, task.request.getJSONArray("candidates").getJSONObject(0).getString("address")); f.publish(); middleByGesture(f); return task;
    }
    static void finishRemaining(Fixture f, Task task) throws Exception {
        task.replies.add(f.backend.snapshot(task, "completed", true, true, 0)); fence((ExecutorService) field(f.controller, "networkIO")); fence(f.control); f.publish();
        eq(X, f.state().rows.get(0).address); eq(DENSE_COUNT, f.state().completed);
    }
    static final class DiffAudit implements AutoCloseable {
        final Fixture f; final List<PreferredIpController.Row> captured; final String address; final CountDownLatch applied = new CountDownLatch(1); long frames;
        final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
            public void onItemRangeChanged(int p, int n, Object payload) { inspect(); }
            public void onItemRangeInserted(int p, int n) { inspect(); }
            public void onItemRangeRemoved(int p, int n) { inspect(); }
            public void onItemRangeMoved(int p, int q, int n) { inspect(); }
        };
        final ViewTreeObserver.OnPreDrawListener drawing;
        DiffAudit(Fixture f, List<PreferredIpController.Row> captured, String address) {
            this.f = f; this.captured = captured; this.address = address;
            drawing = () -> { f.failures.attempt(() -> { if (selected(f.state().data, address)) { assertWidgets(f); frames++; } }); return true; };
            f.main(() -> { f.list().getAdapter().registerAdapterDataObserver(observer); f.list().getViewTreeObserver().addOnPreDrawListener(drawing); }); f.resources.add(this);
        }
        void inspect() {
            f.failures.attempt(() -> {
                if (rows(f) == captured) {
                    eq(false, row(captured, address).checked); check(selected(f.state().data, address), "newer actual State consumed before older Diff apply"); applied.countDown();
                }
            });
        }
        public void close() { f.main(() -> { f.list().getAdapter().unregisterAdapterDataObserver(observer); if (f.list().getViewTreeObserver().isAlive()) f.list().getViewTreeObserver().removeOnPreDrawListener(drawing); }); }
    }
    static void testQueuedContentDiffUsesLatestSelection(Fixture f) throws Exception { lateDiff(f, false, false, false); }
    static void testCalculatedContentDiffUsesLatestSelection(Fixture f) throws Exception { lateDiff(f, true, false, false); }
    static void testQueuedReorderDiffUsesLatestSelection(Fixture f) throws Exception { lateDiff(f, false, true, false); }
    static void testCalculatedReorderDiffUsesLatestSelection(Fixture f) throws Exception { lateDiff(f, true, true, false); }
    static void testQueuedContentDiffAfterReadFailure(Fixture f) throws Exception { lateDiff(f, false, false, true); }
    static void testCalculatedContentDiffAfterReadFailure(Fixture f) throws Exception { lateDiff(f, true, false, true); }
    static void lateDiff(Fixture f, boolean calculated, boolean reorder, boolean failCancel) throws Exception {
        if (reorder) progressive(f); else denseComplete(f);
        f.trace = new PreferredIpTestControl.StateTrace(f); long version = f.store.read().optLong("selection_version");
        Task remaining = reorder ? resumeHeld(f) : null;
        Anchor anchor = new Anchor(f); check(anchor.position > 5 && anchor.position < rows(f).size() - 5, "actual non-boundary gesture anchor");
        String address = anchor.address, secondary = nextVisible(f, anchor); long id = f.stableId(address);
        ExecutorService differ = f.onMain(() -> (ExecutorService) field(f.activity, "differ"));
        PreferredIpTestControl.Gate diff = PreferredIpV2ContinuationTest.block(f, differ);
        List<PreferredIpController.Row> before = rows(f);
        if (reorder) finishRemaining(f, remaining); else { f.click(f.widget(secondary, R.id.preferred_row_heading)); f.publish(); }
        check(f.onMain(() -> (Boolean) field(f.list().getAdapter(), "diffRunning")), "real Diff queued behind case differ gate");
        List<PreferredIpController.Row> captured = f.state().rows;
        check(!row(captured, address).checked && (reorder || !sameRender(row(before, secondary), row(captured, secondary))), "real content/reorder old snapshot");
        if (reorder) { eq(Y, before.get(0).address); eq(X, captured.get(0).address); }
        DiffAudit audit = new DiffAudit(f, captured, address);
        PreferredIpTestControl.Gate mainGate = calculated ? f.gates.create() : null;
        // Failure variant first saves genuinely, leaving old Diff.checked=false opposed to current disk=true.
        if (failCancel) { f.click(f.widget(address, R.id.preferred_choice)); f.settle(); f.publish(); check(selected(f.store.read(), address), "real add completed before failing cancel"); }
        synchronized (storeMonitor()) {
            View choice = f.widget(address, R.id.preferred_choice);
            f.main(() -> {
                check(choice.performClick(), "actual late-window selection intent"); eq(!failCancel, ((CompoundButton) choice).isChecked());
                try { fence(f.control); eq(1, field(f.controller, "selectionPending")); } catch (Exception e) { throw new AssertionError(e); }
                if (calculated) new Handler(Looper.getMainLooper()).post(() -> { try { mainGate.block(); } catch (Exception e) { f.failures.add(e); } });
            });
            if (calculated) mainGate.awaitEnter();
            if (failCancel) f.fault = new ReadFault(f.dir);
        }
        ioWithoutMain(f); publishWithoutMain(f);
        if (failCancel) { f.fault.close(); f.fault = null; }
        check(selected(f.store.read(), address), "latest genuine disk selection differs from captured old Row.checked");
        eq(false, row(captured, address).checked); eq(version + 1, f.store.read().getLong("selection_version"));
        if (!calculated) {
            f.main(() -> { }); check(selected(f.state().data, address), "latest State actually consumed while Diff still queued");
            check(f.trace.snapshot().stream().anyMatch(e -> address.equals(e.address)), "actual settled message consumed before releasing queued Diff");
        }
        diff.close(); fence(differ); // Old calculation has returned and posted its apply; main gate still holds calculated variant.
        if (calculated) mainGate.close();
        f.main(() -> { }); await(audit.applied, "actual old Diff applied after newer State");
        f.settle(); f.publish(); f.awaitAdapterIdle(); f.draw(); assertWidgets(f); check(audit.frames > 0, "actual post-confirmation native frames audited");
        anchor.assertPreserved(f); if (!reorder) check(f.controller.expanded.contains(secondary), "old content Diff did not lose the explicitly expanded address");
        f.show(address); eq(id, f.stableId(address)); eq(true, f.checked(address)); f.show(Y); eq(false, f.checked(Y));
        long revision = f.store.read().getLong("revision"); f.publish(); f.draw(); f.settle(); eq(revision, f.store.read().getLong("revision")); eq(version + 1, f.store.read().getLong("selection_version")); f.failures.assertEmpty();
    }
    static void testSameRowsAddReadFailureHasNoDiff(Fixture f) throws Exception { sameRowsFailure(f, false); }
    static void testSameRowsRemoveReadFailureHasNoDiff(Fixture f) throws Exception { sameRowsFailure(f, true); }
    static void sameRowsFailure(Fixture f, boolean saved) throws Exception {
        f.pausedComplete(); if (saved) f.selectX(true); f.stopCycle(); f.show(X); f.trace = new PreferredIpTestControl.StateTrace(f);
        List<PreferredIpController.Row> original = rows(f); JSONObject before = f.store.read();
        synchronized (storeMonitor()) {
            View choice = f.widget(X, R.id.preferred_choice);
            f.main(() -> { check(choice.performClick(), "equal-model native click"); eq(!saved, ((CompoundButton) choice).isChecked()); try { fence(f.control); eq(1, field(f.controller, "selectionPending")); } catch (Exception e) { throw new AssertionError(e); } });
            f.fault = new ReadFault(f.dir);
        }
        f.settle(); f.fault.close(); f.fault = null; f.publish(); f.draw();
        check(rows(f) == original, "sameRows early return: no Diff assigned a replacement list");
        check(!f.onMain(() -> (Boolean) field(f.list().getAdapter(), "diffRunning")), "not falsely claiming a computed Diff for equal models");
        for (int n = 0; n < original.size(); n++) check(sameRender(original.get(n), f.state().rows.get(n)), "all real render fields equal");
        for (PreferredIpTestControl.Event e : f.trace.snapshot()) if (e.state != null) check(!e.state.saving, "all captured fast frames saving=false");
        eq(saved, f.checked(X)); eq(before.toString(), f.store.read().toString()); f.show(Y); eq(false, f.checked(Y));
    }
    static void testNativeTouchUpDefersMovesFoldAnchor(Fixture f) throws Exception { touchDefer(f, MotionEvent.ACTION_UP); }
    static void testNativeTouchCancelDefersMovesFoldAnchor(Fixture f) throws Exception { touchDefer(f, MotionEvent.ACTION_CANCEL); }
    static void touchDefer(Fixture f, int end) throws Exception {
        progressive(f); Task task = resumeHeld(f);
        Anchor located = new Anchor(f); String address = located.address; selectVisible(f, address);
        String secondary = nextVisible(f, new Anchor(f)); f.click(f.widget(secondary, R.id.preferred_row_heading)); f.publish(); f.draw();
        Anchor anchor = new Anchor(f); List<String> held = order(rows(f));
        check(anchor.position > 5 && anchor.position < held.size() - 5, "non-boundary unchanged-height touch anchor");
        Touch touch = new Touch(f); finishRemaining(f, task); awaitHeld(f, held); f.drawWithoutAdapterDrain();
        eq(X, f.state().rows.get(0).address); eq(Y, rows(f).get(0).address); check(row(rows(f), X).result.optBoolean("probe_complete"), "content updates while positions held");
        anchor.assertPreserved(f); eq(true, f.checked(address)); check(f.controller.expanded.contains(secondary), "touch doesn't alter fold"); touch.end(end);
        f.awaitAdapterIdle(); f.draw(); eq(X, rows(f).get(0).address); anchor.assertPreserved(f); assertWidgets(f); eq(1L, f.store.read().getLong("selection_version"));
    }
    static void testNativeAccessibilityFocusDefersMovesAndClicks(Fixture f) throws Exception {
        progressive(f); NativeAccess access = new NativeAccess(f); Task task = resumeHeld(f);
        String address = new Anchor(f).address; selectVisible(f, address); String secondary = nextVisible(f, new Anchor(f)); selectVisible(f, secondary);
        access.configure(true); access.rowClick(secondary, R.id.preferred_row_heading); f.draw();
        Anchor anchor = new Anchor(f); List<String> held = order(rows(f)); check(anchor.position > 5 && anchor.position < held.size() - 5, "non-boundary unchanged-height accessibility anchor");
        access.focus(secondary, R.id.preferred_row_heading, true);
        finishRemaining(f, task); awaitHeld(f, held); f.drawWithoutAdapterDrain(); eq(Y, rows(f).get(0).address); eq(X, f.state().rows.get(0).address);
        eq(true, f.onMain(() -> f.rowWidget(secondary, R.id.preferred_row_heading).isAccessibilityFocused())); anchor.assertPreserved(f);
        access.rowClick(secondary, R.id.preferred_choice); awaitHeld(f, held); f.drawWithoutAdapterDrain(); eq(false, f.checked(secondary)); check(f.controller.expanded.contains(secondary), "native accessibility checkbox click does not fold");
        eq(secondary, f.onMain(() -> field(f.activity, "accessibilityAddress"))); access.focus(secondary, R.id.preferred_row_heading, false);
        f.awaitAdapterIdle(); f.draw(); eq(X, rows(f).get(0).address); anchor.assertPreserved(f); assertWidgets(f);
        access.rowClick(secondary, R.id.preferred_row_heading); f.draw(); check(!f.controller.expanded.contains(secondary), "native accessibility heading click folds");
        eq(3L, f.store.read().getLong("selection_version")); f.show(X); eq(false, f.checked(X));
    }
}
