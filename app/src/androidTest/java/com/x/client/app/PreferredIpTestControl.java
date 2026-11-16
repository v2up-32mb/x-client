package com.x.client.app;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

/** Case-local gates and the expressly approved transparent Controller.main observation.
 * No production State/listener writes, queue manipulation, fake frames or Window interception. */
final class PreferredIpTestControl {
    interface Action { void run() throws Exception; }
    static final class Failures {
        private final List<Throwable> errors = new ArrayList<>();
        synchronized void add(Throwable error) { errors.add(error); }
        void attempt(Action action) { try { action.run(); } catch (Throwable e) { add(e); } }
        synchronized void assertEmpty() {
            if (errors.isEmpty()) return;
            AssertionError failure = new AssertionError("fixture/cleanup failed: " + errors.size());
            for (Throwable error : errors) failure.addSuppressed(error);
            throw failure;
        }
    }
    static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void block() throws Exception {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new TimeoutException("case gate timeout");
        }
        void awaitEnter() throws Exception { PreferredIpV2Test.await(entered, "gate enter"); }
        public void close() { release.countDown(); }
    }
    static final class Gates {
        final List<Gate> gates = new CopyOnWriteArrayList<>();
        Gate create() { Gate gate = new Gate(); gates.add(gate); return gate; }
        void releaseAll() { for (Gate gate : gates) gate.close(); }
    }
    static final class Event {
        final PreferredIpController.State state;
        final String address;
        final Object before, after;
        Event(PreferredIpController.State state, String address, Object before, Object after) {
            this.state = state; this.address = address; this.before = before; this.after = after;
        }
    }
    static final class StateTrace extends Handler implements AutoCloseable {
        final PreferredIpV2Test.Fixture fixture;
        final Handler original;
        final Field reference;
        final List<Event> events = new ArrayList<>();
        private boolean restored;
        StateTrace(PreferredIpV2Test.Fixture fixture) throws Exception {
            super(Looper.getMainLooper()); // Same synchronous, no-Callback semantics as production.
            this.fixture = fixture;
            reference = PreferredIpController.class.getDeclaredField("main"); reference.setAccessible(true);
            original = (Handler) reference.get(fixture.controller);
            PreferredIpV2Test.eq(Looper.getMainLooper(), original.getLooper());
            fixture.actorDrain(); // Only install with network slot drained; old Handler FIFO is empty.
            fixture.trace = this; // Also reachable for cleanup if a subsequent readback/publish assertion fails.
            fixture.main(() -> {
                try { reference.set(fixture.controller, this); PreferredIpV2Test.check(reference.get(fixture.controller) == this, "Handler replacement readback"); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            fixture.publish();
            PreferredIpV2Test.check(snapshot().stream().anyMatch(event -> event.state != null), "actual State dispatch reached trace (ART final-reference check)");
            fixture.failures.assertEmpty();
        }
        @Override public void dispatchMessage(Message message) {
            PreferredIpController.State state = null; String address = null; Object before = null;
            try {
                Runnable callback = message.getCallback();
                PreferredIpV2Test.check(callback != null, "unexpected Controller.main message without callback");
                int states = 0, controllers = 0, strings = 0;
                for (Class<?> c = callback.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true); Object captured = field.get(callback);
                        if (captured instanceof PreferredIpController.State) { states++; state = (PreferredIpController.State) captured; }
                        else if (captured == fixture.controller) controllers++;
                        else if (captured instanceof String) { strings++; address = (String) captured; }
                        else throw new AssertionError("unknown Controller.main captured value: " + field.getName());
                    }
                }
                PreferredIpV2Test.check(controllers == 1 && ((states == 1 && strings == 0) || (states == 0 && strings == 1)), "unrecognized publish/settled closure");
                before = PreferredIpV2Test.field(fixture.controller, "listener");
            } catch (Throwable observationFailure) { fixture.failures.add(observationFailure); }
            // Original Message/Runnable is dispatched exactly once even if observation fails.
            try { super.dispatchMessage(message); }
            finally {
                try {
                    Object after = PreferredIpV2Test.field(fixture.controller, "listener");
                    synchronized (events) { events.add(new Event(state, address, before, after)); }
                } catch (Throwable observationFailure) { fixture.failures.add(observationFailure); }
            }
        }
        List<Event> snapshot() { synchronized (events) { return new ArrayList<>(events); } }
        void noSavingAndLostCompletion(String address) {
            int states = 0, lostStates = 0, lostSettled = 0;
            for (Event e : snapshot()) {
                if (e.state != null) {
                    states++; PreferredIpV2Test.check(!e.state.saving, "real saving=true frame in fast-failure window");
                    if (e.before == null && e.after == null) lostStates++;
                }
                if (address.equals(e.address) && e.before == null && e.after == null) lostSettled++;
            }
            PreferredIpV2Test.check(states > 0 && lostStates > 0 && lostSettled > 0, "actual State and settled consumed with null listener");
            fixture.failures.assertEmpty();
        }
        boolean restored() { return restored; }
        public void close() throws Exception {
            if (restored) return;
            fixture.actorDrain();
            fixture.main(() -> {
                try {
                    PreferredIpV2Test.check(reference.get(fixture.controller) == this, "trace still installed at restore");
                    reference.set(fixture.controller, original);
                    PreferredIpV2Test.check(reference.get(fixture.controller) == original, "original Handler restored");
                    restored = true;
                } catch (Exception e) { throw new AssertionError(e); }
            });
            fixture.failures.assertEmpty();
        }
    }
    /** Public RecyclerView-only temporary detach; no holder flags/adapter/Window writes. */
    static final class AbsentHolder implements AutoCloseable {
        final PreferredIpV2Test.Fixture f;
        final androidx.recyclerview.widget.RecyclerView list;
        final androidx.recyclerview.widget.RecyclerView.ViewHolder holder;
        final android.view.View view;
        final int childIndex, position, top;
        final long stableId;
        final java.util.concurrent.atomic.AtomicLong notifications = new java.util.concurrent.atomic.AtomicLong();
        final androidx.recyclerview.widget.RecyclerView.AdapterDataObserver observer = new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            public void onChanged() { notifications.incrementAndGet(); }
            public void onItemRangeChanged(int p, int n, Object payload) { notifications.incrementAndGet(); }
            public void onItemRangeInserted(int p, int n) { notifications.incrementAndGet(); }
            public void onItemRangeRemoved(int p, int n) { notifications.incrementAndGet(); }
            public void onItemRangeMoved(int p, int q, int n) { notifications.incrementAndGet(); }
        };
        boolean detached, closed;
        AbsentHolder(PreferredIpV2Test.Fixture fixture) throws Exception {
            f = fixture; list = f.list();
            view = f.rowWidget(PreferredIpV2Test.X, 0);
            holder = f.onMain(() -> list.getChildViewHolder(view));
            childIndex = f.onMain(() -> list.indexOfChild(view)); position = f.onMain(holder::getBindingAdapterPosition);
            stableId = f.onMain(holder::getItemId); top = f.onMain(view::getTop);
            f.main(() -> list.getAdapter().registerAdapterDataObserver(observer));
            f.resources.add(this);
        }
        void detach() {
            PreferredIpV2Test.check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper(), "detach on main");
            list.suppressLayout(true);
            try {
                list.getLayoutManager().detachView(view); detached = true;
                PreferredIpV2Test.eq(-1, list.indexOfChild(view));
                identity();
            } catch (Throwable e) { f.failures.attempt(this::close); throw e; }
        }
        void identity() {
            PreferredIpV2Test.eq(position, holder.getBindingAdapterPosition());
            PreferredIpV2Test.eq(stableId, holder.getItemId());
            PreferredIpV2Test.eq(PreferredIpV2Test.X, view.getTag());
            PreferredIpV2Test.check(position != androidx.recyclerview.widget.RecyclerView.NO_POSITION, "identity valid");
        }
        void attachMismatch(boolean diskValue) {
            PreferredIpV2Test.eq(-1, list.indexOfChild(view));
            PreferredIpV2Test.eq(!diskValue, ((android.widget.CompoundButton) view.findViewById(R.id.preferred_choice)).isChecked());
            list.getLayoutManager().attachView(view, Math.min(childIndex, list.getChildCount())); detached = false;
            identity(); PreferredIpV2Test.eq(top, view.getTop());
            PreferredIpV2Test.eq(!diskValue, ((android.widget.CompoundButton) view.findViewById(R.id.preferred_choice)).isChecked());
            PreferredIpV2Test.eq(0L, notifications.get());
        }
        public void close() {
            if (closed) return;
            f.main(() -> {
                try { if (detached) { list.getLayoutManager().attachView(view, Math.min(childIndex, list.getChildCount())); detached = false; } }
                finally { list.suppressLayout(false); list.getAdapter().unregisterAdapterDataObserver(observer); closed = true; }
            });
        }
    }

}
