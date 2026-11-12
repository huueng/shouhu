package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

/**
 * 开机广播
 * 任务：
 * 1. 拉起 MainLauncherActivity（进入锁定任务模式）
 * 2. 启动前台服务（悬浮导航、周期任务）
 * 3. 恢复所有 AlarmManager 定时器（整点报时、杀后台、天气播报）
 *
 * 注意：锁定任务模式（LockTask）不会开机自动恢复，必须在这里
 * 显式 start LockTask。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ElderBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "收到广播: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // 1. 拉起主界面（进入锁定任务模式）
            Intent mainIntent = new Intent(context, MainLauncherActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(mainIntent);

            // 2. 确保 Device Owner 限制在开机后依然生效
            // Device Owner 的 addUserRestriction 是持久化的，
            // 开机后系统自动读入，不需要重新设置

            // 3. 悬浮导航键：默认关，只有家人明确开过才启动
            //    （之前是无条件启动，导致每次开机桌面右下角多三个圆点挡住卡片）
            if (Prefs.getBool(context, Prefs.FLOATING_NAV, false)) {
                Intent svc = new Intent(context, FloatingNavService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }

            // 4. 重新注册所有周期定时器
            ElderApp.scheduleAllTimers(context);

            // 5. 远程能力开机即恢复（不依赖主界面是否被成功拉起）
            try {
                if (Prefs.getBool(context, Prefs.REPORT_ENABLED, false)) ReportUploader.schedule(context);
                if (Prefs.getBool(context, "ddns_enabled", false)) DdnsService.start(context);
                if (Prefs.getBool(context, Prefs.LOCATE_SERVER, false)) LocateServer.start(context);
            } catch (Exception e) {
                Log.w(TAG, "远程服务启动失败", e);
            }

            Log.i(TAG, "开机接管完成");
        }
    }
}
