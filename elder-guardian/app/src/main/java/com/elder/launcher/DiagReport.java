package com.elder.launcher;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运维诊断（短信发「诊断」就能收到这段）
 *
 * 解决的问题：老人说"手机不好用了"，家人不在身边，也说不清哪一项坏了。
 * 这里把"最容易悄悄失效"的几项一次性列出来——Device Owner、锁定任务、无障碍、通知使用权、
 * 电池白名单、定位服务、域名同步、轨迹条数、使用统计、最近崩溃。
 *
 * 每一条都是"会自己坏"的东西：系统升级、清理软件、家人误关，都会让它们失效，
 * 而界面上看不出来（老人只会觉得"不好用了"）。
 */
public final class DiagReport {

    private static final String TAG = "ElderDiag";

    private DiagReport() {
    }

    public static boolean accessibilityOn(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            return enabled.toLowerCase(Locale.ROOT)
                    .contains(ctx.getPackageName().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean notifListenerOn(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            if (enabled == null) return false;
            return enabled.toLowerCase(Locale.ROOT)
                    .contains(ctx.getPackageName().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean batteryWhitelisted(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean deviceOwner(Context ctx) {
        try {
            return ElderDeviceAdminReceiver.isDeviceOwner(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    private static String batteryText(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return "未知";
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return pct + "%" + (bm.isCharging() ? "（充电中）" : "");
        } catch (Exception e) {
            return "未知";
        }
    }

    private static String signalText(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "未知";
            int level = -1;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) level = ss.getLevel();
            }
            String carrier = tm.getNetworkOperatorName();
            if (level < 0) return (carrier == null || carrier.isEmpty()) ? "无卡/无信号" : carrier;
            return level + " 格" + (carrier == null || carrier.isEmpty() ? "" : " " + carrier);
        } catch (Exception e) {
            return "未知";
        }
    }

    private static String crashText(Context ctx) {
        String c = Prefs.getString(ctx, "last_crash", "");
        if (c == null || c.isEmpty()) return "无";
        return c;
    }

    /** 人看的版本（短信正文） */
    public static String buildText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("【守护诊断】").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append("  Android ")
                    .append(Build.VERSION.RELEASE).append('\n');
            sb.append("版本：").append(version(ctx)).append('\n');
            sb.append("设备所有者：").append(deviceOwner(ctx) ? "是" : "否（锁定/禁装会失效）").append('\n');
            sb.append("锁定任务：").append(Prefs.getBool(ctx, Prefs.LOCK_TASK, false) ? "开" : "关").append('\n');
            sb.append("无障碍：").append(accessibilityOn(ctx) ? "正常" : "未开启（看门狗/悬浮键失效）").append('\n');
            sb.append("通知使用权：").append(notifListenerOn(ctx) ? "正常" : "未开启（通知拦不住）").append('\n');
            sb.append("电池白名单：").append(batteryWhitelisted(ctx) ? "是" : "否（容易被系统杀）").append('\n');
            sb.append("定位服务：").append(LocateServer.RUNNING ? "运行中" : "未运行").append('\n');
            sb.append("域名同步：").append(shortMsg(DdnsUpdater.lastMessage(ctx))).append('\n');
            sb.append("电量：").append(batteryText(ctx)).append("    信号：").append(signalText(ctx)).append('\n');
            sb.append("轨迹：").append(TrackStore.load(ctx).size()).append(" 个点")
                    .append("    使用统计：").append(UsageStore.hasPermission(ctx) ? "可用" : "缺权限").append('\n');
            sb.append("最近崩溃：").append(crashText(ctx));
        } catch (Exception e) {
            sb.append("诊断生成失败：").append(e.getMessage());
        }
        return sb.toString();
    }

    private static String shortMsg(String msg) {
        if (msg == null || msg.isEmpty()) return "还没同步过";
        String m = msg.replace('\n', ' ');
        return m.length() > 40 ? m.substring(0, 40) + "…" : m;
    }

    public static JSONObject buildJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("time", System.currentTimeMillis());
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("version", version(ctx));
            o.put("deviceOwner", deviceOwner(ctx));
            o.put("lockTask", Prefs.getBool(ctx, Prefs.LOCK_TASK, false));
            o.put("accessibility", accessibilityOn(ctx));
            o.put("notifListener", notifListenerOn(ctx));
            o.put("batteryWhitelist", batteryWhitelisted(ctx));
            o.put("locateRunning", LocateServer.RUNNING);
            o.put("ddnsMsg", DdnsUpdater.lastMessage(ctx));
            o.put("battery", batteryText(ctx));
            o.put("signal", signalText(ctx));
            o.put("trackPoints", TrackStore.load(ctx).size());
            o.put("usagePermission", UsageStore.hasPermission(ctx));
            o.put("lastCrash", crashText(ctx));
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * 远程「修复」：把能自己修的都修一遍。
     * 修不了的（无障碍/通知使用权需要系统设置里点一下）如实告诉家人。
     */
    public static String repair(Context ctx) {
        StringBuilder sb = new StringBuilder("【守护修复】\n");
        try {
            // 1. 重下发设备限制（白名单、禁装、禁卸、防卸载）
            ElderDeviceAdminReceiver.enableAllRestrictions(ctx);
            sb.append("· 设备限制：已重下发\n");

            // 2. 定位与域名服务，开着就拉起来
            if (Prefs.getBool(ctx, Prefs.LOCATE_SERVER, false)) {
                if (!LocateServer.RUNNING) LocateServer.start(ctx);
                sb.append("· 定位服务：").append(LocateServer.RUNNING ? "已拉起" : "启动失败").append('\n');
            }
            try {
                if (Prefs.getBool(ctx, "ddns_enabled", false)) {
                    DdnsService.start(ctx);
                    sb.append("· 域名同步：已重启\n");
                }
            } catch (Exception ignored) {
            }

            // 3. 把主界面顶回前台（老人可能停在了别处）
            try {
                android.content.Intent i = new android.content.Intent(ctx, MainLauncherActivity.class);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                sb.append("· 主界面：已拉回\n");
            } catch (Exception e) {
                Log.w(TAG, "拉回主界面失败", e);
            }

            // 4. 需要人工的项，直接说清楚
            if (!accessibilityOn(ctx)) {
                sb.append("· 无障碍：需要手动开（设置 → 辅助功能 → 无障碍 → 守护）\n");
            }
            if (!notifListenerOn(ctx)) {
                sb.append("· 通知使用权：需要手动开（设置 → 通知 → 通知使用权）\n");
            }
            if (!batteryWhitelisted(ctx)) {
                sb.append("· 电池白名单：需要手动加（设置 → 电池 → 应用启动管理）\n");
            }
        } catch (Exception e) {
            sb.append("修复出错：").append(e.getMessage());
        }
        return sb.toString();
    }

    public static String version(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + "(" + pi.versionCode + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    public static String now(Context ctx) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
    }
}
