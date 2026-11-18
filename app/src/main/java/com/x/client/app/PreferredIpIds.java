package com.x.client.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Short opaque IDs without truncating the network description or source URL. */
final class PreferredIpIds {
    private PreferredIpIds() { }

    static String opaque(String kind, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] alphabet = "0123456789abcdef".toCharArray();
            StringBuilder out = new StringBuilder(kind).append('-');
            for (byte b : digest) {
                out.append(alphabet[(b & 0xff) >>> 4]).append(alphabet[b & 0x0f]);
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is a mandatory Java/Android algorithm; never use a colliding fallback.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
