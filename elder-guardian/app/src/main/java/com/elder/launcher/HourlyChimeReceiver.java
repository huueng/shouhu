package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * 定时任务统一入口：
 * 1. 整点报时
 * 2. 周期清理后台
 * 3. 每日天气播报（可多次）
 */
public class HourlyChimeReceiver extends BroadcastReceiver {

    public static final String ACTION_HOURLY = "com.elder.launcher.ACTION_HOURLY_CHIME";
    public static final String ACTION_KILL = "com.elder.launcher.ACTION_PERIODIC_KILL";
    public static final String ACTION_WEATHER = "com.elder.launcher.ACTION_DAILY_WEATHER";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        SharedPreferences prefs = ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE);

        switch (action) {
            case ACTION_HOURLY: {
                if (!prefs.getBoolean("hourly_chime_enabled", true)) return;
                Calendar c = Calendar.getInstance();
                int h = c.get(Calendar.HOUR_OF_DAY);
                String period = h < 12 ? "上午" : (h < 18 ? "下午" : "晚上");
                int display = h == 0 ? 12 : (h > 12 ? h - 12 : h);
                TtsHelper.speak(ctx, "现在是" + period + display + "点整");
                break;
            }
            case ACTION_KILL: {
                if (prefs.getBoolean("auto_kill_enabled", true)) {
                    ProcessKiller.killBackground(ctx);
                }
                break;
            }
            case ACTION_WEATHER: {
                if (!prefs.getBoolean("weather_enabled", true)) return;
                WeatherHelper.fetchAsync(ctx, text ->
                        TtsHelper.speak(ctx, text == null ? "今天天气暂时无法获取" : "现在播报：" + text));
                break;
            }
            default:
                break;
        }
    }
}
