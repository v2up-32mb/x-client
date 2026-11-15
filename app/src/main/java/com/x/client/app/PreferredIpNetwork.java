package com.x.client.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.Inet6Address;

final class PreferredIpNetwork {
    static String id(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm.getActiveNetwork();
        if (network == null) return "offline";
        LinkProperties lp = cm.getLinkProperties(network);
        String description = network.getNetworkHandle() + ":" + (lp == null ? "" : lp.getInterfaceName()
                + ":" + lp.getLinkAddresses() + ":" + lp.getDnsServers());
        return PreferredIpIds.opaque("net", description);
    }

    static void requireDirect(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (new Preferences(context).getEnable()) throw new IllegalStateException("请先断开本应用VPN；不会自动断开");
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                throw new IllegalStateException("检测到VPN，请断开后再测，避免混入隧道流量");
            }
        }
        if (cm.getActiveNetwork() == null) throw new IllegalStateException("当前没有可用网络");
    }

    static boolean ipv6(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return false;
        boolean defaultRoute = false;
        for (android.net.RouteInfo route : lp.getRoutes()) {
            if (route.isDefaultRoute() && route.getDestination().getAddress() instanceof Inet6Address) defaultRoute = true;
        }
        if (!defaultRoute) return false;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet6Address && !a.getAddress().isLinkLocalAddress()
                    && !a.getAddress().isLoopbackAddress() && !a.getAddress().isSiteLocalAddress()
                    && (a.getAddress().getAddress()[0] & 0xfe) != 0xfc) return true;
        }
        return false;
    }

    static boolean metered(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return cm.isActiveNetworkMetered() || (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    static String label(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return "离线";
        String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动网络" : "当前网络";
        try { requireDirect(context); return type + " · 直连"; }
        catch (Exception error) { return type + " · " + error.getMessage(); }
    }
}
