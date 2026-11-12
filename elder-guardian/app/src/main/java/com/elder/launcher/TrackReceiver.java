package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 轨迹采样：每 10 分钟看一次位置，只有「真的动了」才记一个点。
 *
 * 判断标准（满足其一就记）：
 * - 距离上一个点超过 80 米
 * - 距离上次记录超过 30 分钟（人没动也留个痕迹，看得出"一直在家"）
 *
 * 这样一天最多几十个点，几乎不耗电，家人端一次就能把整天路线取走。
 */
public class TrackReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.elder.launcher.TRACK_TICK";
    private static final String TAG = "ElderTrackTick";
    private static final long INTERVAL_MS = 10 * 60 * 1000L;
    private static final int REQ = 9401;

    public static void schedule(Context ctx) {
        if (!Prefs.getBool(ctx, Prefs.TRACK_ENABLED, true)) return;
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, TrackReceiver.class).setAction(ACTION);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, REQ, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 60 * 1000L, INTERVAL_MS, pi);
            Log.i(TAG, "轨迹采样已排期，每 " + (INTERVAL_MS / 60000) + " 分钟一次");
        } catch (Exception e) {
            Log.w(TAG, "排期失败", e);
        }
    }

    public static void cancel(Context ctx) {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, TrackReceiver.class).setAction(ACTION);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, REQ, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.getBool(context, Prefs.TRACK_ENABLED, true)) return;
        final PendingResult pending = goAsync();
        final Context ctx = context.getApplicationContext();
        new Thread(() -> {
            try {
                LocationHelper.Result r = LocationHelper.getOnce(ctx, 8000);
                if (r == null || !r.ok()) return;

                // 每次采样都记住当前位置。围栏判定靠的是这个点，不是轨迹：
                // 轨迹是"动了才记"，人待着不动时不会有新点，中继就永远不知道他在哪。
                ReportUploader.rememberPos(ctx, r.lat, r.lng, r.accuracy);

                double dist = TrackStore.distanceFromLast(ctx, r.lat, r.lng);
                long lastAt = TrackStore.lastTime(ctx);
                boolean moved = dist < 0 || dist >= TrackStore.MIN_MOVE_M;
                boolean stale = lastAt == 0 || System.currentTimeMillis() - lastAt > TrackStore.MAX_SILENCE_MS;
                if (moved || stale) {
                    TrackStore.add(ctx, r.lat, r.lng, r.accuracy);
                    Log.i(TAG, "记录轨迹点 距离上次 " + Math.round(dist) + " 米");
                }
            } catch (Exception e) {
                Log.w(TAG, "采样失败", e);
            } finally {
                pending.finish();
            }
        }, "track-sample").start();
    }
}
