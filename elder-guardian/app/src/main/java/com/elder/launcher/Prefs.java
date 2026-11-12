package com.elder.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置中心 —— 所有 SharedPreferences 键集中在这里
 *
 * 老版本里键名散落在各个 Activity 中，出现过一次真正的错位：
 * AddAppActivity 写的是 tv_tiles，而手机端主界面读的是 tile_packages，
 * 结果「添加应用」在手机上完全无效，家人只能靠改默认值。
 * 现在统一由本类收口，并保留旧键的一次性迁移。
 */
public final class Prefs {

    public static final String FILE = "elder_config";

    /** 首次警告是否已确认 */
    public static final String WARNING_CONFIRMED = "warning_confirmed";
    /** 深色模式 */
    public static final String DARK_MODE = "dark_mode";

    /** 锁定任务模式（老人无法退出） */
    public static final String LOCK_TASK = "lock_task_enabled";
    /** 默认桌面（Device Owner 专用，EMUI 上无效，见文档） */
    public static final String PERSISTENT_HOME = "persistent_home";
    public static final String BLOCK_INSTALL = "block_install";
    public static final String BLOCK_UNINSTALL = "block_uninstall";

    /** 主界面卡片（逗号分隔包名） */
    public static final String TILES = "tile_packages";
    /** 老版本电视端卡片键，仅用于迁移 */
    public static final String LEGACY_TILES = "tv_tiles";

    public static final String CHIME_ENABLED = "hourly_chime_enabled";
    public static final String CHIME_HOURS = "chime_hours";
    public static final String WEATHER_ENABLED = "weather_enabled";
    public static final String WEATHER_HOURS = "weather_speak_hours";
    public static final String LAST_WEATHER_SHORT = "last_weather_short";
    /** 天气词（多云 / 有雨…），主界面第二行用 */
    public static final String LAST_WEATHER_DESC = "last_weather_desc";

    public static final String KILL_ENABLED = "auto_kill_enabled";
    public static final String KILL_INTERVAL = "kill_interval_minutes";
    public static final String KILL_WHITELIST = "kill_whitelist";
    public static final String NOTIF_WHITELIST = "notification_whitelist";
    /** 通知过滤总开关：关掉=所有通知都放行（以前只能换名单，没法整体关） */
    public static final String NOTIF_FILTER = "notif_filter_enabled";

    /** 来电播报：name 念备注名 / number 念号码 / off 不播报 */
    public static final String CALLER_ANNOUNCE = "caller_announce_mode";
    /** 通话结束后自动回桌面 */
    public static final String CALL_RETURN_HOME = "call_return_home";
    /** 看门狗：检测到切回系统桌面就自动跳回来 */
    public static final String WATCHDOG = "watchdog_enabled";
    /** 定位服务（老人机做服务器，家人端拉取） */
    public static final String LOCATE_SERVER = "locate_server_enabled";
    public static final String LOCATE_KEY = "locate_server_key";
    /** 悬浮导航键（返回/主页/多任务三个小圆点） */
    public static final String FLOATING_NAV = "floating_nav";

    /** 主界面是否显示「设置」卡片：新装默认显示，方便调试，设置里可以关掉 */
    public static final String HOME_SETTINGS_CARD = "home_settings_card";
    /** 轨迹采样（老人端本地记录，家人端来取） */
    public static final String TRACK_ENABLED = "track_enabled";
    /** 限制移动数据（仅 Android 11+ 且已激活 Device Owner） */
    public static final String DATA_LIMIT = "data_limit_enabled";

    /**
     * 内网 WIFI 模式：不插卡、只连家里一两个 WIFI 的「看视频机」。
     * 打开后电话/短信/联系人/来电播报全部从界面和逻辑里撤掉，
     * 定位、轨迹、主动上报照常走 WIFI（IPv6 直连中继）。
     */
    public static final String INTRANET_MODE = "intranet_mode";
    /** 首次启动是否已经选过「使用形态」（内网 WIFI 机 / 老人主手机） */
    public static final String MODE_CHOSEN = "mode_chosen";
    public static final String FAST_LINK = "fast_link";

    /**
     * 快通道（秒级响应）：老人端向中继挂长轮询，家人点「催一次定位」几秒内就能下发。
     * 代价是每 25 秒一次心跳（约 3–4 MB/天），所以做成可关。
     */
    /** 连到不认识的 WIFI 时语音提醒的冷却标记 */
    public static final String INTRANET_WARN = "intranet_warn_mark";

