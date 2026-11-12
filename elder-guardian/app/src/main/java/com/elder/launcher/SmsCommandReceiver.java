package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短信指令接收器（零服务器的定位查询通道）
 *
 * 家人用白名单号码给老人机发短信：
 *   位置 / 定位 / 在哪 / loc        → 回一条当前位置
 *   跟踪 / 跟踪 10 / 跟踪 10 30     → 每 30 秒回一条，共 10 条（默认 5 条 / 60 秒）
 *
 * 运维指令（同样只认家人号码，不依赖「短信定位」总开关）：
 *   诊断 / diag    → 设备所有者、锁定、无障碍、通知、白名单、定位、域名、电量信号、崩溃
 *   修复 / fix     → 重下发设备限制、拉起服务、把主界面拉回前台
 *   使用 / usage   → 近 7 天使用统计
 *   重启 / restart → 重启守护界面（不改动系统）
 *
 * 安全：只有 loc_family_numbers 里的号码能触发；白名单为空时一律忽略。
 */
public class SmsCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "ElderSmsCmd";

    private static final Pattern EN_WORD = Pattern.compile("\\b(loc|location|where)\\b");
    private static final Pattern TRACK_WORD = Pattern.compile("\\btrack\\b");

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(action)) return;

        // 内网 WIFI 模式：这台机器没插卡，短信指令这条通道本来就不存在
        if (IntranetMode.isOn(ctx)) {
            Log.i(TAG, "内网 WIFI 模式，忽略短信指令");
            return;
        }

        String from;
        String body;
        try {
            SmsMessage[] msgs = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (msgs == null || msgs.length == 0) return;
            from = msgs[0].getOriginatingAddress();
            StringBuilder sb = new StringBuilder();
            for (SmsMessage m : msgs) {
                if (m != null && m.getMessageBody() != null) sb.append(m.getMessageBody());
            }
            body = sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "解析短信失败", e);
            return;
        }

        if (from == null || body == null) return;
        List<String> family = LocConfig.familyNumbers(ctx);
        if (family.isEmpty()) {
            Log.i(TAG, "未配置家人号码，忽略定位短信");
            return;
        }
        if (!LocConfig.isFamily(ctx, from)) {
            Log.i(TAG, "非家人号码 " + LocConfig.mask(from) + " 发来定位请求，已忽略");
            return;
        }

        String cmd = body.trim().toLowerCase(Locale.ROOT);

        // ---- 运维指令：先处理，不受「短信定位」开关影响 ----
        if (matches(cmd, "诊断", "自检", "diag")) {
            Log.i(TAG, "诊断请求 from=" + LocConfig.mask(from));
            SmsResponder.send(ctx, from, DiagReport.buildText(ctx));
            return;
        }
        if (matches(cmd, "修复", "fix")) {
            Log.i(TAG, "修复请求 from=" + LocConfig.mask(from));
            SmsResponder.send(ctx, from, DiagReport.repair(ctx));
            return;
        }
        if (matches(cmd, "使用", "用量", "usage")) {
            UsageStore.refresh(ctx);
            SmsResponder.send(ctx, from, UsageStore.summaryText(ctx));
            return;
        }
        if (matches(cmd, "重启", "restart")) {
            SmsResponder.send(ctx, from, "守护界面正在重启…");
            try {
                android.content.Intent i = new android.content.Intent(ctx, MainLauncherActivity.class);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "重启界面失败", e);
            }
            return;
        }

        // ---- 定位指令 ----
        if (!LocConfig.enabled(ctx)) return;

        boolean isTrack = cmd.contains("跟踪") || TRACK_WORD.matcher(cmd).find();
        boolean isLoc = isTrack
                || cmd.contains("位置") || cmd.contains("定位") || cmd.contains("在哪")
                || EN_WORD.matcher(cmd).find();
        if (!isLoc) return;

        int count = 1;
        int intervalSec = 60;
        if (isTrack) {
            count = 5;
            List<Integer> nums = numbersIn(cmd);
            if (!nums.isEmpty()) count = nums.get(0);
            if (nums.size() >= 2) intervalSec = nums.get(1);
            count = Math.max(1, Math.min(20, count));
            intervalSec = Math.max(20, Math.min(600, intervalSec));
        }

        Log.i(TAG, "收到定位指令 from=" + LocConfig.mask(from)
                + " 次数=" + count + " 间隔=" + intervalSec + "s");
        LocationFetchService.start(ctx, from, count, intervalSec, "sms");
    }

    private boolean matches(String cmd, String... words) {
        for (String w : words) {
            if (cmd.contains(w)) return true;
        }
        return false;
    }

    private List<Integer> numbersIn(String s) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(s);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
