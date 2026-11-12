package com.elder.launcher;

import android.content.Context;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * 短信发送工具
 * 用 SmsManager 直接发送，不需要把本应用设成默认短信 App
 * 长文本自动分片（中文一条 70 字，定位消息通常 2 片）
 */
public class SmsResponder {

    private static final String TAG = "ElderSms";

    private SmsResponder() {
    }

    public static boolean send(Context ctx, String number, String text) {
        String to = LocConfig.normalize(number);
        if (to.isEmpty() || text == null || text.isEmpty()) return false;
        try {
            SmsManager sm = SmsManager.getDefault();
            if (sm == null) {
                Log.e(TAG, "SmsManager 不可用");
                return false;
            }
            ArrayList<String> parts = sm.divideMessage(text);
            sm.sendMultipartTextMessage(to, null, parts, null, null);
            Log.i(TAG, "定位短信已发出 -> " + LocConfig.mask(to) + "，分片 " + parts.size());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "发送短信失败", e);
            return false;
        }
    }
}
