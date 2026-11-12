package com.elder.launcher;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * 网络状态读取 + 尽力而为的开关
 *
 * 这里必须说清一件事（也是这个类存在的理由）：
 *
 * 1. **移动数据**：普通应用没有任何公开 API 能开关它。
 *    `TelephonyManager.setDataEnabled()` 需要 MODIFY_PHONE_STATE（系统签名），
 *    Device Owner 也没有这个权限。实测在荣耀 10 上直接写
 *    `settings put global mobile_data 0`，值改了、网络照旧（ping 还能通、LTE 还连着），
 *    说明 EMUI 不认这个键。所以本类对移动数据只能"读"，不能"写"。
 *
 * 2. **WiFi**：`WifiManager.setWifiEnabled()` 从 Android 10（API 29）开始，
 *    对 targetSdk >= 29 的应用**永远返回 false**。所以 Android 9 及以下是真能关的，
 *    Android 10 及以上只能引导去系统设置页。
 *
 * 换句话说：状态一定读得到，开关要看系统脸色。界面按 canToggleXxx() 决定按钮语义，
 * 不做"点了没反应"的假开关。
 */
public final class NetState {

    private static final String TAG = "ElderNet";

    private NetState() {
    }

    // ==================== 读状态 ====================

    public static boolean isWifiOn(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前 WiFi 名称；未连接返回 null（系统会给 SSID 加引号，这里去掉） */
    public static String wifiSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) return null;
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isMobileDataOn(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return tm.isDataEnabled();
            }
            return Settings.Global.getInt(ctx.getContentResolver(),
                    "mobile_data", 1) == 1;
        } catch (Exception e) {
            // isDataEnabled 需要 READ_PHONE_STATE，读不到就退回设置项
            try {
                return Settings.Global.getInt(ctx.getContentResolver(), "mobile_data", 1) == 1;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    public static String carrier(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String name = tm.getNetworkOperatorName();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    /** 4G / 5G / 3G / 2G，读不到返回空串 */
    public static String networkType(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            int type = tm.getDataNetworkType();
            switch (type) {
                case TelephonyManager.NETWORK_TYPE_LTE:
                    return "4G";
                case TelephonyManager.NETWORK_TYPE_NR:
                    return "5G";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                    return "3G";
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                    return "2G";
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 能力判断 ====================

    /** Android 10 起，第三方应用调 setWifiEnabled 一律返回 false */
    public static boolean canToggleWifi() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    /** 普通应用无法开关移动数据（见类注释） */
    public static boolean canToggleMobileData() {
        return false;
    }

    /** @return 是否真的切换成功（以读回的状态为准） */
    public static boolean setWifi(Context ctx, boolean on) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            boolean ret = wm.setWifiEnabled(on);
            Log.i(TAG, "setWifiEnabled(" + on + ") 返回 " + ret);
            // 有些 ROM 返回值不可靠，隔一会儿读一次真实状态
            for (int i = 0; i < 8; i++) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
                if (wm.isWifiEnabled() == on) return true;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "切换 WiFi 失败", e);
            return false;
        }
    }

    public static boolean setMobileData(Context ctx, boolean on) {
        // 没有可用 API，直接如实返回失败，由界面引导去系统设置
        Log.i(TAG, "开关移动数据：普通应用无权限，需要系统设置");
        return false;
    }
}
