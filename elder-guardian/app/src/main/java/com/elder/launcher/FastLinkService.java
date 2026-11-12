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
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 快通道：让"家人点一下，老人机几秒内动起来"成为可能。
 *
 * 背景：原来的命令通道只有一个方向 —— 老人机每 10 分钟上报时，中继在响应里捎一条命令。
 * 也就是说家人按「查询位置」最坏要等 10 分钟，体验上就是"没反应"。
 *
 * 做法：老人机主动向中继挂一条长轮询 GET /api/wait?t=25。
 * 中继一有命令立刻返回，这里执行完马上补发一次上报把结果带回去。
 * 全程由老人机发起，不需要公网入站、不需要端口映射，穿 NAT / CF 隧道都能用。
 *
 * 代价：相当于每 25 秒一次心跳，移动数据下约 3–4 MB/天，耗电比纯 10 分钟上报高。
 * 所以做成可关闭（设置里「秒级响应」/ 管理命令 fastlink off），
 * 并且电量低又没充电时自动暂停。
 */
public class FastLinkService extends Service {

    private static final String TAG = "ElderFastLink";
    private static final String CHANNEL = "elder_fastlink";
    private static final int NOTIF_ID = 1031;
    private static final int WAIT_SECONDS = 25;

    private volatile boolean running = false;
    private Thread worker;

    public static void start(Context ctx) {
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, FastLinkService.class));
        } catch (Exception e) {
            Log.w(TAG, "启动快通道失败", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, FastLinkService.class));
        } catch (Exception ignored) {
        }
    }

    /** 设置项：默认开。关掉就退回"只靠 10 分钟上报"的老行为。 */
    public static boolean enabled(Context ctx) {
        return Prefs.getBool(ctx, Prefs.FAST_LINK, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        Prefs.putBool(ctx, Prefs.FAST_LINK, on);
        if (on) start(ctx); else stop(ctx);
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
        if (!enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::loop, "elder-fastlink");
            worker.start();
            Log.i(TAG, "快通道已启动");
        }
        // 被系统杀掉后尽量重来
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        Log.i(TAG, "快通道已停止");
        super.onDestroy();
    }

    private void loop() {
        int backoff = 0;
        while (running) {
            try {
                if (!enabled(this)) break;

                // 电量很低又没充电时让位给"保命"：不再常连
                if (shouldYield()) {
                    sleepQuiet(60000);
                    continue;
                }

                String base = apiBase(this);
                String token = deviceToken(this);
                if (base == null || token == null) {
                    sleepQuiet(60000);
                    continue;
                }

                JSONObject cmd = waitOnce(base, token);
                if (cmd != null) {
                    Log.i(TAG, "快通道收到命令：" + cmd.optString("cmd"));
                    RemoteCommand.accept(this, cmd);
                    if (RemoteCommand.runPending(this) != null) {
                        // 执行完立刻回传，别等下一次周期上报
                        ReportUploader.uploadAsync(this, false, null);
                    }
                    backoff = 0;
                    continue;
                }
                backoff = 0;
                sleepQuiet(300);
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                backoff = Math.min(60, backoff == 0 ? 3 : backoff * 2);
                Log.w(TAG, "快通道异常，退避 " + backoff + "s", e);
                sleepQuiet(backoff * 1000L);
            }
        }
    }

    /** 挂一次长轮询；有命令返回命令，没有返回 null */
    private JSONObject waitOnce(String base, String token) throws Exception {
        String url = base + "/api/wait?k=" + URLEncoder.encode(token, "UTF-8")
                + "&t=" + WAIT_SECONDS;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            // 比服务端的 25 秒多留 10 秒：宁可自己先超时重连，也别被中间层掐断
            conn.setReadTimeout((WAIT_SECONDS + 10) * 1000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "wait 返回 " + code);
                throw new IllegalStateException("http " + code);
            }
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject o = new JSONObject(sb.toString());
            if (!o.optBoolean("ok", false)) return null;
            return o.optJSONObject("cmd");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean shouldYield() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return false;
            int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();
            return level > 0 && level <= 10 && !charging;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从上报地址里拆出 origin：https://host[:port]/api/report?k=xxx → https://host[:port]
     * 顺便支持用户填的是中继根地址（没写 /api/report）。
     */
    static String apiBase(Context ctx) {
        try {
            String raw = Prefs.getString(ctx, Prefs.REPORT_URL, "").trim();
            int i = raw.indexOf("/api/");
            if (i > 0) return raw.substring(0, i);
            // 兜底：至少是 http(s)://host[:port]
            URL u = new URL(raw);
            String port = u.getPort() > 0 ? (":" + u.getPort()) : "";
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Exception e) {
            return null;
        }
    }

    /** 设备令牌：优先从上报地址的 ?k= 里取 */
    static String deviceToken(Context ctx) {
        try {
            String raw = Prefs.getString(ctx, Prefs.REPORT_URL, "").trim();
            int i = raw.indexOf("k=");
            if (i < 0) return null;
            String t = raw.substring(i + 2);
            int amp = t.indexOf('&');
            if (amp > 0) t = t.substring(0, amp);
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "快速响应", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("保持与中继的连接，家人查询位置时几秒内响应");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainLauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("守护在线")
                .setContentText("家人查询位置可秒级响应（耗电略高，可在设置里关掉）")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pi)
                .build();
    }
}
