package com.elder.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮虚拟按键（返回 / 主页 / 多任务）
 * 用于手机没有实体按键、且无法改成三键导航的情况
 */
public class FloatingNavService extends Service {

    private static final String TAG = "ElderNav";
    private static final String CHANNEL_ID = "elder_nav";
    private static final int NOTIFY_ID = 1001;

    private WindowManager wm;
    private LinearLayout bar;
    private boolean added = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "缺少悬浮窗权限，悬浮按键无法显示", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!added) {
            showBar();
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "悬浮按键", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("老人守护")
                .setContentText("悬浮按键运行中")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setOngoing(true)
                .build();
        startForeground(NOTIFY_ID, n);
    }

    private void showBar() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        bar.addView(makeKey("◀", () -> {
            ElderAccessibilityService svc = ElderAccessibilityService.get();
            if (svc != null) {
                svc.back();
            } else {
                Toast.makeText(this, "需要开启无障碍服务才能返回", Toast.LENGTH_SHORT).show();
            }
        }));
        bar.addView(makeKey("●", () -> {
            ElderAccessibilityService svc = ElderAccessibilityService.get();
            if (svc != null) {
                svc.home();
            } else {
                Intent i = new Intent(this, MainLauncherActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }));
        bar.addView(makeKey("■", () -> {
            ElderAccessibilityService svc = ElderAccessibilityService.get();
            if (svc != null) {
                svc.recents();
            } else {
                Toast.makeText(this, "需要开启无障碍服务才能打开多任务", Toast.LENGTH_SHORT).show();
            }
        }));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(40);

        try {
            wm.addView(bar, lp);
            added = true;
            Log.i(TAG, "悬浮按键已显示");
        } catch (Exception e) {
            Log.e(TAG, "悬浮按键添加失败", e);
        }
    }

    private TextView makeKey(String text, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(22);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC37474F"));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.setMargins(dp(8), 0, dp(8), 0);
        tv.setLayoutParams(lp);

        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bar != null && wm != null) {
            try {
                wm.removeView(bar);
            } catch (Exception ignored) {
            }
            bar = null;
            added = false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
