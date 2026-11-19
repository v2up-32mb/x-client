package com.x.client.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/** UI is the only writer. The VPN process opens a complete, revisioned file for each start. */
public final class PreferredIpStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final File lockFile;

    public PreferredIpStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "preferred-ip-v1.json"));
        // A separate, permanent inode: never lock the base/.bak files that AtomicFile renames.
        lockFile = new File(context.getFilesDir(), "preferred-ip-v1.lock");
    }

    private interface Transaction { JSONObject run() throws Exception; }

    private JSONObject withLock(Transaction transaction) throws Exception {
        // FileChannel locks overlap rather than reenter in one JVM. Serialize local callers,
        // then lock across UI/:vpn processes, including AtomicFile recovery on API 24.
        synchronized (LOCK) {
            try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw");
                 FileLock held = lock.getChannel().lock()) {
                return transaction.run();
            }
        }
    }

    public JSONObject read() throws Exception {
        return withLock(this::readLocked);
    }

    /** Only call inside withLock; never reacquire the OS lock from a transaction. */
    private JSONObject readLocked() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
            return empty();
        }
        // openRead may restore .bak or remove .new. The same lock excludes all writers,
        // so recovery cannot race a live transaction on either old or new Android.
        try (FileInputStream input = file.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 16 * 1024 * 1024) throw new IllegalStateException("优选库过大");
                output.write(buffer, 0, count);
            }
            JSONObject data = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
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
        return withLock(() -> writeLocked(data, expectedRevision));
    }

    /** Short read/latest-revision/CAS transaction. Never call network or another store transaction here. */
    interface Mutation { void apply(JSONObject latest) throws Exception; }
    JSONObject update(Mutation mutation) throws Exception {
        return withLock(() -> {
            JSONObject latest = readLocked();
            long revision = latest.getLong("revision");
            mutation.apply(latest);
            return writeLocked(latest, revision);
        });
    }

    private JSONObject writeLocked(JSONObject data, long expectedRevision) throws Exception {
        if (readLocked().getLong("revision") != expectedRevision) {
            throw new IllegalStateException("优选库已更新，请重新确认");
        }
        JSONObject next = new JSONObject(data.toString());
        next.remove("download_ledger");
        next.put("schema_version", 1).put("revision", expectedRevision + 1);
        byte[] bytes = next.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("优选库超过16MiB限制");
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(bytes);
            stream.getFD().sync(); // propagate sync failures; AtomicFile itself only logs them
            file.finishWrite(stream);
            stream = null;
            if (readLocked().getLong("revision") != expectedRevision + 1) throw new IllegalStateException("优选库写入未完成，请重试");
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw error;
        }
        return next;
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
        PreferredIpStore store = new PreferredIpStore(context);
        store.withLock(() -> {
            JSONObject data = store.readLocked();
            data.getJSONObject("profile_modes").put(id, mode);
            return store.writeLocked(data, data.getLong("revision"));
        });
    }

    static void copyMode(Context context, String from, String to) {
        try {
            PreferredIpStore store = new PreferredIpStore(context);
            store.withLock(() -> {
                JSONObject data = store.readLocked();
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
                return store.writeLocked(data, data.getLong("revision"));
            });
        } catch (Exception error) {
            throw new IllegalStateException("复制优选模式失败，未复制节点：" + error.getMessage(), error);
        }
    }

    static void removeMode(Context context, String id) {
        try {
            PreferredIpStore store = new PreferredIpStore(context);
            store.withLock(() -> {
                JSONObject data = store.readLocked();
                data.getJSONObject("profile_modes").remove(id);
                data.getJSONObject("validations").remove(id);
                if (data.has("rollback_modes")) data.getJSONObject("rollback_modes").remove(id);
                return store.writeLocked(data, data.getLong("revision"));
            });
        } catch (Exception error) {
            throw new IllegalStateException("删除优选模式失败，未删除节点：" + error.getMessage(), error);
        }
    }
}
