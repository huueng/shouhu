package com.elder.family;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机后把常驻服务拉起来，否则重启一次就静默了 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                AlertService.ensureRunning(context);
                Log.i("ElderBoot", "开机拉起常驻告警");
            } catch (Exception e) {
                Log.w("ElderBoot", "拉起失败", e);
            }
        }
    }
}
