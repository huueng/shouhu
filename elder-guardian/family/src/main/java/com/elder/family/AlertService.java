package com.elder.family;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 常驻告警服务：关掉 App 也能收到围栏与失联提醒。
 *
 * 为什么要前台服务：Android 8 之后后台线程随时会被冻结，
 * 只有带常驻通知的前台服务能稳定跑住。代价是通知栏会有一个小图标。
 *
 * 两种数据源都支持：
 *   · relay —— 问 NAS 中继要事件（/api/events），围栏在服务端算
 *   · direct —— 直接问老人机（/status、/loc），围栏在本地算
 *
 * 本地算围栏必须带精度缓冲：老人机室内多为网络定位，精度常 300–1000 米，
 * 而围栏半径可能只有 300–500 米，直接比半径会疯狂误报。
 */
public class AlertService extends Service {

    private static final String TAG = "ElderAlert";

    private static final String CH_SERVICE = "family_service";
    private static final String CH_ALERT = "family_alert";

    private static final int NOTI_SERVICE = 1001;
    private static final long LOOP_MS = 60 * 1000L;          // 主循环间隔
    private static final long DIRECT_POS_MS = 5 * 60 * 1000L; // 直连模式取位置的间隔

    private volatile boolean running = true;
    private Thread worker;

    /** relay：每个老人上次拉到的事件游标 */
    private final Map<String, Long> cursors = new HashMap<>();
    /** direct：连续"确定在外"的次数，满 2 次才报 */
    private final Map<String, Integer> outHits = new HashMap<>();
    /** direct：当前是否在围栏内 */
    private final Map<String, Boolean> insideState = new HashMap<>();
    /** direct：上次取位置的时间 */
    private final Map<String, Long> lastPosAt = new HashMap<>();
    /** 低电/静音这类状态提醒的去重键 */
    private final Map<String, String> lastStatusKey = new HashMap<>();

    private int notiSeed = 2000;

    // ===== 启停 =====

    public static void ensureRunning(Context ctx) {
        try {
            Intent i = new Intent(ctx, AlertService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "启动常驻失败", e);
        }
    }

