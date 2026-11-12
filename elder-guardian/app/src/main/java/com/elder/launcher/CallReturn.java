package com.elder.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 「通话结束自动回桌面」
 *
 * 老人拨完电话经常停在通话记录/联系人页面，不知道按哪儿回主界面。
 * 这里在拨号前打一个时间戳，通话状态回到 IDLE 时如果时间戳还很新，就把主界面拉到前台。
 *
 * 只对「我们主动发起的通话」生效：家人打进来、响铃挂断查位置这些都不受影响。
 */
public final class CallReturn {

    private static final String TAG = "ElderCallReturn";
    /** 拨号后多久内挂断还算"本次通话"（10 分钟，足够一般通话） */
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;

    private CallReturn() {
    }

    public static void markPending(Context ctx) {
        Prefs.get(ctx).edit()
                .putLong("call_pending_at", System.currentTimeMillis())
                .apply();
    }

    public static void clear(Context ctx) {
        Prefs.get(ctx).edit().remove("call_pending_at").apply();
    }

    /** 通话结束后调用：满足条件就把主界面拉回来 */
    public static void maybeReturnHome(Context ctx) {
        SharedPreferences p = Prefs.get(ctx);
        long pendingAt = p.getLong("call_pending_at", 0L);
        if (pendingAt <= 0L) return;
        // 无论是否跳转，这次通话都算处理过了，避免下一次误判
        p.edit().remove("call_pending_at").apply();

        if (!p.getBoolean(Prefs.CALL_RETURN_HOME, true)) return;
        if (System.currentTimeMillis() - pendingAt > PENDING_TTL_MS) return;

        try {
            Intent i = new Intent(ctx, MainLauncherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            Log.i(TAG, "通话结束，已拉回主界面");
        } catch (Exception e) {
            Log.w(TAG, "拉回主界面失败（可能被后台启动限制拦截）", e);
        }
    }
}
