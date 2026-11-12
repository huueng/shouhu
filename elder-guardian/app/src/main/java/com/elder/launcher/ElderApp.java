package com.elder.launcher;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Application 类
 * 负责应用级初始化：scheduleAllTimers 等
 */
public class ElderApp extends Application {

    private static final String TAG = "ElderApp";
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        Log.i(TAG, "老人守护 App 启动");

        // 深色模式：跟随家人在设置页的开关
        try {
            boolean dark = getSharedPreferences("elder_config", MODE_PRIVATE)
                    .getBoolean("dark_mode", false);
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(dark
                    ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        } catch (Exception e) {
            Log.w(TAG, "应用深色模式失败", e);
        }

        // 崩溃记录：诊断短信/体检要能看到"最近是不是崩过"
        installCrashRecorder();

        // 注册周期定时器（如果 Device Owner 已激活）
        if (ElderDeviceAdminReceiver.isDeviceOwner(this)) {
            scheduleAllTimers(this);
        }

        // 来电响铃挂断触发定位：Android 8+ 对清单里的 PHONE_STATE 广播有后台限制，
        // 进程存活期间再动态注册一份，老人在用微信等其它 App 时也能正常触发
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(
                    android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(new CallTriggerReceiver(), f);
        } catch (Exception e) {
            Log.w(TAG, "注册来电监听失败", e);
        }
    }

    public static Context getAppContext() {
        return appContext;
    }

    /**
     * 记录最近一次崩溃到 prefs（诊断里会显示）。
     * 只记一行"时间 + 异常类型 + 首行信息"，不存堆栈——够判断，也不占地方。
     */
    private void installCrashRecorder() {
        try {
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
                try {
                    String head = ex == null ? "unknown" : (ex.getClass().getSimpleName()
                            + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
                    if (head.length() > 80) head = head.substring(0, 80);
                    getSharedPreferences("elder_config", MODE_PRIVATE).edit()
                            .putString("last_crash",
                                    new java.text.SimpleDateFormat("MM-dd HH:mm",
                                            java.util.Locale.CHINA).format(new java.util.Date())
                                            + " " + head)
                            .apply();
                } catch (Exception ignored) {
                }
                if (prev != null) prev.uncaughtException(thread, ex);
            });
        } catch (Exception e) {
            Log.w(TAG, "安装崩溃记录失败", e);
        }
    }

    /**
     * 把本应用注册成系统默认桌面（HOME）
     *
     * 注意：华为 EMUI / 荣耀 Magic UI 上 DevicePolicyManager.addPersistentPreferredActivity
     * 不生效（实测），必须用 ADB 的 cmd package set-home-activity，
     * 所以这里只作为其他机型的兜底路径。
     */
    public static void setPersistentHome(Context ctx) {
        try {
            android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(ctx);
            android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(ctx);
            android.content.IntentFilter f =
                    new android.content.IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            android.content.ComponentName home = new android.content.ComponentName(
                    ctx.getPackageName(), MainLauncherActivity.class.getName());
            dpm.addPersistentPreferredActivity(admin, f, home);
            ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE)
                    .edit().putBoolean("persistent_home", true).apply();
            Log.i(TAG, "已设为默认桌面");
        } catch (Exception e) {
            Log.w(TAG, "设置默认桌面失败", e);
        }
    }

    /** 取消默认桌面设置，恢复系统原桌面（电视端不用时） */
    public static void clearPersistentHome(Context ctx) {
        try {
            android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(ctx);
            android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(ctx);
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.getPackageName());
            ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE)
                    .edit().putBoolean("persistent_home", false).apply();
            Log.i(TAG, "已恢复系统桌面");
        } catch (Exception e) {
            Log.w(TAG, "恢复系统桌面失败", e);
        }
    }

    /**
     * 注册所有周期定时器（AlarmManager）
     * 开机时也调这个（BootReceiver 里）
     */
    public static void scheduleAllTimers(Context ctx) {
        // 轨迹采样：动了才记点，家人端来取
        TrackReceiver.schedule(ctx);
        // 主动上报（填了地址才生效）与每日检查更新
        ReportUploader.schedule(ctx);
        UpdateChecker.schedule(ctx);
        // 每天刷一次使用统计，家人拉 /usage 或发「使用」时有数据
        UsageStore.refresh(ctx);

        Log.i(TAG, "注册所有周期定时器");

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();

        // 先撤销旧的报时 / 天气定时器，避免家人取消某个时间点后旧闹钟还在响
        cancelSlots(am, ctx, HourlyChimeReceiver.ACTION_HOURLY, 1100);
        cancelSlots(am, ctx, HourlyChimeReceiver.ACTION_WEATHER, 1003);

        // 1. 语音报时 —— 按家人选择的时间点（默认 8 / 12 / 18 点）
        String chimeHours = prefs.getString("chime_hours", "8,12,18");
        String[] chimeArr = chimeHours.split(",");
        for (int i = 0; i < chimeArr.length; i++) {
            int hh;
            try {
                hh = Integer.parseInt(chimeArr[i].trim());
            } catch (Exception e) {
                continue;
            }
            if (hh < 0 || hh > 23) continue;

            Intent chime = new Intent(ctx, HourlyChimeReceiver.class);
            chime.setAction(HourlyChimeReceiver.ACTION_HOURLY);
            PendingIntent piChime = PendingIntent.getBroadcast(ctx, 1100 + i, chime,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, hh);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() < now) {
                c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                    c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, piChime);
        }

        // 2. 定时清理后台 —— 周期可配（默认 120 分钟）
        int killMinutes = prefs.getInt("kill_interval_minutes", 120);
        if (killMinutes < 15) killMinutes = 15;
        long killInterval = killMinutes * 60_000L;

        Intent kill = new Intent(ctx, HourlyChimeReceiver.class);
        kill.setAction(HourlyChimeReceiver.ACTION_KILL);
        PendingIntent piKill = PendingIntent.getBroadcast(ctx, 1002, kill,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + killInterval, killInterval, piKill);

        // 3. 每日天气播报 —— 支持多个时间点（默认 8 / 12 / 18 点）
        String hoursStr = prefs.getString("weather_speak_hours", "8,12,18");
        String[] hours = hoursStr.split(",");
        for (int i = 0; i < hours.length; i++) {
            int hh;
            try {
                hh = Integer.parseInt(hours[i].trim());
            } catch (Exception e) {
                continue;
            }
            if (hh < 0 || hh > 23) continue;

            Intent weather = new Intent(ctx, HourlyChimeReceiver.class);
            weather.setAction(HourlyChimeReceiver.ACTION_WEATHER);
            PendingIntent piWeather = PendingIntent.getBroadcast(ctx, 1003 + i, weather,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, hh);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() < now) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                    cal.getTimeInMillis(), AlarmManager.INTERVAL_DAY, piWeather);
        }

        Log.i(TAG, "定时器注册完成");
    }

    /** 撤销某一组按小时注册的旧闹钟（最多 24 个时间点） */
    private static void cancelSlots(AlarmManager am, Context ctx, String action, int baseCode) {
        for (int i = 0; i < 24; i++) {
            Intent it = new Intent(ctx, HourlyChimeReceiver.class);
            it.setAction(action);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, baseCode + i, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            pi.cancel();
        }
    }
}
