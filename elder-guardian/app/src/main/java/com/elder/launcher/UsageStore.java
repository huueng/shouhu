package com.elder.launcher;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 使用习惯快照（最近 14 天，每天每个应用用了多少分钟）
 *
 * 为什么要它：很多"手机坏了"其实是配置不合理——
 * 老人天天用抖音，家人却把它从桌面删了；或者微信被误加进清理名单一天清 8 次。
 * 有了这份数据，家人（或以后接的 AI 诊断）才能判断"该改配置"而不是"该换手机"。
 *
 * 数据只存在本机 files/usage.json，不上传；只有家人主动拉 /status 或 /usage 时才带出去。
 */
public final class UsageStore {

    private static final String TAG = "ElderUsage";
    private static final String FILE = "usage.json";
    /** 保留天数：14 天够看出规律，也不至于把文件撑大 */
    private static final int KEEP_DAYS = 14;

    private UsageStore() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** 是否给了「使用情况访问」权限；没给的话系统会返回空数据 */
    public static boolean hasPermission(Context ctx) {
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager)
                    ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private static String dayKey(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime());
    }

    /** 重新采集今天的数据，并与已有文件合并 */
    public static void refresh(Context ctx) {
        if (!hasPermission(ctx)) {
            Log.i(TAG, "没有使用情况访问权限，跳过采集");
            return;
        }
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return;

            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            long start = day.getTimeInMillis();
            long end = System.currentTimeMillis();

            // 今天各应用的前台时长
            JSONObject today = new JSONObject();
            List<UsageStats> list = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, start, end);
            if (list != null) {
                for (UsageStats s : list) {
                    long ms = s.getTotalTimeInForeground();
                    if (ms < 60 * 1000) continue;      // 一分钟以下不算
                    int minutes = (int) (ms / 60000);
                    String pkg = s.getPackageName();
                    if (pkg == null) continue;
                    if (pkg.equals(ctx.getPackageName())) continue;   // 自己就是桌面，永远在前台
                    today.put(pkg, today.optInt(pkg, 0) + minutes);
                }
            }

            JSONObject all = loadRaw(ctx);
            all.put(dayKey(Calendar.getInstance()), today);
            prune(all);
            save(ctx, all);
        } catch (Exception e) {
            Log.w(TAG, "采集使用统计失败", e);
        }
    }

    private static void prune(JSONObject all) {
        try {
            Calendar limit = Calendar.getInstance();
            limit.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
            String min = dayKey(limit);
            List<String> drop = new ArrayList<>();
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.compareTo(min) < 0) drop.add(k);
            }
            for (String k : drop) all.remove(k);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject loadRaw(Context ctx) {
        FileInputStream in = null;
        try {
            File f = file(ctx);
            if (!f.exists()) return new JSONObject();
            in = new FileInputStream(f);
            InputStreamReader r = new InputStreamReader(in, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return new JSONObject();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void save(Context ctx, JSONObject all) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(ctx));
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(all.toString());
            w.flush();
        } catch (Exception e) {
            Log.w(TAG, "保存使用统计失败", e);
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 今天用得最多的几个应用：[{pkg,name,minutes}]，给 /status 用 */
    public static org.json.JSONArray todayTop(Context ctx, int limit) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            JSONObject all = loadRaw(ctx);
            JSONObject today = all.optJSONObject(dayKey(Calendar.getInstance()));
            if (today == null) {
                refresh(ctx);
                today = loadRaw(ctx).optJSONObject(dayKey(Calendar.getInstance()));
            }
            if (today == null) return arr;

            List<Map.Entry<String, Integer>> list = new ArrayList<>();
            Iterator<String> it = today.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                if (pkg.equals(ctx.getPackageName())) continue;
                list.add(new java.util.AbstractMap.SimpleEntry<>(pkg, today.optInt(pkg, 0)));
            }
            Collections.sort(list, (a, b) -> b.getValue() - a.getValue());

            PackageManager pm = ctx.getPackageManager();
            for (int i = 0; i < list.size() && i < limit; i++) {
                Map.Entry<String, Integer> e = list.get(i);
                JSONObject o = new JSONObject();
                o.put("pkg", e.getKey());
                o.put("minutes", e.getValue());
                o.put("name", labelOf(pm, e.getKey()));
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private static String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** 近 7 天概览：{"days":n,"total":[{"name","minutes"}],"daily":[{"date","minutes"}]} */
    public static JSONObject history(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONObject all = loadRaw(ctx);
            Map<String, Integer> total = new LinkedHashMap<>();
            org.json.JSONArray daily = new org.json.JSONArray();

            Calendar c = Calendar.getInstance();
            for (int i = 6; i >= 0; i--) {
                Calendar d = (Calendar) c.clone();
                d.add(Calendar.DAY_OF_YEAR, -i);
                JSONObject day = all.optJSONObject(dayKey(d));
                int daySum = 0;
                if (day != null) {
                    Iterator<String> it = day.keys();
                    while (it.hasNext()) {
                        String pkg = it.next();
                        if (pkg.equals(ctx.getPackageName())) continue;
                        int m = day.optInt(pkg, 0);
                        daySum += m;
                        total.put(pkg, total.containsKey(pkg) ? total.get(pkg) + m : m);
                    }
                }
                JSONObject o = new JSONObject();
                o.put("date", dayKey(d));
                o.put("minutes", daySum);
                daily.put(o);
            }

            List<Map.Entry<String, Integer>> list = new ArrayList<>(total.entrySet());
            Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
            org.json.JSONArray top = new org.json.JSONArray();
            PackageManager pm = ctx.getPackageManager();
            for (int i = 0; i < list.size() && i < 8; i++) {
                JSONObject o = new JSONObject();
                o.put("name", labelOf(pm, list.get(i).getKey()));
                o.put("minutes", list.get(i).getValue());
                top.put(o);
            }
            out.put("ok", true);
            out.put("days", 7);
            out.put("total", top);
            out.put("daily", daily);
        } catch (Exception e) {
            try {
                out.put("ok", false);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 短信/设置页用的一段话 */
    public static String summaryText(Context ctx) {
        try {
            refresh(ctx);
            JSONObject h = history(ctx);
            if (!h.optBoolean("ok")) return "使用统计读取失败";
            if (!hasPermission(ctx)) return "没有「使用情况访问」权限，无法统计（家人设置页可授权）";

            StringBuilder sb = new StringBuilder("近 7 天使用情况：\n");
            org.json.JSONArray daily = h.optJSONArray("daily");
            if (daily != null) {
                for (int i = 0; i < daily.length(); i++) {
                    JSONObject d = daily.getJSONObject(i);
                    int m = d.optInt("minutes");
                    sb.append(d.optString("date").substring(5)).append("  ")
                            .append(m >= 60 ? (m / 60 + " 小时 " + (m % 60) + " 分") : (m + " 分钟"))
                            .append('\n');
                }
            }
            org.json.JSONArray top = h.optJSONArray("total");
            if (top != null && top.length() > 0) {
                sb.append("最常用：");
                for (int i = 0; i < top.length() && i < 5; i++) {
                    JSONObject o = top.getJSONObject(i);
                    if (i > 0) sb.append('、');
                    sb.append(o.optString("name")).append(' ').append(o.optInt("minutes")).append(" 分");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "使用统计读取失败：" + e.getMessage();
        }
    }
}
