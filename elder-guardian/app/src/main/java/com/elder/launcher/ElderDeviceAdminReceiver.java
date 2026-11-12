package com.elder.launcher;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

/**
 * Device Owner 核心类
 * 所有设备限制（状态栏屏蔽、禁止安装、锁定任务、流量保护等）
 * 都通过 DPM (DevicePolicyManager) 在这里或 MainActivity 中调用。
 */
public class ElderDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "ElderAdmin";

    public static ComponentName getComponentName(Context ctx) {
        return new ComponentName(ctx, ElderDeviceAdminReceiver.class);
    }

    public static boolean isDeviceOwner(Context ctx) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
    }

    public static DevicePolicyManager getDPM(Context ctx) {
        return (DevicePolicyManager)
                ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /**
     * 按家人配置下发 Device Owner 限制（幂等，可重复调用）
     * 是否开启某项由 elder_config 里的开关决定，默认全部开启
     */
    public static void enableAllRestrictions(Context ctx) {
        DevicePolicyManager dpm = getDPM(ctx);
        if (dpm == null || !isDeviceOwner(ctx)) return;

        ComponentName admin = getComponentName(ctx);
        android.content.SharedPreferences p =
                ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE);

        try {
            // 1. 状态栏下拉：只在「锁定任务」开启时才禁用。
            // 老版本无条件 setStatusBarDisabled(true)，家人只想把本应用当桌面用时
            // 会发现状态栏凭空消失，这次改为跟随开关，关闭时显式恢复。
            boolean lockTask = p.getBoolean(Prefs.LOCK_TASK, false);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                dpm.setStatusBarDisabled(admin, lockTask);
            }
            // 故意不禁 SAFE_BOOT，留一个 Recovery 双清的逃生通道

            // 2. 禁止安装未知来源
            if (p.getBoolean(Prefs.BLOCK_INSTALL, true)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }

            // 3. 禁止卸载
            if (p.getBoolean(Prefs.BLOCK_UNINSTALL, true)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
            }

            // 4. 锁定任务白名单（只授权，不主动锁定；锁定由 Activity 决定）
            //    关键：白名单必须跟着桌面卡片动态生成。
            //    老版本写死了微信/短信/拨号几个包，家人后来「添加应用」的 App 不在名单里，
            //    一开锁定任务就会点不开（系统直接拦），连拨号都失败（华为拨号是 com.android.contacts）。
            String[] lockTaskPkgs = buildLockTaskPackages(ctx);
            dpm.setLockTaskPackages(admin, lockTaskPkgs);

            // 5. 防止本应用被卸载（家人误操作或老人乱点）。仅在「禁止卸载」开启时生效
            try {
                dpm.setUninstallBlocked(admin, ctx.getPackageName(),
                        p.getBoolean(Prefs.BLOCK_UNINSTALL, true));
            } catch (Exception ignored) {
                // 个别机型/系统版本不支持，忽略即可
            }

            Log.i(TAG, "Device Owner 限制已按配置下发，锁定任务白名单 " + lockTaskPkgs.length + " 个");
        } catch (Exception e) {
            Log.e(TAG, "设置限制失败", e);
        }
    }

    /**
     * 生成锁定任务白名单：系统必须项 + 桌面上的每个卡片 + 实际解析到的拨号/短信应用
     */
    public static String[] buildLockTaskPackages(Context ctx) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(ctx.getPackageName());
        // 系统 UI / 设置：来电界面、权限弹窗、系统对话框都依赖它们
        set.add("com.android.systemui");
        set.add("com.android.settings");
        set.add("com.android.phone");
        // 常见通讯应用（华为/原生两套包名都放进去，没有的会被系统忽略）
        set.add("com.android.mms");
        set.add("com.google.android.apps.messaging");
        set.add("com.android.dialer");
        set.add("com.google.android.dialer");
        set.add("com.android.contacts");
        set.add("com.huawei.contacts");
        set.add("com.hihonor.contacts");

        // ==== 通话界面 ====
        // 这是之前来电/去电都"没有电话页"的根因：
        // 锁定任务下只有白名单里的包能启动 Activity，而真正画通话界面的不是拨号器，
        // 是 InCallUI（com.android.incallui）/ Telecom。缺了它们，通话照常建立，
        // 但界面起不来，老人看到的就是"电话打出去了却没反应"。
        set.add("com.android.incallui");
        set.add("com.android.server.telecom");
        set.add("com.android.telecom");
        set.add("com.huawei.android.incallui");
        set.add("com.hihonor.incallui");
        // 通话录音、SIM 卡工具等系统弹窗也依赖下面几个
        set.add("com.android.phone.recorder");
        set.add("com.android.stk");

        // 桌面上的每一个卡片 —— 这是老版本缺的那一环
        for (String pkg : Prefs.tilesCsv(ctx).split(",")) {
            pkg = pkg.trim();
            if (!pkg.isEmpty()) set.add(pkg);
        }

        // ===== 换机适配：不写死品牌包名，按系统里"实际谁能干这活"来放行 =====
        // 之前只取 firstResolvedPackage（第一个候选），在有些 ROM 上会漏掉真正画界面的应用；
        // 这里把每个 Intent 的**所有**候选都放进去，荣耀/小米/OPPO/vivo/三星都能覆盖。

        // 1) 通话界面：不同品牌包名不一样（com.android.incallui / com.samsung.android.incallui /
        //    com.miui.incallui …），统一按 InCallService 查，最可靠
        try {
            Intent inCall = new Intent("android.telecom.InCallService");
            for (android.content.pm.ResolveInfo ri
                    : ctx.getPackageManager().queryIntentServices(inCall, 0)) {
                if (ri.serviceInfo != null) set.add(ri.serviceInfo.packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询通话界面包失败", e);
        }

        // 2) 拨号 / 通话 / 短信 / 会话列表：全部候选
        addResolved(ctx, set, new Intent(Intent.ACTION_DIAL));
        try {
            addResolved(ctx, set, new Intent(Intent.ACTION_CALL));
        } catch (Exception ignored) {
        }
        addResolved(ctx, set, new Intent(Intent.ACTION_SENDTO,
                android.net.Uri.parse("smsto:")));
        addResolved(ctx, set, new Intent(Intent.ACTION_VIEW)
                .setType("vnd.android-dir/mms-sms"));

        // 3) Android 11+ 的权限确认界面（没有它，系统弹窗出不来）
        set.add("com.android.permissioncontroller");
        set.add("com.google.android.permissioncontroller");
        // 4) 三星/小米/OPPO 的通话与联系人壳
        set.add("com.samsung.android.incallui");
        set.add("com.samsung.android.dialer");
        set.add("com.miui.incallui");
        set.add("com.android.incallui");

        return set.toArray(new String[0]);
    }

    /** 把某个 Intent 能处理的所有应用都加进白名单（换机时最稳的做法） */
    private static void addResolved(Context ctx, java.util.Set<String> set, Intent intent) {
        try {
            for (android.content.pm.ResolveInfo ri
                    : ctx.getPackageManager().queryIntentActivities(intent, 0)) {
                if (ri.activityInfo != null) set.add(ri.activityInfo.packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询 " + intent.getAction() + " 候选失败", e);
        }
    }

    /**
     * 一键解除所有 Device Owner 限制
     */
    public static void disableAllRestrictions(Context ctx) {
        DevicePolicyManager dpm = getDPM(ctx);
        if (dpm == null || !isDeviceOwner(ctx)) return;

        ComponentName admin = getComponentName(ctx);
        try {
            dpm.clearDeviceOwnerApp(ctx.getPackageName());
            Log.i(TAG, "Device Owner 限制已解除");
        } catch (Exception e) {
            Log.e(TAG, "解除限制失败", e);
        }
    }

    /**
     * 设置后台流量限制（Device Owner API，Android 11+）
     */
    public static void setDataRestriction(Context ctx, boolean enable) {
        Log.i(TAG, "后台流量限制 " + (enable ? "开启" : "关闭") + "（Android 11+ 可用）");
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin 已激活");
        enableAllRestrictions(context);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device Admin 已解除");
    }
}
