package com.elder.launcher;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 短信查看（只读）
 *
 * 为什么要有：老人想看谁发过什么，但系统短信 App 里全是"新建短信""回复""拨号"，
 * 一按就发出去，很容易误发；也会误点进别人的会话乱回。
 * 这里只看不发：列表 + 点开看全文，没有任何发送入口。
 *
 * 想回短信就去桌面上的「发短信」卡片——那是刻意的两步操作。
 */
public class SmsInboxActivity extends AppCompatActivity {

    private LinearLayout list;
    private TextView empty;
    private final Map<String, String> nameCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Ui.applyEdgeToEdge(this);
        load();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(20);
        back.setTextColor(Ui.accent(this));
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("短信（只看，不能发）");
        title.setTextSize(20);
        title.setTextColor(Ui.textPrimary(this));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void load() {
        list.removeAllViews();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            showNoPermission();
            return;
        }

        List<Thread> threads = readThreads();
        if (threads.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂时没有短信。");
            tv.setTextSize(16);
            tv.setTextColor(Ui.textSecondary(this));
            tv.setPadding(dp(8), dp(24), dp(8), 0);
            list.addView(tv);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (Thread t : threads) {
            list.addView(makeRow(t, fmt));
        }
    }

    private View makeRow(final Thread t, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView who = new TextView(this);
        String name = t.name;
        who.setText(name == null || name.isEmpty() ? t.address : (name + "（" + mask(t.address) + "）"));
        who.setTextSize(17);
        who.setTextColor(Ui.textPrimary(this));
        who.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        who.setMaxLines(1);
        who.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(who, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(this);
        time.setText(fmt.format(new Date(t.date)));
        time.setTextSize(12);
        time.setTextColor(Ui.textSecondary(this));
        top.addView(time);

        row.addView(top);

        TextView snippet = new TextView(this);
        snippet.setText(t.body);
        snippet.setTextSize(14);
        snippet.setTextColor(Ui.textSecondary(this));
        snippet.setMaxLines(2);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        snippet.setPadding(0, dp(6), 0, 0);
        row.addView(snippet);

        if (t.count > 1) {
            TextView more = new TextView(this);
            more.setText("共 " + t.count + " 条");
            more.setTextSize(12);
            more.setTextColor(Ui.textSecondary(this));
            more.setPadding(0, dp(4), 0, 0);
            row.addView(more);
        }

        row.setOnClickListener(v -> showBody(t));
        return row;
    }

    /** 点开看全文：只有「关闭」，没有回复框 */
    private void showBody(final Thread t) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(t.body);
        tv.setTextSize(17);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(t.name == null || t.name.isEmpty() ? mask(t.address) : t.name)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showNoPermission() {
        TextView tv = new TextView(this);
        tv.setText("还没有「读取短信」权限，所以看不到内容。\n\n"
                + "这个权限只用来显示，不会上传、不会发送。\n"
                + "可以在「设置 → 应用 → 守护 → 权限」里打开。");
        tv.setTextSize(15);
        tv.setTextColor(Ui.textPrimary(this));
        tv.setPadding(dp(12), dp(24), dp(12), dp(12));
        list.addView(tv);

        TextView open = new TextView(this);
        open.setText("去系统应用设置");
        open.setTextSize(16);
        open.setTextColor(Ui.accent(this));
        open.setPadding(dp(12), dp(12), dp(12), dp(12));
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
        });
        list.addView(open);
    }

    // ==================== 读短信 ====================

    static class Thread {
        String address;
        String name;
        String body;
        long date;
        int count;
    }

    /**
     * 按号码归组，只保留每个号码的最新一条。
     * 不用系统会话表（不同 ROM 表结构不一样），直接从收件箱聚合，兼容性最好。
     */
    private List<Thread> readThreads() {
        List<Thread> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{"address", "body", "date"},
                    null, null, "date DESC");
            if (c == null) return out;

            Map<String, Thread> grouped = new LinkedHashMap<>();
            int guard = 0;
            while (c.moveToNext() && guard++ < 500) {
                String address = c.getString(0);
                String body = c.getString(1);
                long date = c.getLong(2);
                String key = address == null ? "" : address.replaceAll("[^0-9+]", "");
                if (key.isEmpty()) continue;

                Thread t = grouped.get(key);
                if (t == null) {
                    t = new Thread();
                    t.address = address;
                    t.body = body == null ? "" : body;
                    t.date = date;
                    t.count = 1;
                    grouped.put(key, t);
                } else {
                    t.count++;
                }
                if (grouped.size() >= 40) break;
            }
            out.addAll(grouped.values());
            for (Thread t : out) t.name = lookupName(t.address);
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private String lookupName(String number) {
        if (number == null || number.isEmpty()) return "";
        if (nameCache.containsKey(number)) return nameCache.get(number);
        String name = "";
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            c = getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        nameCache.put(number, name);
        return name;
    }

    private String mask(String number) {
        return LocConfig.mask(number);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
