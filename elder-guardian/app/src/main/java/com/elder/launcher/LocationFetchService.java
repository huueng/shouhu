package com.elder.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * 定位取点服务（短时前台服务）
 *
 * 为什么需要前台服务：
 * - 手机锁屏/桌面不在前台时，普通后台拿不到定位（Android 10 限制）
 * - 前台服务带 location 类型，可以正常取点，且系统不容易杀
 * - 取完立刻 stopSelf，不留常驻通知，不耗电
 *
 * 触发来源：短信指令 / 家人号码响铃挂断 / 设置页测试
 */
public class LocationFetchService extends Service {

    private static final String TAG = "ElderLocation";
    public static final String EXTRA_REPLY_TO = "reply_to";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_INTERVAL = "interval";
    public static final String EXTRA_REASON = "reason";

    private static final String CHANNEL_ID = "elder_location";
    private static final int NOTIF_ID = 1021;

    private volatile boolean stopped = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    /** 统一入口：所有触发方都走这里 */
    public static void start(Context ctx, String replyTo, int count, int intervalSec, String reason) {
        try {
            Intent i = new Intent(ctx, LocationFetchService.class);
            i.putExtra(EXTRA_REPLY_TO, replyTo);
            i.putExtra(EXTRA_COUNT, count);
            i.putExtra(EXTRA_INTERVAL, intervalSec);
            i.putExtra(EXTRA_REASON, reason);
            ContextCompat.startForegroundService(ctx, i);
        } catch (Exception e) {
            Log.e(TAG, "启动定位服务失败", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String replyTo = LocConfig.normalize(intent.getStringExtra(EXTRA_REPLY_TO));
        int count = Math.max(1, Math.min(20, intent.getIntExtra(EXTRA_COUNT, 1)));
        int interval = Math.max(20, Math.min(600, intent.getIntExtra(EXTRA_INTERVAL, 60)));
        String reason = intent.getStringExtra(EXTRA_REASON);

        startForeground(NOTIF_ID, buildNotification("正在获取位置…"));

        if (replyTo.isEmpty()) {
            Log.w(TAG, "没有回传号码，任务结束");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            // 已有任务在跑，避免多个任务同时抢 GPS；本次直接交给已有任务收尾
            Log.w(TAG, "已有定位任务进行中，忽略本次请求 reason=" + reason);
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        worker = new Thread(() -> runTask(replyTo, count, interval, reason), "elder-loc-task");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runTask(String replyTo, int count, int intervalSec, String reason) {
        Log.i(TAG, "开始定位 reason=" + reason + " 次数=" + count + " 间隔=" + intervalSec + "s");
        try {
            for (int i = 0; i < count && !stopped; i++) {
                boolean online = LocationHelper.hasNetwork(this);
                updateNotification(count > 1
                        ? "正在跟踪位置 " + (i + 1) + "/" + count
                        : (online ? "正在获取位置…" : "没有网络，正在等 GPS 定位（可能要一分钟）"));

                LocationHelper.Result r = LocationHelper.getOnce(this, 25000);
                String text;
                if (r == null || !r.ok()) {
                    text = "定位失败：" + (r == null ? "未知原因" : r.error)
                            + "\n请确认手机的「位置信息」已打开，并拿到窗边或室外再试"
                            + "（没有网络时只能靠 GPS，室内基本定不到）。";
                } else {
                    text = LocationHelper.describe(r);
                    if (count > 1) {
                        text = "【" + (i + 1) + "/" + count + "】\n" + text;
                    }
                    Log.i(TAG, "定位成功 " + r.lat + "," + r.lng
                            + " 精度=" + r.accuracy + "m 地址=" + r.address);
                }
                SmsResponder.send(this, replyTo, text);

                if (i < count - 1 && !stopped) {
                    sleepInterruptible(intervalSec * 1000L);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "定位任务异常", t);
            try {
                SmsResponder.send(this, replyTo, "定位出错，请稍后重试。");
            } catch (Exception ignored) {
            }
        } finally {
            stopped = true;
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            Log.i(TAG, "定位任务结束");
        }
    }

    /** 间隔等待，每 0.5 秒检查一次退出标记，保证能及时停止 */
    private void sleepInterruptible(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (!stopped && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "elder:location");
            wakeLock.setReferenceCounted(false);
            // 最多 15 分钟，无论如何都会释放
            wakeLock.acquire(15 * 60 * 1000L);
        } catch (Exception e) {
            Log.w(TAG, "获取 WakeLock 失败", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "位置查询",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("家人短信查询位置时短暂使用");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainLauncherActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("位置查询")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi);
        return b.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopped = true;
        releaseWakeLock();
        super.onDestroy();
    }
}