    public static void stopService(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, AlertService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_SERVICE, serviceNotification(0));
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::loop, "alert-loop");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    // ===== 主循环 =====

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (Exception e) {
                Log.w(TAG, "轮询异常", e);
            }
            try {
                Thread.sleep(LOOP_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void tick() {
        String raw = getSharedPreferences("alerts", MODE_PRIVATE).getString("data", "[]");
        JSONArray arr;
        try {
            arr = new JSONArray(raw);
        } catch (Exception e) {
            return;
        }

        int active = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null || !e.optBoolean("enabled", true)) continue;
            active++;
            try {
                if ("relay".equals(e.optString("mode", "direct"))) {
                    checkRelay(e);
                } else {
                    checkDirect(e);
                }
            } catch (Exception ex) {
                Log.w(TAG, "检查失败", ex);
            }
        }
        updateServiceNoti(active);
    }

    // ===== 中继模式：拉事件 =====

    private void checkRelay(JSONObject e) {
        String id = e.optString("id");
        String base = trimSlash(e.optString("base"));
        String token = e.optString("token");
        String name = e.optString("name", "老人");
        if (base.isEmpty() || token.isEmpty()) return;

        long since = cursors.containsKey(id) ? cursors.get(id) : 0L;
        String url = base + "/api/events?wait=0&k=" + enc(token) + "&since=" + since;
        JSONObject j = getJson(url, 8000);
        if (j == null) return;

        JSONArray evs = j.optJSONArray("events");
        if (evs != null) {
            for (int i = 0; i < evs.length(); i++) {
                JSONObject ev = evs.optJSONObject(i);
                if (ev != null) fireEvent(name, ev);
            }
        }
        cursors.put(id, j.optLong("cursor", since));
    }

    private void fireEvent(String name, JSONObject ev) {
        String kind = ev.optString("kind");
        String text = ev.optString("text");

        boolean urgent = "fence_out".equals(kind) || "offline".equals(kind);

        String title;
        String body;
        if ("fence_out".equals(kind)) {
            title = name + "离开了「" + ev.optString("fenceName", "围栏") + "」";
            body = "距离围栏中心约 " + Math.round(ev.optDouble("dist", 0)) + " 米";
        } else if ("fence_in".equals(kind)) {
            title = name + "回到了「" + ev.optString("fenceName", "围栏") + "」";
            body = "";
        } else if ("offline".equals(kind)) {
            title = name + " 的手机没消息了";
            body = text;
        } else if ("online".equals(kind)) {
            title = name + " 的手机恢复上报";
            body = "";
        } else {
            title = name;
            body = text;
        }
        showAlert(title, body, urgent);
    }

    // ===== 直连模式：问老人机 =====

    private void checkDirect(JSONObject e) {
        String id = e.optString("id");
        String host = e.optString("host");
        String port = e.optString("port");
        if (port.isEmpty()) port = "8899";
        String key = e.optString("key");
        String name = e.optString("name", "老人");
        if (host.isEmpty() || key.isEmpty()) return;

        String base = "http://" + host + ":" + port;

        // 状态：低电 / 静音 顺手提一句（同一个问题不重复刷）
        JSONObject st = getJson(base + "/status?key=" + enc(key), 8000);
        if (st != null && st.optBoolean("ok", false)) {
            int bat = st.optInt("battery", -1);
            boolean charging = st.optBoolean("charging", false);
            boolean muted = st.optBoolean("muted", false);

            String warn = null;
            if (bat >= 0 && bat <= 20 && !charging) {
                warn = "电量只剩 " + bat + "%，没在充电";
            } else if (muted) {
                warn = "手机被调成静音了，来电话不会响";
            }
            String prev = lastStatusKey.get(id);
            if (warn != null && !warn.equals(prev)) {
                showAlert(name, warn, false);
                lastStatusKey.put(id, warn);
            } else if (warn == null) {
                lastStatusKey.remove(id);
            }
        }

        // 围栏：只有设了围栏才需要取位置
        JSONArray fences = fencesOf(e);
        if (fences.length() == 0) return;

        long now = System.currentTimeMillis();
        Long last = lastPosAt.get(id);
        if (last != null && now - last < DIRECT_POS_MS) return;
        lastPosAt.put(id, now);

        JSONObject loc = getJson(base + "/loc?key=" + enc(key), 30000);
        if (loc == null || !loc.optBoolean("ok", false)) return;

        double lat = loc.optDouble("lat", 0);
        double lng = loc.optDouble("lng", loc.optDouble("lon", 0));
        double acc = loc.optDouble("acc", 0);
        if (lat == 0 && lng == 0) return;

        for (int i = 0; i < fences.length(); i++) {
            JSONObject f = fences.optJSONObject(i);
            if (f == null || !f.optBoolean("enabled", true)) continue;
            judgeFence(id, name, f, lat, lng, acc);
        }
    }

    /**
     * 单个围栏的本地判定。
     * 圆形比半径，多边形用射线法；两者都按"整个误差圆都在外/内"才认，
     * 模糊带维持上一状态——老人机室内精度常 300–1000 米，硬判必然误报。
     */
    private void judgeFence(String elderId, String name, JSONObject f,
                            double lat, double lng, double acc) {
        String fid = f.optString("id", "f");
        String key = elderId + "|" + fid;
        String fname = f.optString("name", "围栏");

        boolean insideGeom;
        double dist; // 用于文案：圆是离圆心的距离，多边形是离边界的距离

        if ("polygon".equals(f.optString("shape")) && f.optJSONArray("points") != null) {
            JSONArray pts = f.optJSONArray("points");
            if (pts.length() < 3) return;
            double[][] poly = new double[pts.length()][2];
            for (int i = 0; i < pts.length(); i++) {
                JSONArray p = pts.optJSONArray(i);
                if (p == null || p.length() < 2) return;
                poly[i][0] = p.optDouble(0);
                poly[i][1] = p.optDouble(1);
            }
            insideGeom = pointInPolygon(poly, lat, lng);
            dist = boundaryDist(poly, lat, lng);

            // 多边形没有"半径"可比，精度差到这个程度就别判了
            if (acc > 400) return;
            boolean certainty = acc < dist;   // 误差圆没压到边界
            if (!certainty) return;
        } else {
            double fl = f.optDouble("lat", 0);
            double fg = f.optDouble("lng", f.optDouble("lon", 0));
            double r = f.optDouble("radius", 500);
            if (r <= 0) return;

            dist = distanceM(fl, fg, lat, lng);
            if (acc > r * 1.5) return;                 // 精度太差，等下一个点
            if (dist - acc > r) {
                insideGeom = false;
            } else if (dist + acc < r) {
                insideGeom = true;
            } else {
                return;                                // 模糊带
            }
        }

        Boolean prev = insideState.get(key);
        if (prev == null) {
            insideState.put(key, insideGeom);           // 首次只建基线
            return;
        }

        if (!insideGeom) {
            int hits = (outHits.containsKey(key) ? outHits.get(key) : 0) + 1;
            outHits.put(key, hits);
            if (hits >= 2 && prev) {
                insideState.put(key, false);
                outHits.put(key, 0);
                showAlert(name + "离开了「" + fname + "」",
                        "距离围栏约 " + Math.round(dist) + " 米", true);
            }
        } else {
            outHits.put(key, 0);
            if (!prev) {
                insideState.put(key, true);
                showAlert(name + "回到了「" + fname + "」", "", false);
            }
        }
    }

    /** 兼容老版本：单个 fence 字段也认 */
    private JSONArray fencesOf(JSONObject e) {
        JSONArray arr = e.optJSONArray("fences");
        if (arr != null && arr.length() > 0) return arr;
        JSONObject one = e.optJSONObject("fence");
        arr = new JSONArray();
        if (one != null) arr.put(one);
        return arr;
    }

    private static boolean pointInPolygon(double[][] poly, double lat, double lng) {
        boolean inside = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double yi = poly[i][0], xi = poly[i][1];
            double yj = poly[j][0], xj = poly[j][1];
            if ((yi > lat) != (yj > lat)) {
                if (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside;
            }
        }
        return inside;
    }

    /** 点到多边形边界的最短距离（米，局部等距近似） */
    private static double boundaryDist(double[][] poly, double lat, double lng) {
        double best = Double.MAX_VALUE;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            best = Math.min(best, segDist(lat, lng, poly[i][0], poly[i][1], poly[j][0], poly[j][1]));
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    private static double segDist(double lat, double lng, double lat1, double lon1,
                                  double lat2, double lon2) {
        double mPerLat = 111320.0;
        double mPerLon = 111320.0 * Math.cos(lat * Math.PI / 180);
        double px = (lng - lon1) * mPerLon, py = (lat - lat1) * mPerLat;
        double bx = (lon2 - lon1) * mPerLon, by = (lat2 - lat1) * mPerLat;
        double den = bx * bx + by * by;
        if (den == 0) return Math.hypot(px, py);
        double t = (px * bx + py * by) / den;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        return Math.hypot(px - t * bx, py - t * by);
    }

    // ===== 通知 =====

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "后台守护", NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "老人告警", NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 300, 200, 300});
        alert.setShowBadge(true);
        nm.createNotificationChannel(alert);
    }

    private Notification serviceNotification(int active) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_SERVICE)
                : new Notification.Builder(this);

        String text = active > 0
                ? ("正在守护 " + active + " 位老人")
                : "常驻已开启，但还没有配置老人";

        return b.setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("守护")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    private void updateServiceNoti(int active) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_SERVICE, serviceNotification(active));
        } catch (Exception ignored) {
        }
    }

    private void showAlert(String title, String body, boolean urgent) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, notiSeed, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH_ALERT)
                    : new Notification.Builder(this);

            b.setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(title)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(urgent ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);

            if (body != null && !body.isEmpty()) b.setContentText(body);
            if (urgent) {
                b.setDefaults(Notification.DEFAULT_VIBRATE);
                if (Build.VERSION.SDK_INT >= 21) b.setCategory(Notification.CATEGORY_ALARM);
            }

            nm.notify(notiSeed++, b.build());
        } catch (Exception e) {
            Log.w(TAG, "发通知失败", e);
        }
    }

    // ===== 工具 =====

    private static String trimSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double dLat = (lat2 - lat1) * Math.PI / 180;
        double dLng = (lng2 - lng1) * Math.PI / 180;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /** 取 JSON。失败返回 null，调用方按"这次没拿到"处理。 */
    private JSONObject getJson(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(Math.min(timeoutMs, 10000));
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
