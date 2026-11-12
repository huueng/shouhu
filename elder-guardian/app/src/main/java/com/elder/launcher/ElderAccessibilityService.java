package com.elder.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍服务：提供「返回 / 主页 / 多任务」全局操作（悬浮虚拟按键用），
 * 以及「看门狗」——检测到切回系统桌面就把老人守护拉回前台。
 *
 * 注意：本服务不读取任何屏幕内容，只取事件里的包名做判断。
 */
public class ElderAccessibilityService extends AccessibilityService {

    private static final String TAG = "ElderA11y";
    /** 两次纠正的最小间隔，避免和系统抢焦点 */
    private static final long MIN_JUMP_GAP_MS = 2500L;
    /** 连续纠正这么多次还失败就放弃（防死循环） */
    private static final int MAX_CONSECUTIVE_JUMPS = 5;

    private static ElderAccessibilityService instance;

    private Set<String> launcherPackages;
    private long lastJumpAt = 0L;
    private int consecutiveJumps = 0;

    public static ElderAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkgName = event.getPackageName();
        if (pkgName == null) return;
        String pkg = pkgName.toString();

        // 已经回到自己界面，说明上一次纠正成功，计数清零
        if (getPackageName().equals(pkg)) {
            consecutiveJumps = 0;
            return;
        }

        if (!Prefs.getBool(this, Prefs.WATCHDOG, false)) return;
        if (!isSystemLauncher(pkg)) return;

        long now = System.currentTimeMillis();
        if (now - lastJumpAt < MIN_JUMP_GAP_MS) return;
        if (consecutiveJumps >= MAX_CONSECUTIVE_JUMPS) return;

        lastJumpAt = now;
        consecutiveJumps++;
        try {
            Intent i = new Intent(this, MainLauncherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            Log.i(TAG, "检测到切回系统桌面（" + pkg + "），已拉回老人守护");
        } catch (Exception e) {
            Log.w(TAG, "拉回失败", e);
        }
    }

    /** 系统桌面（HOME）应用的包名集合，不含自己 */
    private boolean isSystemLauncher(String pkg) {
        if (launcherPackages == null) {
            launcherPackages = new HashSet<>();
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                    if (ri.activityInfo == null) continue;
                    String p = ri.activityInfo.packageName;
                    if (!getPackageName().equals(p)) launcherPackages.add(p);
                }
            } catch (Exception e) {
                Log.w(TAG, "查询桌面应用失败", e);
            }
        }
        return launcherPackages.contains(pkg);
    }

    @Override
    public void onInterrupt() {
    }

    public void back() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void home() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void recents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }
}
