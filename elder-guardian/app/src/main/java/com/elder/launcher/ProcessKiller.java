package com.elder.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 周期清理后台
 * 只清理「用户安装的第三方应用」，并且严格跳过白名单，杜绝误杀正在用的软件
 */
public final class ProcessKiller {

    /** 默认白名单：常用社交/支付/短信/电话/桌面 */
    public static final String DEFAULT_KILL_WHITELIST =
            "com.tencent.mm,com.tencent.mobileqq,com.eg.android.AlipayGphone,"
                    + "com.ss.android.ugc.aweme,com.android.mms,com.android.dialer,"
                    + "com.google.android.apps.messaging,com.android.phone,"
                    + "com.huawei.contacts,com.hihonor.contacts";

    private ProcessKiller() {
    }

    /** 返回本次清理的应用数量 */
    public static int killBackground(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE);
        String saved = prefs.getString("kill_whitelist", DEFAULT_KILL_WHITELIST);
        Set<String> white = new HashSet<>(Arrays.asList(saved.split(",")));

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = ctx.getPackageManager();
        if (am == null || pm == null) return 0;

        String self = ctx.getPackageName();
        int count = 0;
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (pkg.equals(self)) continue;
            if (white.contains(pkg)) continue;
            // 只清理用户自行安装的应用，系统应用一律不动
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            try {
                am.killBackgroundProcesses(pkg);
                count++;
            } catch (Exception ignored) {
            }
        }
        return count;
    }
}
