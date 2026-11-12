package com.elder.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 配置快照：把「配好的那一刻」存下来，出问题能一键回到这个状态。
 *
 * 三种：
 *   first  —— 首次装机、初始设置跑完之后自动存一份，之后不再覆盖（出厂基线）
 *   manual —— 老版本留下的"手动快照"（覆盖式），保留兼容
 *   具名快照 —— 家人存的时候可以自己起名字（"刚配好定位"、"换卡前"……），
 *              想恢复哪一份就恢复哪一份
 *
 * 为什么放 App 私有目录：里面含定位密钥、上报令牌，不能放公共目录给人翻。
 * 恢复走 ProfileStore.importJson，和其它地方导入导出用同一套白名单，不会写坏键。
 *
 * 具名快照的元信息（名字、时间）放在 index.json，正文仍是 ProfileStore 导出的原文，
 * 这样老版本存的 first/manual 不用迁移也能继续用。
 */
public final class SnapshotStore {

    private static final String TAG = "ElderSnapshot";
    private static final String DIR = "snapshots";
    private static final String FILE_FIRST = "first.json";
    private static final String FILE_MANUAL = "manual.json";
    private static final String FILE_INDEX = "index.json";

    public static final String KEY_FIRST_AT = "snapshot_first_at";
    public static final String KEY_MANUAL_AT = "snapshot_manual_at";

    /** 首次基线的固定 id（家人端「恢复布局」里也用它） */
    public static final String ID_FIRST = "first";
    public static final String ID_MANUAL = "manual";

    /** 最多留这么多份具名快照，防止一直存把手机塞满 */
    private static final int MAX_NAMED = 20;

