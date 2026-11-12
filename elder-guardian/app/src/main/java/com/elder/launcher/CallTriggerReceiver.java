package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.CallLog;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * 「响铃挂断」触发定位
 *
 * 家人用白名单号码拨打老人手机，响 1-2 声就挂断（不接听），
 * 老人机检测到「响铃 → 未接听 → 挂断」后自动回一条定位短信。
 * 老人全程不用做任何操作，也不会响铃打扰太久。
 *
 * 号码来源：优先用广播里的 incoming_number；部分 ROM 不给号码时，
 * 回查最近 30 秒的通话记录（需要 READ_CALL_LOG，Device Owner 可静默授予）。
 */
public class CallTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "ElderCallTrigger";

    private static final String PREF_RING_START = "loc_call_ring_start";
    private static final String PREF_OFFHOOK = "loc_call_offhook";
    private static final String PREF_NUMBER = "loc_call_number";

    /** 响铃超过这个时长视为正常通话/长时间未接，不触发 */
    private static final long MAX_RING_MS = 8000L;
    private static final long DEDUP_MS = 15000L;

    private static long lastTriggerAt = 0L;

    /**
     * 来电播报延后 1.6 秒。
     * 原来一响就念，结果"人名"和"铃声"叠在一起，两个声音互相盖，老人根本听不清；
     * 晚一点念，铃声起头的那一下不打架，名字也更清楚。
     */
    private static final long ANNOUNCE_DELAY_MS = 1600L;
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable sAnnounceTask;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        // 内网 WIFI 模式：没有 SIM 卡，响铃挂断和来电播报都不存在，直接跳过省电
        if (IntranetMode.isOn(ctx)) return;

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        SharedPreferences prefs = ctx.getSharedPreferences(LocConfig.PREFS, Context.MODE_PRIVATE);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            prefs.edit()
                    .putLong(PREF_RING_START, System.currentTimeMillis())
                    .putBoolean(PREF_OFFHOOK, false)
                    .putString(PREF_NUMBER, incoming == null ? "" : incoming)
                    .apply();
            scheduleAnnounce(ctx, incoming);
            return;
        }

        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            // 接听了，不算「响铃挂断」；已经念出来也无所谓，还没念的就别念了
            cancelAnnounce();
            prefs.edit().putBoolean(PREF_OFFHOOK, true).apply();
            return;
        }

        if (!TelephonyManager.EXTRA_STATE_IDLE.equals(state)) return;

        cancelAnnounce();

        // 通话结束：如果这次是我们发起的通话，把老人送回主界面
        // （必须放在各个 return 之前，否则响铃挂断分支会把它跳过）
        CallReturn.maybeReturnHome(ctx);

        long ringStart = prefs.getLong(PREF_RING_START, 0L);
        boolean offhook = prefs.getBoolean(PREF_OFFHOOK, false);
        String stored = prefs.getString(PREF_NUMBER, "");
        prefs.edit()
                .putLong(PREF_RING_START, 0L)
                .putBoolean(PREF_OFFHOOK, false)
                .putString(PREF_NUMBER, "")
                .apply();

        if (ringStart <= 0L || offhook) return;
        long ringMs = System.currentTimeMillis() - ringStart;
        if (ringMs > MAX_RING_MS) return;

        if (!LocConfig.enabled(ctx) || !LocConfig.callTriggerEnabled(ctx)) return;
        if (LocConfig.familyNumbers(ctx).isEmpty()) return;

        String caller = stored;
        if (caller == null || caller.isEmpty() || "null".equalsIgnoreCase(caller)) {
            caller = recentIncomingNumber(ctx);
        }
        if (caller == null || caller.isEmpty()) {
            Log.i(TAG, "响铃挂断，但拿不到来电号码，跳过");
            return;
        }
        if (!LocConfig.isFamily(ctx, caller)) {
            Log.i(TAG, "非家人号码 " + LocConfig.mask(caller) + " 响铃挂断，跳过");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < DEDUP_MS) return;
        lastTriggerAt = now;

        Log.i(TAG, "家人 " + LocConfig.mask(caller) + " 响铃挂断，开始定位（响铃 " + ringMs + "ms）");
        LocationFetchService.start(ctx, caller, 1, 60, "call");
    }

    /** 延迟播报来电；接听/挂断时会取消 */
    private void scheduleAnnounce(final Context ctx, final String number) {
        cancelAnnounce();
        final Context app = ctx.getApplicationContext();
        sAnnounceTask = () -> {
            sAnnounceTask = null;
            announceCaller(app, number);
        };
        MAIN.postDelayed(sAnnounceTask, ANNOUNCE_DELAY_MS);
    }

    private static void cancelAnnounce() {
        if (sAnnounceTask != null) {
            MAIN.removeCallbacks(sAnnounceTask);
            sAnnounceTask = null;
        }
    }

    /**
     * 来电语音播报 —— 老人看不清屏幕上的小字，直接念出来更实用
     *
     * 模式：name 念联系人备注名（查不到就用号码）／number 念号码／off 不播报
     */
    private void announceCaller(Context ctx, String number) {
        String mode = ctx.getSharedPreferences(LocConfig.PREFS, Context.MODE_PRIVATE)
                .getString(Prefs.CALLER_ANNOUNCE, "name");
        if (mode == null || "off".equals(mode)) return;
        if (number == null || number.isEmpty() || "null".equalsIgnoreCase(number)) return;

        String digits = readableNumber(number);
        if ("number".equals(mode)) {
            TtsHelper.speak(ctx, "来电，" + digits, TtsHelper.RATE_CALLER);
            return;
        }
        String name = lookupContactName(ctx, number);
        // 用更慢的语速念名字，并在"来电"后加逗号停顿一下
        TtsHelper.speak(ctx, "来电，" + (name == null || name.isEmpty() ? digits : name),
                TtsHelper.RATE_CALLER);
    }

    /** 13800138000 → 138 0013 8000，让 TTS 按组念而不是当成一个巨大的数字 */
    private String readableNumber(String number) {
        String raw = number.replaceAll("[^0-9+]", "");
        if (raw.length() < 8) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            if (i == 3 || i == 7) sb.append(' ');
            sb.append(raw.charAt(i));
        }
        return sb.toString();
    }

    /** 用号码反查通讯录姓名（需要 READ_CONTACTS） */
    private String lookupContactName(Context ctx, String number) {
        android.database.Cursor c = null;
        try {
            android.net.Uri uri = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number));
            c = ctx.getContentResolver().query(uri,
                    new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "查通讯录失败（可能缺少 READ_CONTACTS）", e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** 回查最近 30 秒的来电号码（广播不给号码时兜底） */
    private String recentIncomingNumber(Context ctx) {
        Cursor c = null;
        try {
            long since = System.currentTimeMillis() - 30000L;
            c = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE},
                    CallLog.Calls.DATE + ">?",
                    new String[]{String.valueOf(since)},
                    CallLog.Calls.DATE + " DESC");
            if (c == null) return null;
            while (c.moveToNext()) {
                int type = c.getInt(1);
                if (type == CallLog.Calls.INCOMING_TYPE || type == CallLog.Calls.MISSED_TYPE) {
                    return c.getString(0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "查询通话记录失败（可能缺少 READ_CALL_LOG 权限）", e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