    /** 主动上报（webhook）：默认关闭，填了地址才发 */
    public static final String REPORT_ENABLED = "report_enabled";
    public static final String REPORT_URL = "report_url";
    public static final String REPORT_HEADER = "report_header";
    /** 上报间隔（分钟）。围栏告警靠它，默认 10 */
    public static final String REPORT_INTERVAL = "report_interval_min";

    /** 检查更新 */
    public static final String UPDATE_URL = "update_url";

    /** 最近一次崩溃（诊断里用） */
    public static final String LAST_CRASH = "last_crash";

    /** 局域网传包 */
    public static final String TRANSFER_ENABLED = "transfer_enabled";

    /** 默认卡片候选：按顺序取本机真实安装的（华为/原生包名都覆盖） */
    private static final String[] DEFAULT_TILE_CANDIDATES = {
            "com.tencent.mm",                        // 微信
            "com.android.mms",                       // 短信
            "com.google.android.apps.messaging",     // 短信（谷歌）
            "com.android.dialer",                    // 电话
            "com.google.android.dialer",             // 电话（谷歌）
            "com.huawei.contacts"                    // 华为联系人
    };

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return get(ctx).getBoolean(key, def);
    }

    public static void putBool(Context ctx, String key, boolean value) {
        get(ctx).edit().putBoolean(key, value).apply();
    }

    public static String getString(Context ctx, String key, String def) {
        return get(ctx).getString(key, def);
    }

    public static void putString(Context ctx, String key, String value) {
        get(ctx).edit().putString(key, value).apply();
    }

    public static int getInt(Context ctx, String key, int def) {
        return get(ctx).getInt(key, def);
    }

    public static void putInt(Context ctx, String key, int value) {
        get(ctx).edit().putInt(key, value).apply();
    }

    public static long getLong(Context ctx, String key, long def) {
        return get(ctx).getLong(key, def);
    }

    public static void putLong(Context ctx, String key, long value) {
        get(ctx).edit().putLong(key, value).apply();
    }

    /**
     * 读取桌面卡片配置；没有配置过就用「按本机实际情况生成」的默认值。
     *
     * 不做 tv_tiles 迁移：那是电视端留下的列表（通常只有 1-2 个应用），
     * 直接搬到手机上反而会让老人桌面只剩一个陌生图标，统一以默认值为准。
     */
    public static String tilesCsv(Context ctx) {
        SharedPreferences p = get(ctx);
        String current = p.getString(TILES, null);
        if (current != null) return current;

        String value = defaultTilesCsv(ctx);
        p.edit().putString(TILES, value).remove(LEGACY_TILES).apply();
        return value;
    }

    public static void putTilesCsv(Context ctx, String csv) {
        get(ctx).edit().putString(TILES, csv).apply();
    }

    /**
     * 首次使用时的默认卡片：微信 + 短信。
     * 「打电话」是主界面固定的第一张卡片（电话墙），不再写进这里，避免出现两张拨号卡。
     * 不写死短信包名 —— 各家 ROM 的短信应用包名不一样，按 Intent 实际能解析到的来。
     */
    public static String defaultTilesCsv(Context ctx) {
        java.util.LinkedHashSet<String> pkgs = new java.util.LinkedHashSet<>();

        if (ctx.getPackageManager().getLaunchIntentForPackage("com.tencent.mm") != null) {
            pkgs.add("com.tencent.mm");
        }

        String sms = firstResolvedPackage(ctx, new android.content.Intent(
                android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")));
        if (sms == null) {
            sms = firstResolvedPackage(ctx, new android.content.Intent(
                    android.content.Intent.ACTION_VIEW).setType("vnd.android-dir/mms-sms"));
        }
        if (sms != null) pkgs.add(sms);

        // 一个都解析不到时，退回老的四件套，至少让家人有东西可点
        if (pkgs.isEmpty()) {
            for (String pkg : DEFAULT_TILE_CANDIDATES) {
                if (ctx.getPackageManager().getLaunchIntentForPackage(pkg) != null) pkgs.add(pkg);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String pkg : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
        }
        return sb.toString();
    }

    /** 某个 Intent 实际会被哪个应用处理（跳过系统 Resolver 自己） */
    public static String firstResolvedPackage(Context ctx, android.content.Intent intent) {
        try {
            java.util.List<android.content.pm.ResolveInfo> list =
                    ctx.getPackageManager().queryIntentActivities(intent, 0);
            for (android.content.pm.ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if ("android".equals(pkg) || ctx.getPackageName().equals(pkg)) continue;
                return pkg;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