    private SnapshotStore() {
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "建目录失败：" + d.getAbsolutePath());
        }
        return d;
    }

    private static File file(Context ctx, boolean first) {
        return new File(dir(ctx), first ? FILE_FIRST : FILE_MANUAL);
    }

    private static File namedFile(Context ctx, String id) {
        return new File(dir(ctx), id + ".json");
    }

    public static boolean has(Context ctx, boolean first) {
        return file(ctx, first).exists();
    }

    /**
     * 首次安装后存一份基线。
     * 只在没有的时候存 —— 之后再调不会把基线覆盖成"被改坏的现状"。
     */
    public static void saveFirstIfAbsent(Context ctx) {
        if (has(ctx, true)) return;
        if (save(ctx, true)) {
            Log.i(TAG, "已保存首次配置基线");
        }
    }

    /** 存快照（覆盖同名文件） */
    public static boolean save(Context ctx, boolean first) {
        return writeTo(ctx, file(ctx, first), first ? KEY_FIRST_AT : KEY_MANUAL_AT);
    }

    private static boolean writeTo(Context ctx, File f, String atKey) {
        FileOutputStream out = null;
        try {
            JSONObject json = ProfileStore.exportJson(ctx);
            out = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(json.toString(2));
            w.flush();
            w.close();
            out = null;
            if (atKey != null) Prefs.putLong(ctx, atKey, System.currentTimeMillis());
            Log.i(TAG, "快照已保存：" + f.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "保存快照失败", e);
            return false;
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 存一份带名字的快照。
     *
     * @return 新快照的 id；失败返回 null
     */
    public static String saveNamed(Context ctx, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "手工 " + fmt(System.currentTimeMillis());
        if (clean.length() > 40) clean = clean.substring(0, 40);

        String id = "s" + System.currentTimeMillis();
        File f = namedFile(ctx, id);
        int n = 0;
        while (f.exists() && n < 100) {
            n++;
            id = "s" + System.currentTimeMillis() + "_" + n;
            f = namedFile(ctx, id);
        }
        if (!writeTo(ctx, f, null)) return null;

        JSONArray idx = readIndex(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", clean);
            o.put("at", System.currentTimeMillis());
            idx.put(o);
        } catch (Exception ignored) {
        }
        // 超量的从最老的开始删，别只删索引留一堆孤儿文件
        while (idx.length() > MAX_NAMED) {
            JSONObject oldest = idx.optJSONObject(0);
            if (oldest == null) break;
            String oid = oldest.optString("id", "");
            if (!oid.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                namedFile(ctx, oid).delete();
            }
            idx = removeAt(idx, 0);
        }
        writeIndex(ctx, idx);
        Log.i(TAG, "已存具名快照：" + clean + " (" + id + ")");
        return id;
    }

    /** 列出所有快照（新的在前）：首次基线、老手动快照、具名快照 */
    public static JSONArray list(Context ctx) {
        JSONArray out = new JSONArray();
        try {
            if (has(ctx, true)) {
                JSONObject o = new JSONObject();
                o.put("id", ID_FIRST);
                o.put("name", "首次装机基线");
                o.put("at", Prefs.getLong(ctx, KEY_FIRST_AT, 0));
                o.put("auto", true);
                out.put(o);
            }
            if (has(ctx, false)) {
                JSONObject o = new JSONObject();
                o.put("id", ID_MANUAL);
                o.put("name", "手动快照（旧版）");
                o.put("at", Prefs.getLong(ctx, KEY_MANUAL_AT, 0));
                out.put(o);
            }

            JSONArray idx = readIndex(ctx);
            List<JSONObject> named = new ArrayList<JSONObject>();
            for (int i = 0; i < idx.length(); i++) {
                JSONObject o = idx.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty() || !namedFile(ctx, id).exists()) continue;
                named.add(o);
            }
            Collections.sort(named, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("at", 0), a.optLong("at", 0));
                }
            });
            for (JSONObject o : named) out.put(o);
        } catch (Exception e) {
            Log.w(TAG, "读快照列表失败", e);
        }
        return out;
    }

    /** 快照名字（给提示语用） */
    public static String nameOf(Context ctx, String id) {
        if (ID_FIRST.equals(id)) return "首次装机基线";
        if (ID_MANUAL.equals(id)) return "手动快照";
        JSONArray idx = readIndex(ctx);
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                return o.optString("name", id);
            }
        }
        return id;
    }

    /** 按 id 恢复：first / manual / 具名快照 id。返回恢复了多少项，0 表示无效。 */
    public static int restoreById(Context ctx, String id) {
        if (id == null) id = "";
        id = id.trim();
        if (id.isEmpty() || ID_FIRST.equals(id)) return restore(ctx, true);
        if (ID_MANUAL.equals(id)) return restore(ctx, false);
        File f = namedFile(ctx, id);
        if (!f.exists()) return 0;
        String text = ProfileStore.readFile(f.getAbsolutePath());
        if (text == null) return 0;
        int n = ProfileStore.importJson(ctx, text);
        Log.i(TAG, "已从快照 " + id + " 恢复 " + n + " 项配置");
        return n;
    }

    /** 删一份具名快照。首次基线和旧手动快照不给删（那是安全网）。 */
    public static boolean remove(Context ctx, String id) {
        if (id == null || id.trim().isEmpty()) return false;
        id = id.trim();
        if (ID_FIRST.equals(id) || ID_MANUAL.equals(id)) return false;
        File f = namedFile(ctx, id);
        boolean ok = f.delete();
        JSONArray idx = readIndex(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < idx.length(); i++) {
            JSONObject o = idx.optJSONObject(i);
            if (o == null) continue;
            if (!id.equals(o.optString("id", ""))) out.put(o);
        }
        writeIndex(ctx, out);
        return ok;
    }

    /**
     * 恢复到快照。
     *
     * @param first true=首次基线；false=手动快照
     * @return 恢复了多少项配置；0 表示没有快照或不是有效档案
     */
    public static int restore(Context ctx, boolean first) {
        File f = file(ctx, first);
        if (!f.exists()) return 0;
        String text = ProfileStore.readFile(f.getAbsolutePath());
        if (text == null) return 0;
        int n = ProfileStore.importJson(ctx, text);
        Log.i(TAG, "已从" + (first ? "首次基线" : "手动快照") + "恢复 " + n + " 项配置");
        return n;
    }

    /**
     * 一份快照里"到底配置成什么样"的文字摘要（只读，不改任何东西）。
     *
     * 家人挑快照时得先知道里面是什么 —— 只显示"换卡前 11-10 22:15"根本没法选。
     * 只摘关键项，不把整个 JSON 摊出来：那边有令牌，而且没人看得懂。
     */
    public static String summaryText(Context ctx, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String sid = id.trim();
        File f;
        if (ID_FIRST.equals(sid)) {
            f = file(ctx, true);
        } else if (ID_MANUAL.equals(sid)) {
            f = file(ctx, false);
        } else {
            f = namedFile(ctx, sid);
        }
        if (!f.exists()) return null;
        String text = ProfileStore.readFile(f.getAbsolutePath());
        if (text == null) return null;

        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
        JSONObject meta = root.optJSONObject("meta");
        JSONObject p = root.optJSONObject("prefs");
        if (p == null) p = new JSONObject();

        StringBuilder sb = new StringBuilder();
        sb.append("快照「").append(nameOf(ctx, sid)).append("」");
        if (meta != null) {
            long t = meta.optLong("time", 0);
            sb.append("\n存于 ").append(t > 0 ? fmt(t) : (f.lastModified() > 0 ? fmt(f.lastModified()) : "未知"));
            String ver = meta.optString("version", "");
            String dev = meta.optString("device", "");
            if (!ver.isEmpty() || !dev.isEmpty()) {
                sb.append(" · ").append(ver);
                if (!dev.isEmpty()) sb.append(" · ").append(dev);
            }
        }

        sb.append("\n· 锁定任务：").append(yn(p, Prefs.LOCK_TASK));
        sb.append(" ｜ 安装限制：").append(yn(p, Prefs.BLOCK_INSTALL));
        sb.append(" ｜ 防卸载：").append(yn(p, Prefs.BLOCK_UNINSTALL));

        boolean reportOn = p.optBoolean("report_enabled", false);
        String url = p.optString("report_url", "");
        int every = p.optInt(Prefs.REPORT_INTERVAL, 10);
        sb.append("\n· 主动上报：").append(reportOn ? "开" : "关");
        if (reportOn) sb.append("，每 ").append(every).append(" 分钟");
        if (!url.isEmpty()) sb.append("（地址 …").append(tailToken(url)).append("）");

        int tiles = 0;
        try {
            String raw = p.optString("contact_tiles", "");
            if (!raw.isEmpty()) tiles = new org.json.JSONArray(raw).length();
        } catch (Exception ignored) {
        }
        sb.append("\n· 桌面卡片：").append(tiles > 0 ? (tiles + " 个") : "默认（没自定义）");

        sb.append("\n· 整点报时：").append(yn(p, Prefs.CHIME_ENABLED));
        String hours = p.optString(Prefs.CHIME_HOURS, "");
        if (!hours.isEmpty()) sb.append("（").append(hours).append("）");
        sb.append(" ｜ 天气：").append(yn(p, Prefs.WEATHER_ENABLED));

        sb.append("\n· 内网 WIFI 模式：").append(yn(p, Prefs.INTRANET_MODE));
        sb.append(" ｜ 自动清理后台：").append(yn(p, Prefs.KILL_ENABLED));
        sb.append(" ｜ 轨迹记录：").append(yn(p, Prefs.TRACK_ENABLED));
        return sb.toString();
    }

    private static String yn(JSONObject p, String key) {
        if (p == null || !p.has(key)) return "未设置";
        return p.optBoolean(key, false) ? "开" : "关";
    }

    /** 地址末尾令牌只留 4 位：截图发给别人时不至于把上报口子一起给出去 */
    private static String tailToken(String url) {
        int i = url.indexOf("k=");
        if (i < 0) return "无令牌";
        String t = url.substring(i + 2);
        int amp = t.indexOf('&');
        if (amp > 0) t = t.substring(0, amp);
        if (t.length() <= 4) return t;
        // 只留后 4 位（调用处已经带了"…"）
        return t.substring(t.length() - 4);
    }

    /** 给设置页/远程命令看的一句话 */
    public static String info(Context ctx) {
        StringBuilder sb = new StringBuilder();
        long a = Prefs.getLong(ctx, KEY_FIRST_AT, 0);
        sb.append("首次基线：").append(a > 0 ? fmt(a) : "还没存");
        int named = 0;
        JSONArray idx = readIndex(ctx);
        for (int i = 0; i < idx.length(); i++) {
            if (idx.optJSONObject(i) != null) named++;
        }
        sb.append("\n手动快照：").append(named).append(" 份");
        return sb.toString();
    }

    // ==================== index.json ====================

    private static JSONArray readIndex(Context ctx) {
        File f = new File(dir(ctx), FILE_INDEX);
        if (!f.exists()) return new JSONArray();
        String text = ProfileStore.readFile(f.getAbsolutePath());
        if (text == null) return new JSONArray();
        try {
            return new JSONArray(text);
        } catch (Exception e) {
            Log.w(TAG, "index.json 坏了，重建");
            return new JSONArray();
        }
    }

    private static void writeIndex(Context ctx, JSONArray arr) {
        FileOutputStream out = null;
        try {
            File f = new File(dir(ctx), FILE_INDEX);
            out = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(arr.toString());
            w.flush();
            w.close();
            out = null;
        } catch (Exception e) {
            Log.w(TAG, "写 index.json 失败", e);
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONArray removeAt(JSONArray arr, int pos) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i == pos) continue;
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.put(o);
        }
        return out;
    }

    private static String fmt(long t) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t));
    }
}
