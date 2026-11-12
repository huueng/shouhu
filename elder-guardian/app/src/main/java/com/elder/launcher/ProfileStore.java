package com.elder.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 配置档案（Profile JSON）：一份 JSON = 一台老人机的全部设置
 *
 * 解决三件麻烦事：
 *   1. 换手机 / 重装（尤其换签名必须卸载重装）时不用再从头点一遍
 *   2. 家里有两台老人机，配好一台可以直接"复制"过去
 *   3. 出问题时家人把这份 JSON 发过来，比问"你点了哪个开关"准确一百倍
 *
 * ⚠️ 里面包含定位密钥、DDNS Token，属于敏感文件：只在家人之间传，不要发群里。
 */
public final class ProfileStore {

    private static final String TAG = "ElderProfile";

    /** 需要打包的配置项（只列我们自己的键，避免把系统/别的库的键带出去） */
    private static final String[] KEYS = {
            Prefs.TILES,
            Prefs.DARK_MODE,
            Prefs.LOCK_TASK,
            Prefs.BLOCK_INSTALL,
            Prefs.BLOCK_UNINSTALL,
            Prefs.PERSISTENT_HOME,
            Prefs.CHIME_ENABLED, Prefs.CHIME_HOURS,
            Prefs.WEATHER_ENABLED, Prefs.WEATHER_HOURS,
            "weather_place_name", "weather_place_ll",
            Prefs.KILL_ENABLED, Prefs.KILL_INTERVAL,
            Prefs.KILL_WHITELIST, Prefs.NOTIF_WHITELIST, Prefs.NOTIF_FILTER,
            Prefs.CALLER_ANNOUNCE, Prefs.CALL_RETURN_HOME, Prefs.WATCHDOG,
            Prefs.FLOATING_NAV, Prefs.TRACK_ENABLED,
            // 内网 WIFI 模式 + 是否已选过使用形态
            Prefs.INTRANET_MODE, Prefs.MODE_CHOSEN,
            Prefs.LOCATE_SERVER, Prefs.LOCATE_KEY,
            "ddns_enabled", "ddns_provider", "ddns_host", "ddns_token",
            "ddns_zone", "ddns_custom_url", "ddns_custom_header", "ddns_ipv4",
            LocConfig.KEY_ENABLED, LocConfig.KEY_CALL_TRIGGER, LocConfig.KEY_FAMILY,
            "report_enabled", "report_url", "report_header", Prefs.REPORT_INTERVAL,
            "update_url",
            "contact_tiles",
            Prefs.DATA_LIMIT
    };

    private ProfileStore() {
    }

    public static JSONObject exportJson(Context ctx) {
        JSONObject all = new JSONObject();
        try {
            JSONObject meta = new JSONObject();
            meta.put("app", "elder-guardian");
            meta.put("version", DiagReport.version(ctx));
            meta.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            meta.put("android", android.os.Build.VERSION.RELEASE);
            meta.put("time", System.currentTimeMillis());
            all.put("meta", meta);

            JSONObject prefs = new JSONObject();
            SharedPreferences sp = Prefs.get(ctx);
            for (String key : KEYS) {
                Object v = sp.getAll().get(key);
                if (v == null) continue;
                prefs.put(key, v);
            }
            all.put("prefs", prefs);
        } catch (Exception e) {
            Log.w(TAG, "导出失败", e);
        }
        return all;
    }

    /** 导出到文件，返回路径；失败返回 null */
    public static String exportToFile(Context ctx) {
        FileOutputStream out = null;
        try {
            JSONObject json = exportJson(ctx);
            String name = "profile-" + new SimpleDateFormat("MMdd-HHmm", Locale.CHINA)
                    .format(new Date()) + ".json";

            File dir = null;
            try {
                dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "老人守护");
                if (!dir.exists() && !dir.mkdirs()) dir = null;
            } catch (Exception ignored) {
            }
            if (dir == null) dir = ctx.getExternalFilesDir(null);
            if (dir == null) return null;

            File f = new File(dir, name);
            out = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(json.toString(2));
            w.flush();
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "写文件失败", e);
            return null;
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 从 JSON 文本导入。
     * @return 导入了多少项；0 表示不是有效档案
     */
    public static int importJson(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        try {
            JSONObject all = new JSONObject(text);
            JSONObject prefs = all.optJSONObject("prefs");
            if (prefs == null) return 0;

            SharedPreferences.Editor ed = Prefs.get(ctx).edit();
            int count = 0;
            Iterator<String> it = prefs.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!allowed(key)) continue;
                Object v = prefs.get(key);
                if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) ed.putInt(key, (Integer) v);
                else if (v instanceof Long) ed.putLong(key, (Long) v);
                else if (v instanceof String) ed.putString(key, (String) v);
                else ed.putString(key, String.valueOf(v));
                count++;
            }
            ed.apply();

            if (count > 0) {
                // 导入后把依赖配置的服务重新排期
                ElderApp.scheduleAllTimers(ctx);
                try {
                    if (Prefs.getBool(ctx, "ddns_enabled", false)) DdnsService.start(ctx);
                    if (Prefs.getBool(ctx, Prefs.LOCATE_SERVER, false)) LocateServer.start(ctx);
                } catch (Exception ignored) {
                }
            }
            return count;
        } catch (Exception e) {
            Log.w(TAG, "导入失败", e);
            return 0;
        }
    }

    /** 从文件读（家人把 json 丢到手机里再选） */
    public static String readFile(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean allowed(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /** 给界面用的一句话摘要 */
    public static String summary(Context ctx) {
        JSONObject json = exportJson(ctx);
        JSONObject prefs = json.optJSONObject("prefs");
        int n = prefs == null ? 0 : prefs.length();
        List<String> parts = new ArrayList<>();
        parts.add(n + " 项配置");
        if (Prefs.tilesCsv(ctx) != null) {
            String csv = Prefs.tilesCsv(ctx);
            int c = csv.trim().isEmpty() ? 0 : csv.split(",").length;
            parts.add(c + " 个桌面卡片");
        }
        try {
            int contacts = ContactStore.load(ctx).size();
            if (contacts > 0) parts.add(contacts + " 个联系人卡片");
        } catch (Exception ignored) {
        }
        return String.join(" · ", parts);
    }
}
