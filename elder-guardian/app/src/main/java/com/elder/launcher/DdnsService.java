package com.elder.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * DDNS 常驻服务
 *
 * 每 5 分钟检查一次本机 IPv6，变了就推给 Dynv6；网络切换（WiFi ↔ 移动数据）时立即更新一次。
 * 这样家人端永远只需要记住一个固定域名。
 *
 * 用前台服务是因为：后台定时任务在 Android 10 上会被压制，而这里恰恰要求"网络一变就更新"。
 */
public class DdnsService extends Service {

    private static final String TAG = "ElderDdnsSvc";
    private static final String CHANNEL_ID = "elder_ddns";
    private static final long INTERVAL_MS = 5 * 60 * 1000L;

    public static volatile boolean RUNNING = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback netCallback;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, DdnsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, DdnsService.class));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            DdnsUpdater.updateAsync(DdnsService.this, false, null);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2203, buildNotification());
        if (RUNNING) {
            // 已经在跑，补一次更新就够（例如从设置页点"立即更新"顺带启动）
            handler.removeCallbacks(tick);
            handler.post(tick);
            return START_NOT_STICKY;
        }
        RUNNING = true;
        handler.post(tick);
        registerNetworkCallback();
        Log.i(TAG, "DDNS 服务已启动，每 " + (INTERVAL_MS / 60000) + " 分钟检查一次");
        return START_NOT_STICKY;
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // 切网后地址通常变了，立刻更新一次
                    handler.postDelayed(() -> DdnsUpdater.updateAsync(DdnsService.this, true, null), 3000);
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            Log.w(TAG, "注册网络监听失败", e);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "域名同步",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("域名同步已开启")
                .setContentText(DdnsUpdater.hostname(this) + " 会跟着本机地址自动更新")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        handler.removeCallbacksAndMessages(null);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && netCallback != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        Prefs.putBool(this, "ddns_enabled", false);
        super.onDestroy();
    }
}
