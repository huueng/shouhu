package com.elder.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 远程定位相关配置（全部存在 elder_config 里，不依赖服务器）
 *
 * loc_enabled             是否允许家人通过短信查询位置（默认开）
 * call_trigger_enabled    是否允许「家人号码响铃挂断」触发定位（默认开）
 * loc_family_numbers      家人手机号，逗号分隔，只有这些号码能触发
 */
public class LocConfig {

    public static final String PREFS = "elder_config";
    public static final String KEY_ENABLED = "loc_enabled";
    public static final String KEY_CALL_TRIGGER = "call_trigger_enabled";
    public static final String KEY_FAMILY = "loc_family_numbers";

    private LocConfig() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 短信查询总开关 */
    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    /** 响铃挂断触发的开关 */
    public static boolean callTriggerEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CALL_TRIGGER, true);
    }

    /** 家人号码列表（已归一化、去重） */
    public static List<String> familyNumbers(Context ctx) {
        return parseCsv(prefs(ctx).getString(KEY_FAMILY, ""));
    }

    /** 号码是否在家人白名单内 */
    public static boolean isFamily(Context ctx, String number) {
        String n = normalize(number);
        if (n.isEmpty()) return false;
        for (String f : familyNumbers(ctx)) {
            if (f.equals(n)) return true;
        }
        return false;
    }

    public static List<String> parseCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split("[,，;；\\s]+")) {
            String n = normalize(s);
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /** 把用户输入的号码串清洗成标准 CSV（去空格、去 +86、去重） */
    public static String cleanCsv(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String n : parseCsv(csv)) {
            if (sb.length() > 0) sb.append(",");
            sb.append(n);
        }
        return sb.toString();
    }

    /**
     * 号码归一化：去掉空格/横杠/括号，去掉中国区号前缀
     * 例：+86 138-0013-8000 → 13800138000
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[^0-9+]", "");
        if (s.startsWith("+86")) {
            s = s.substring(3);
        } else if (s.startsWith("0086")) {
            s = s.substring(4);
        } else if (s.startsWith("86") && s.length() == 13) {
            s = s.substring(2);
        }
        return s;
    }

    /** 日志/提示里隐藏中间几位 */
    public static String mask(String number) {
        String n = normalize(number);
        if (n.length() < 7) return n;
        return n.substring(0, 3) + "****" + n.substring(n.length() - 4);
    }

    /** 设置页展示用：「3 个家人号码」 */
    public static String familySummary(Context ctx) {
        Set<String> set = new LinkedHashSet<>(familyNumbers(ctx));
        return set.isEmpty() ? "未设置" : set.size() + " 个";
    }
}
