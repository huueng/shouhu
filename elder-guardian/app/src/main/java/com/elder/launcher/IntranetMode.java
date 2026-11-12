package com.elder.launcher;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.Enumeration;

/**
 * 内网 WIFI 模式（不插卡的「看视频机」）
 *
 * 场景：老人自己用老年机打电话，家里另外放一台智能机，不插卡、连家里 WIFI，
 * 用来刷视频打发时间。这台机器既没有 SIM 卡，也不该被当成"主手机"来管：
 *
 *  · 界面上不该出现「打电话 / 发短信 / 联系人」——点了只会报错，反而让老人以为坏了
 *  · 短信指令、响铃挂断定位、来电播报全部无意义（没有卡，收不到也打不通）
 *  · 只需要知道"现在连的是哪个 WIFI"，上报时把这个名字带回去
 *
 * 早期版本还做过"家里 WIFI 白名单"，要求家人先配好允许连哪几个网、连错就报警。
 * 实际用下来是多余的：这种机器基本不离开家，白名单反而带来一堆问题
 * （配了不显示、换个网就不刷新）。现在只读 WIFI 名字，不做任何限制。
 */
public final class IntranetMode {

    private static final String TAG = "ElderIntranet";

    private IntranetMode() {
    }

    // ==================== 开关 ====================

    public static boolean isOn(Context ctx) {
        return Prefs.getBool(ctx, Prefs.INTRANET_MODE, false);
    }

    public static void setOn(Context ctx, boolean on) {
        Prefs.putBool(ctx, Prefs.INTRANET_MODE, on);
    }

    // ==================== WIFI 名字 ====================

    /** 当前连着的 WIFI 名；没连返回 null */
    public static String currentSsid(Context ctx) {
        return NetState.wifiSsid(ctx);
    }

    public static boolean wifiOn(Context ctx) {
        return NetState.isWifiOn(ctx);
    }

    /**
     * 当前 WIFI 的信号格数 1–4；没连返回 -1。
     * 取不到 RSSI 时按"有连接就算 1 格"，避免显示成没信号。
     */
    public static int wifiLevel(Context ctx) {
        try {
            String ssid = currentSsid(ctx);
            if (ssid == null) return -1;
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            int rssi = info == null ? -127 : info.getRssi();
            return Math.max(1, WifiManager.calculateSignalLevel(rssi, 4) + 1);
        } catch (Exception e) {
            return 1;
        }
    }

    // ==================== IPv6 ====================

    /**
     * 本机当前可用的 IPv6 全局地址（没有返回空串）。
     *
     * 家里宽带只有 IPv6 时，中继靠它直连，所以这个地址比"WIFI 已连接"更能说明问题：
     * WIFI 连着但 IPv6 没下发（路由器关了 IPv6 / 运营商没给），上报一样发不出去。
     */
    public static String ipv6(Context ctx) {
        try {
            Enumeration<java.net.NetworkInterface> nifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                java.net.NetworkInterface nif = nifs.nextElement();
                try {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                } catch (Exception ignored) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!(a instanceof java.net.Inet6Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String h = a.getHostAddress();
                    int p = h.indexOf('%');
                    if (p > 0) h = h.substring(0, p);
                    if (!h.isEmpty()) return h;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读 IPv6 失败", e);
        }
        return "";
    }

    // ==================== 状态文案 ====================

    /** 一句话状态，设置页和主界面共用 */
    public static String stateText(Context ctx) {
        if (!wifiOn(ctx)) return "无线网络是关的";
        String cur = currentSsid(ctx);
        if (cur == null) return "没连上 WIFI";
        return "已连 " + cur;
    }

    /** 主界面顶部红条要显示的内容；正常时返回 null */
    public static String bannerText(Context ctx) {
        if (!isOn(ctx)) return null;
        if (!wifiOn(ctx)) return "无线网络被关掉了，家人看不到位置";
        if (currentSsid(ctx) == null) return "没连上 WIFI，家人看不到位置";
        return null;
    }

    /** 给家人端/中继用的标记：ok / no_wifi */
    public static String networkFlag(Context ctx) {
        if (!wifiOn(ctx) || currentSsid(ctx) == null) return "no_wifi";
        return "ok";
    }
}
