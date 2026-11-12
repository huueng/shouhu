package com.elder.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹记录（老人机本地）
 *
 * 为什么放本地而不是实时推：
 * 老人机常驻推流耗电、耗流量，还会让"零服务器"变成空话。
 * 所以改成「检测到移动才记点」，家人端连着的时候一次性取走。
 *
 * 存储：files/track.jsonl，每行一个点，最多保留 500 个（约 3–5 天）。
 */
public final class TrackStore {

    private static final String TAG = "ElderTrack";
    private static final String FILE = "track.jsonl";
    private static final int MAX_POINTS = 500;
    /** 位移小于这个距离不算"移动"，避免原地抖动记一堆点 */
    public static final double MIN_MOVE_M = 80;
    /** 或者哪怕没怎么动，超过这么久也记一个点，便于画出"一直在同一处" */
    public static final long MAX_SILENCE_MS = 30 * 60 * 1000L;

    private TrackStore() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static List<JSONObject> load(Context ctx) {
        List<JSONObject> list = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return list;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    // 老数据只有 "lng"，补一份 "lon"，别让历史点继续丢经度
                    if (!o.has("lon") && o.has("lng")) o.put("lon", o.optDouble("lng"));
                    list.add(o);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读取轨迹失败", e);
        } finally {
            if (r != null) try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public static void add(Context ctx, double lat, double lng, float acc) {
        List<JSONObject> list = load(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("lat", lat);
            // 中继的点结构用 "lon"。以前只写 "lng"，中继读不到经度 → 每个轨迹点
            // 经度都是 0，家人端地图和围栏全跟着错。两个键都写，谁读都不吃亏。
            o.put("lon", lng);
            o.put("lng", lng);
            o.put("acc", Math.round(acc));
            list.add(o);
        } catch (Exception ignored) {
        }
        if (list.size() > MAX_POINTS) {
            list = list.subList(list.size() - MAX_POINTS, list.size());
        }
        save(ctx, list);
    }

    private static void save(Context ctx, List<JSONObject> list) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(ctx));
            StringBuilder sb = new StringBuilder();
            for (JSONObject o : list) sb.append(o.toString()).append('\n');
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "保存轨迹失败", e);
        } finally {
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 家人端拉取：since 之后（毫秒时间戳）的点 */
    public static JSONArray toJson(Context ctx, long since) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : load(ctx)) {
            if (o.optLong("t", 0) >= since) arr.put(o);
        }
        return arr;
    }

    public static void clear(Context ctx) {
        try {
            file(ctx).delete();
        } catch (Exception ignored) {
        }
    }

    /** 上一个点和现在差多远（米）；没有历史点返回 -1 */
    public static double distanceFromLast(Context ctx, double lat, double lng) {
        List<JSONObject> list = load(ctx);
        if (list.isEmpty()) return -1;
        JSONObject last = list.get(list.size() - 1);
        return distance(last.optDouble("lat"), last.optDouble("lng"), lat, lng);
    }

    public static long lastTime(Context ctx) {
        List<JSONObject> list = load(ctx);
        return list.isEmpty() ? 0L : list.get(list.size() - 1).optLong("t", 0L);
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
