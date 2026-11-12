package com.elder.launcher;

import android.app.KeyguardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 老人机「体检报告」：家人端一次请求就能看到手机状态。
 *
 * 为什么这些字段值得同步：老人说不清"手机怎么了"，
 * 但"电量 12% 且没在充电""音量 0""存储满了""微信今天 0 分钟"这些都是能提前发现问题的信号。
 */
public final class StatusReport {

    private StatusReport() {
    }

    public static JSONObject build(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("time", System.currentTimeMillis());

            // 电量：优先读 ACTION_BATTERY_CHANGED 的 sticky 广播 —— 和系统状态栏同一个来源，
            // 比 BATTERY_PROPERTY_CAPACITY 更稳（个别 ROM 上后者会给 -1 或旧值）。
            int pct = -1;
            boolean chg = false;
            int tempTenth = Integer.MIN_VALUE;
            try {
                android.content.Intent sticky = ctx.registerReceiver(null,
                        new android.content.IntentFilter(
                                android.content.Intent.ACTION_BATTERY_CHANGED));
                if (sticky != null) {
                    int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    if (level >= 0 && scale > 0) pct = Math.round(level * 100f / scale);
                    chg = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                    tempTenth = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,
                            Integer.MIN_VALUE);
                }
            } catch (Exception ignored) {
            }
            if (pct < 0) {
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    try {
                        chg = bm.isCharging();
                    } catch (Exception ignored) {
                    }
                }
            }
            o.put("battery", pct);
            o.put("charging", chg);
            // 电池温度（0.1℃）：老人机常年插着充电器，电池鼓包/发热是能提前看出来的
            if (tempTenth != Integer.MIN_VALUE && tempTenth > -1000) {
                o.put("battTemp", Math.round(tempTenth / 10f * 10) / 10f);
            }
            // 电量的采样时刻：家人端要能说清"这是几点钟的电量"，否则会以为是实时值
            o.put("batteryT", System.currentTimeMillis());

            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                o.put("screenOn", Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn());
            }

            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) o.put("locked", km.isKeyguardLocked());

            // 网络：家庭 WiFi 名字 / 是否移动数据
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, mobile = false;
            if (cm != null) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (cap != null) {
                    wifi = cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    mobile = cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                }
            }
            o.put("wifi", wifi);
            o.put("mobile", mobile);
            o.put("wifiName", wifiName(ctx));

            // 内网 WIFI 模式（不插卡的看视频机）：家人端要知道"没信号"是正常现象，
            // 以及现在连的是不是家里那个网、IPv6 有没有下发（决定中继能不能直连）
            o.put("intranet", IntranetMode.isOn(ctx));
            o.put("netFlag", IntranetMode.networkFlag(ctx));
            o.put("ipv6", IntranetMode.ipv6(ctx));

            // 音量：老人误按成静音是高频问题。
            // 分开记"铃声"和"媒体"：看视频听不见是媒体音量，接不到电话是铃声音量，
            // 两个都可能是 0，只报一个的话另一种情况家人看不出来。
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int vol = am.getStreamVolume(android.media.AudioManager.STREAM_RING);
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING);
                o.put("ringVolume", vol);
                o.put("ringMax", max);
                o.put("muted", vol == 0);
                try {
                    o.put("volMusic", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
                    o.put("volMusicMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                    // 勿扰/静音模式：铃声是 0 但媒体还有声音时，家人要能分清是哪种
                    o.put("ringerMode", am.getRingerMode());
                } catch (Exception ignored) {
                }
            }

            // 下面这几项都是"顺手读一下"，不开传感器、不唤醒设备，对耗电没有可测量的影响。
            // 但对家人有用：内存吃紧、开机好几天没重启，往往就是"手机变卡了"的原因。
            try {
                android.app.ActivityManager amg = (android.app.ActivityManager)
                        ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (amg != null) {
                    android.app.ActivityManager.MemoryInfo mi =
                            new android.app.ActivityManager.MemoryInfo();
                    amg.getMemoryInfo(mi);
                    o.put("memFreeMB", mi.availMem / 1024 / 1024);
                    o.put("memTotalMB", mi.totalMem / 1024 / 1024);
                    o.put("memLow", mi.lowMemory);
                }
            } catch (Exception ignored) {
            }
            try {
                o.put("uptimeMin", android.os.SystemClock.elapsedRealtime() / 60000);
            } catch (Exception ignored) {
            }

            try {
                StatFs stat = new StatFs(ctx.getFilesDir().getAbsolutePath());
                long freeMb = stat.getAvailableBlocksLong() * stat.getBlockSizeLong() / 1024 / 1024;
                o.put("storageFreeMB", freeMb);
            } catch (Exception ignored) {
            }

            o.put("lockTask", Prefs.getBool(ctx, Prefs.LOCK_TASK, false));
            o.put("locateOn", LocateServer.RUNNING);
            o.put("ddnsMsg", DdnsUpdater.lastMessage(ctx));
            o.put("trackPoints", TrackStore.load(ctx).size());
            o.put("version", version(ctx));
            o.put("topApps", UsageStore.todayTop(ctx, 5));
        } catch (Exception e) {
            try {
                o.put("ok", false);
                o.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /** 家庭 WiFi 名字（拿不到返回空串，不报错） */
    private static String wifiName(Context ctx) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>")) return "";
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 今天用得最多的 3 个应用（需要「使用情况访问」权限） */
    private static JSONArray topApps(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return arr;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            long start = c.getTimeInMillis();
            java.util.List<android.app.usage.UsageStats> stats =
                    usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                            start, System.currentTimeMillis());
            if (stats == null) return arr;

            java.util.Map<String, Long> map = new java.util.HashMap<>();
            for (android.app.usage.UsageStats s : stats) {
                if (s.getTotalTimeInForeground() <= 0) continue;
                String pkg = s.getPackageName();
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue;
                Long old = map.get(pkg);
                map.put(pkg, (old == null ? 0 : old) + s.getTotalTimeInForeground());
            }
            java.util.List<java.util.Map.Entry<String, Long>> list =
                    new java.util.ArrayList<>(map.entrySet());
            java.util.Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));

            android.content.pm.PackageManager pm = ctx.getPackageManager();
            for (int i = 0; i < list.size() && i < 3; i++) {
                JSONObject o = new JSONObject();
                String pkg = list.get(i).getKey();
                o.put("pkg", pkg);
                try {
                    o.put("name", pm.getApplicationLabel(
                            pm.getApplicationInfo(pkg, 0)).toString());
                } catch (Exception e) {
                    o.put("name", pkg);
                }
                o.put("minutes", list.get(i).getValue() / 60000);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private static String version(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
