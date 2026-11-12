package com.elder.launcher;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 通知白名单 —— NotificationListenerService
 *
 * 核心逻辑：
 *   onNotificationPosted → 收到任何应用通知
 *   检查包名是否在白名单
 *   不在 → cancelNotification 直接屏蔽
 *
 * 默认白名单（可在设置页增减）：
 *   微信 com.tencent.mm
 *   QQ   com.tencent.mobileqq
 *   短信 com.android.mms / com.google.android.apps.messaging
 *   电话 com.android.dialer / com.google.android.dialer / com.android.phone
 *   支付宝 com.eg.android.AlipayGphone
 *   壳 App 自己 com.elder.launcher
 */
public class ElderNotificationListener extends NotificationListenerService {

    private static final String TAG = "ElderNotif";

    // 默认白名单
    private static final String[] DEFAULT_ALLOWED = {
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.eg.android.AlipayGphone",
            "com.elder.launcher"
    };

    private SharedPreferences prefs;
    private Set<String> allowedPackages;
    /** 上次读取到的白名单原文，用于感知设置页的修改 */
    private String lastCsv;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();

        // 总开关关闭时全部放行。以前只有"换名单"，没有"整体关掉"，
        // 家人想临时不拦通知只能把应用一个个加进白名单，很别扭。
        boolean filterOn = getSharedPreferences("elder_config", MODE_PRIVATE)
                .getBoolean("notif_filter_enabled", true);
        if (!filterOn) return;

        // 延迟加载；设置页改过白名单后自动重新读取
        if (allowedPackages == null) {
            loadWhitelist();
        } else {
            String now = getSharedPreferences("elder_config", MODE_PRIVATE)
                    .getString("notification_whitelist", null);
            if (!android.text.TextUtils.equals(now, lastCsv)) loadWhitelist();
        }

        if (allowedPackages != null && !allowedPackages.contains(pkg)) {
            // 不在白名单 → 取消通知
            Log.i(TAG, "屏蔽通知: " + pkg + " key=" + sbn.getKey());
            try {
                cancelNotification(sbn.getKey());
            } catch (Exception e) {
                Log.w(TAG, "取消通知失败", e);
            }
        } else {
            Log.d(TAG, "放行通知: " + pkg);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 白名单通知移除处理（可选：通知壳 App）
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知监听器已连接");
        loadWhitelist();
    }

    private void loadWhitelist() {
        prefs = getSharedPreferences("elder_config", MODE_PRIVATE);
        String custom = prefs.getString("notification_whitelist", null);
        lastCsv = custom;
        allowedPackages = new HashSet<>();

        if (custom != null && !custom.isEmpty()) {
            // 使用自定义白名单
            for (String s : custom.split(",")) {
                allowedPackages.add(s.trim());
            }
        } else {
            // 使用默认白名单
            allowedPackages.addAll(Arrays.asList(DEFAULT_ALLOWED));
        }
    }

    /**
     * 刷新白名单（设置页修改后调用）
     */
    public static void refresh(Context ctx) {
        // 下次收到通知时重新 load
        // 或者可以杀死 service 让它重启
    }
}
