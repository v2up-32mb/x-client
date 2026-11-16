package com.x.client.app;

import android.content.Context;
import android.net.*;
import xclient.Xclient;

/** Package-private dependency boundary. No Intent, preference, build flag or public fake backend. */
final class PreferredIpRuntime {
    interface Backend {
        String parse(String content, String source, String kind) throws Exception;
        String start(String request) throws Exception;
        String poll(String id) throws Exception;
        void cancel(String id);
    }
    interface NetworkAccess {
        String id(); String label(); void requireDirect(); boolean ipv6(); boolean metered();
        void watch(Runnable changed); void unwatch();
    }
    interface Clock { long now(); }
    interface Factory { PreferredIpController create(Context context, String reference); }
    // androidTest in this package may replace the factory before launching the real Activity.
    // Production's only default is the real gomobile API and real ConnectivityManager.
    static Factory factory = (context, reference) -> new PreferredIpController(context, reference,
            new RealBackend(), new RealNetwork(context.getApplicationContext()), System::currentTimeMillis);
    private static PreferredIpController retained;
    static synchronized PreferredIpController acquire(Context context, String reference) {
        if (retained == null) retained = factory.create(context.getApplicationContext(), reference);
        return retained;
    }
    static synchronized void forget() {
        if (retained != null) retained.dispose();
        retained = null;
    }
    private static final class RealBackend implements Backend {
        public String parse(String s, String id, String kind) throws Exception { return Xclient.parsePreferredIPList(s, id, kind); }
        public String start(String s) throws Exception { return Xclient.startPreferredIPTask(s); }
        public String poll(String id) throws Exception { return Xclient.getPreferredIPTask(id); }
        public void cancel(String id) { Xclient.cancelPreferredIPTask(id); }
    }
    private static final class RealNetwork implements NetworkAccess {
        private final Context context;
        private ConnectivityManager.NetworkCallback callback;
        RealNetwork(Context context) { this.context = context; }
        public String id() { return PreferredIpNetwork.id(context); }
        public String label() { return PreferredIpNetwork.label(context); }
        public void requireDirect() { PreferredIpNetwork.requireDirect(context); }
        public boolean ipv6() { return PreferredIpNetwork.ipv6(context); }
        public boolean metered() { return PreferredIpNetwork.metered(context); }
        public void watch(Runnable changed) {
            if (callback != null) return;
            callback = new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network n) { changed.run(); }
                public void onLost(Network n) { changed.run(); }
                public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { changed.run(); }
                public void onLinkPropertiesChanged(Network n, LinkProperties p) { changed.run(); }
            };
            context.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
        }
        public void unwatch() {
            if (callback == null) return;
            context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback); callback = null;
        }
    }
}
