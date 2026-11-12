package com.elder.launcher;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 健康体检（给远程命令和家人端用）
 *
 * 和「守护自检」页的区别：这里是**没有界面**的版本，家人端在中继上点一下「健康检测」，
 * 老人端跑一遍这些检查，把结果当上报的一部分带回去。
 * 检查项挑的是"坏了就一定会出问题"的那些，不追求全。
 */
public final class HealthCheck {

    private static final String TAG = "ElderHealth";

    private HealthCheck() {
    }

    public static JSONObject build(Context ctx) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        int okCount = 0;
        StringBuilder missing = new StringBuilder();

        try {
            // 1. Device Owner：锁屏固定、禁装禁卸、静默授权都靠它
            boolean isDo = ElderDeviceAdminReceiver.isDeviceOwner(ctx);
            okCount += add(items, "设备所有者", isDo,
                    isDo ? "已激活" : "未激活（锁定、禁装禁卸不可用）", missing);

            // 2. 锁定任务开关（配置项）
            boolean lock = Prefs.getBool(ctx, Prefs.LOCK_TASK, false);
            okCount += add(items, "锁定任务", lock || !isDo,
                    lock ? "已开启" : (isDo ? "已关闭（老人可以退出桌面）" : "未激活设备所有者"), missing);

            // 3. 无障碍服务（悬浮键、看门狗依赖它）
            boolean a11y = settingContains(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ctx.getPackageName());
            okCount += add(items, "无障碍服务", a11y,
                    a11y ? "已开启" : "未开启（悬浮键/看门狗不可用）", missing);

            // 4. 通知使用权（通知白名单依赖它）
            boolean notif = settingContains(ctx, "enabled_notification_listeners",
                    ctx.getPackageName());
            okCount += add(items, "通知使用权", notif,
                    notif ? "已开启" : "未开启（垃圾通知拦不住）", missing);

            // 5. 悬浮窗
            boolean overlay = canDrawOverlays(ctx);
            okCount += add(items, "悬浮窗权限", overlay,
                    overlay ? "已授权" : "未授权", missing);

            // 6. 使用情况访问（清理后台的参考）
            boolean usage = hasOp(ctx, AppOpsManager.OPSTR_GET_USAGE_STATS);
            okCount += add(items, "使用情况访问", usage,
                    usage ? "已授权" : "未授权（统计不准）", missing);

            // 7. 电池优化白名单（不加入的话锁屏久了定时任务会被掐）
            boolean battery = isIgnoringBattery(ctx);
            okCount += add(items, "电池优化白名单", battery,
                    battery ? "已加入" : "未加入（定时上报可能延迟）", missing);

            // 8. 定位权限 + 系统定位开关
            boolean locPerm = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean locOn = locationOn(ctx);
            boolean locOk = locPerm && locOn;
            okCount += add(items, "定位", locOk,
                    locOk ? "正常" : (!locPerm ? "缺权限" : "系统定位已关（搜不到 WIFI、定不了位）"), missing);

            // 9. 上报：有地址 + 最近一次成功过
            boolean reportOn = ReportUploader.enabled(ctx);
            long lastOk = Prefs.getLong(ctx, "report_last_at", 0);
            boolean reportOk = reportOn && (lastOk > 0);
            String reportHint;
            if (!reportOn) {
                reportHint = "没配上报地址（家人端看不到数据）";
            } else if (lastOk == 0) {
                reportHint = "配了地址但还没成功过";
            } else {
                reportHint = "上次成功：" + ago(lastOk);
            }
            okCount += add(items, "主动上报", reportOk, reportHint, missing);

            // 10. 网络
            boolean net = hasNetwork(ctx);
            okCount += add(items, "网络", net, net ? "已连接" : "没连 WIFI 也没有移动数据", missing);

            out.put("ok", true);
            out.put("score", okCount);
            out.put("total", items.length());
            out.put("items", items);
            out.put("version", DiagReport.version(ctx));
            // 家人要的不只是"缺什么"，也要知道"哪些是好的"——
            // 只报缺失时，看到 10/10 就完全不知道查了些什么。
            StringBuilder normals = new StringBuilder();
            StringBuilder bad = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                if (it.optBoolean("ok")) {
                    if (normals.length() > 0) normals.append("、");
                    normals.append(it.optString("name"));
                } else {
                    if (bad.length() > 0) bad.append("\n");
                    bad.append("· ").append(it.optString("name"))
                            .append("：").append(it.optString("hint"));
                }
            }
            String msg = okCount + "/" + items.length() + " 正常";
            if (normals.length() > 0) msg += "\n正常：" + normals;
            if (bad.length() > 0) msg += "\n缺少：\n" + bad;
            out.put("summary", msg);
        } catch (Exception e) {
            Log.w(TAG, "体检失败", e);
            try {
                out.put("ok", false);
                out.put("summary", "体检失败：" + e.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 一句话结果，直接给家人端看 */
    public static String summary(Context ctx) {
        JSONObject j = build(ctx);
        return j.optString("summary", "体检失败");
    }

    private static int add(JSONArray items, String name, boolean ok, String hint,
                           StringBuilder missing) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("ok", ok);
            o.put("hint", hint);
            items.put(o);
            if (!ok) {
                if (missing.length() > 0) missing.append("、");
                missing.append(name);
            }
        } catch (Exception ignored) {
        }
        return ok ? 1 : 0;
    }

    private static boolean settingContains(Context ctx, String key, String needle) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), key);
            return v != null && v.toLowerCase().contains(needle.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canDrawOverlays(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(ctx);
        } catch (Exception ignored) {
        }
        return hasOp(ctx, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW);
    }

    private static boolean hasOp(Context ctx, String op) {
        try {
            AppOpsManager am = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (am == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = am.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), ctx.getPackageName());
            } else {
                mode = am.checkOpNoThrow(op, android.os.Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isIgnoringBattery(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean locationOn(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            }
            return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 2) return "刚刚";
        if (m < 60) return m + " 分钟前";
        long h = m / 60;
        if (h < 24) return h + " 小时前";
        return (h / 24) + " 天前";
    }
}
