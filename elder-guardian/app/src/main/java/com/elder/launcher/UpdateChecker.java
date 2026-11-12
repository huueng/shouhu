package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新（不自动装，只告诉家人有新版本）
 *
 * 为什么不做静默安装：Device Owner 确实能静默装，但那等于给这个 App 一个"随时替换自己"的权限，
 * 一旦更新地址被别人改了就是灾难。所以这里只做"发现新版 + 给出下载链接"，
 * 装不装由家人按一下——多一步，但不会出事。
 *
 * 更新源就是家人自己放的一个静态 JSON（GitHub Raw / 自己的网站都行）：
 *   { "versionCode": 4, "versionName": "2.1", "apk": "https://.../app-release.apk", "notes": "..." }
 */
public final class UpdateChecker {

    private static final String TAG = "ElderUpdate";
    private static final int TIMEOUT = 12000;

    private UpdateChecker() {
    }

    public interface Callback {
        void onResult(boolean ok, String message, String apkUrl);
    }

    public static boolean configured(Context ctx) {
        String url = Prefs.getString(ctx, "update_url", "");
        return url != null && !url.trim().isEmpty();
    }

    public static void checkAsync(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        if (!configured(app)) {
            if (cb != null) cb.onResult(false, "还没填更新地址", null);
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String msg;
            String apk = null;
            try {
                JSONObject o = new JSONObject(httpGet(Prefs.getString(app, "update_url", "").trim()));
                int latest = o.optInt("versionCode", 0);
                String name = o.optString("versionName", "");
                apk = o.optString("apk", "");
                int mine = myVersionCode(app);
                if (latest <= mine) {
                    msg = "已是最新版（" + DiagReport.version(app) + "）";
                    ok = true;
                } else {
                    msg = "发现新版 " + name + "（当前 " + DiagReport.version(app) + "）"
                            + (o.optString("notes", "").isEmpty() ? "" : "\n" + o.optString("notes", ""));
                    ok = true;
                }
            } catch (Exception e) {
                msg = "检查失败：" + e.getMessage();
            }
            Prefs.putString(app, "update_last", msg);
            Prefs.putLong(app, "update_last_at", System.currentTimeMillis());
            if (cb != null) cb.onResult(ok, msg, apk);
        }, "update-check").start();
    }

    private static int myVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== 每天自动查一次 ====================

    public static final String ACTION = "com.elder.launcher.UPDATE_TICK";
    private static final long INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int REQ = 9403;

    public static void schedule(Context ctx) {
        if (!configured(ctx)) return;
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, UpdateReceiver.class).setAction(ACTION);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, REQ, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 30 * 60 * 1000L, INTERVAL_MS, pi);
        } catch (Exception e) {
            Log.w(TAG, "更新检查排期失败", e);
        }
    }

    public static class UpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!configured(context)) return;
            checkAsync(context, null);
        }
    }
}
