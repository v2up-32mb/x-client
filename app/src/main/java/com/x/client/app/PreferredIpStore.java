package com.x.client.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** UI is the only writer. The VPN process opens a complete, revisioned file for each start. */
public final class PreferredIpStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;

    public PreferredIpStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "preferred-ip-v1.json"));
    }

    public JSONObject read() throws Exception {
        synchronized (LOCK) {
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
                return empty();
            }
            // Do not call AtomicFile.openRead() in :vpn: on modern Android it can delete a
            // concurrent writer's .new file. Opening the base inode is stable across rename.
            File backup = new File(file.getBaseFile() + ".bak");
            File stable = backup.exists() ? backup : file.getBaseFile();
            byte[] bytes;
            FileInputStream opened;
            try { opened = new FileInputStream(stable); }
            catch (java.io.FileNotFoundException renamed) { opened = new FileInputStream(file.getBaseFile()); }
            try (FileInputStream input = opened;
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 16 * 1024 * 1024) throw new IllegalStateException("优选库过大");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (data.getInt("schema_version") != 1) throw new IllegalStateException("不支持的优选库版本");
            return data;
        }
    }

    public static JSONObject empty() throws Exception {
        return new JSONObject().put("schema_version", 1).put("revision", 0).put("enabled", false)
                .put("sources", new JSONArray()).put("candidates", new JSONArray())
                .put("prefixes", new JSONArray()).put("selected", new JSONArray())
                .put("last_test", new JSONObject()).put("profile_modes", new JSONObject())
                .put("validations", new JSONObject());
    }

    /** Compare-and-swap prevents a stale preview overwriting edits made elsewhere. */
    public JSONObject write(JSONObject data, long expectedRevision) throws Exception {
        synchronized (LOCK) {
            if (read().getLong("revision") != expectedRevision) {
                throw new IllegalStateException("优选库已更新，请重新确认");
            }
            JSONObject next = new JSONObject(data.toString());
            next.put("schema_version", 1).put("revision", expectedRevision + 1);
            byte[] bytes = next.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("优选库超过16MiB限制");
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(bytes);
                file.finishWrite(stream);
                stream = null;
                if (read().getLong("revision") != expectedRevision + 1) throw new IllegalStateException("优选库写入未完成，请重试");
            } catch (Exception error) {
                if (stream != null) file.failWrite(stream);
                throw error;
            }
            return next;
        }
    }

    public static String mode(JSONObject data, String id) {
        JSONObject modes = data.optJSONObject("profile_modes");
        return modes == null ? "independent" : modes.optString(id,
                data.optBoolean("enabled") ? "global" : "independent");
    }

    public static void setMode(Context context, String id, String mode) throws Exception {
        if (!"global".equals(mode) && !"independent".equals(mode) && !"default".equals(mode)) {
            throw new IllegalArgumentException("未知优选模式");
        }
        synchronized (LOCK) {
            PreferredIpStore store = new PreferredIpStore(context);
            JSONObject data = store.read();
            data.getJSONObject("profile_modes").put(id, mode);
            store.write(data, data.getLong("revision"));
        }
    }

    static void copyMode(Context context, String from, String to) {
        try {
            synchronized (LOCK) {
                PreferredIpStore store = new PreferredIpStore(context);
                JSONObject data = store.read();
                JSONObject modes = data.getJSONObject("profile_modes");
                // An unset pre-feature mode stays unset, so first-activation consent still
                // covers the copy instead of inventing an explicit independent exception.
                if (modes.has(from) || data.optBoolean("enabled")) modes.put(to, mode(data, from));
                else modes.remove(to);
                JSONObject validation = data.getJSONObject("validations").optJSONObject(from);
                if (validation != null) data.getJSONObject("validations").put(to, validation);
                if (data.has("rollback_modes")) {
                    JSONObject rollback = data.getJSONObject("rollback_modes");
                    rollback.put(to, rollback.optString(from, "independent"));
                }
                store.write(data, data.getLong("revision"));
            }
        } catch (Exception error) {
            throw new IllegalStateException("复制优选模式失败，未复制节点：" + error.getMessage(), error);
        }
    }

    static void removeMode(Context context, String id) {
        try {
            synchronized (LOCK) {
                PreferredIpStore store = new PreferredIpStore(context);
                JSONObject data = store.read();
                data.getJSONObject("profile_modes").remove(id);
                data.getJSONObject("validations").remove(id);
                if (data.has("rollback_modes")) data.getJSONObject("rollback_modes").remove(id);
                store.write(data, data.getLong("revision"));
            }
        } catch (Exception error) {
            throw new IllegalStateException("删除优选模式失败，未删除节点：" + error.getMessage(), error);
        }
    }
}
