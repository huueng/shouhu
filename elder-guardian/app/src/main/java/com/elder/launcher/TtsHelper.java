package com.elder.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * 语音播报工具（系统 TTS）
 *
 * 注意：很多国产机（华为/荣耀/小米）默认 TTS 引擎为空，
 * 但已内置讯飞引擎 com.iflytek.speechsuite，所以这里显式指定引擎，
 * 避免 TextToSpeech 初始化失败导致「没有声音」。
 */
public final class TtsHelper {

    private static final String TAG = "TtsHelper";

    /** 国内机型常见的预置引擎 */
    private static final String[] PREFERRED_ENGINES = {
            "com.iflytek.speechsuite",   // 讯飞（华为/荣耀/小米常见）
            "com.huawei.tts",            // 华为
            "com.baidu.duersdk.voice"    // 百度
    };

    private static TextToSpeech sTts;
    private static boolean sReady;
    private static String sPendingText;
    private static float sPendingRate = 0.9f;
    private static boolean sInitTried;
    /** 默认语速：老人听的，稍慢一点 */
    public static final float RATE_NORMAL = 0.9f;
    /** 来电播报的语速：比默认更慢，否则名字一闪就过去了 */
    public static final float RATE_CALLER = 0.68f;

    private TtsHelper() {
    }

    /** 播报一段文字（中文，语速稍慢，适合老人） */
    public static void speak(final Context ctx, final String text) {
        speak(ctx, text, RATE_NORMAL);
    }

    /** 指定语速播报。来电名字要更慢（0.68），否则听着像"一闪而过"。 */
    public static void speak(final Context ctx, final String text, final float rate) {
        if (text == null || text.isEmpty()) return;
        sPendingRate = rate;

        // 已就绪，直接播报
        if (sTts != null && sReady) {
            try {
                sTts.setSpeechRate(rate);
            } catch (Exception ignored) {
            }
            sTts.speak(text, TextToSpeech.QUEUE_ADD, null, "elder");
            return;
        }

        sPendingText = text;

        // 正在初始化中，等回调里播报
        if (sTts != null) return;

        if (sInitTried) {
            Log.w(TAG, "TTS 之前初始化失败，不再重试");
            return;
        }
        sInitTried = true;

        // 依次尝试首选引擎，最后退回默认引擎
        tryInitWithEngine(ctx, 0);
    }

    private static void tryInitWithEngine(final Context ctx, final int index) {
        TextToSpeech.OnInitListener listener = (status) -> {
            if (status == TextToSpeech.SUCCESS) {
                try {
                    int r = sTts.setLanguage(Locale.CHINA);
                    if (r == TextToSpeech.LANG_MISSING_DATA
                            || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "中文语音包不可用，setLanguage=" + r);
                    }
                    sTts.setSpeechRate(0.9f);
                    try {
                        AudioAttributes attrs = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        sTts.setAudioAttributes(attrs);
                    } catch (Exception ignored) {
                    }
                    sReady = true;
                    Log.i(TAG, "TTS 初始化成功");
                    if (sPendingText != null) {
                        sTts.setSpeechRate(sPendingRate);
                        sTts.speak(sPendingText, TextToSpeech.QUEUE_FLUSH, null, "elder");
                        sPendingText = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "TTS 参数设置失败", e);
                }
            } else {
                Log.e(TAG, "TTS 初始化失败 status=" + status + "，尝试下一个引擎");
                // 换下一个引擎重试
                if (index + 1 < PREFERRED_ENGINES.length) {
                    sTts = null;
                    tryInitWithEngine(ctx, index + 1);
                } else if (index + 1 == PREFERRED_ENGINES.length) {
                    // 最后用系统默认引擎
                    sTts = null;
                    try {
                        sTts = new TextToSpeech(ctx.getApplicationContext(), (st) -> {
                            if (st == TextToSpeech.SUCCESS) {
                                sTts.setLanguage(Locale.CHINA);
                                sTts.setSpeechRate(0.9f);
                                sReady = true;
                                if (sPendingText != null) {
                                    sTts.setSpeechRate(sPendingRate);
                                    sTts.speak(sPendingText, TextToSpeech.QUEUE_FLUSH, null, "elder");
                                    sPendingText = null;
                                }
                            } else {
                                Log.e(TAG, "系统默认 TTS 也失败 status=" + st);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "创建默认 TTS 失败", e);
                    }
                }
            }
        };

        try {
            if (index < PREFERRED_ENGINES.length) {
                sTts = new TextToSpeech(ctx.getApplicationContext(), listener,
                        PREFERRED_ENGINES[index]);
            }
        } catch (Exception e) {
            Log.e(TAG, "引擎 " + PREFERRED_ENGINES[index] + " 创建失败", e);
            sTts = null;
            tryInitWithEngine(ctx, index + 1);
        }
    }
}
