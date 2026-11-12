package com.elder.launcher;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 守护自检 —— 家人最需要的一页
 *
 * 老人守护的能力分散在 4 个地方：Device Owner、默认桌面、各种特殊权限、
 * 厂商后台策略。任何一项没开，功能都会「看起来装了但没生效」。
 * 这页把它们逐项列出来，能亮绿就说明到位，能点按钮的直接跳到对应系统设置。
 */
public class GuardianCheckActivity extends AppCompatActivity {

    private LinearLayout list;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Ui.applyEdgeToEdge(this);
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
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        back.setTextColor(Ui.accent(this));
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("守护自检");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Ui.textPrimary(this));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title);
        root.addView(header);

        summary = new TextView(this);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        summary.setPadding(dp(24), 0, dp(24), dp(12));
        root.addView(summary);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button refresh = new Button(this);
        refresh.setText("重新检查");
        refresh.setOnClickListener(v -> rebuildChecks());
        root.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        rebuildChecks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildChecks();
    }

    private void rebuildChecks() {
        list.removeAllViews();
        int total = 0;
        int passed = 0;

        // 1. Device Owner —— 锁定任务、禁装禁卸的前提
        boolean doOn = ElderDeviceAdminReceiver.isDeviceOwner(this);
        total++;
        if (doOn) passed++;
        addCheck("设备管理器（Device Owner）", doOn,
                doOn ? "已激活，锁定任务 / 禁止安装 / 禁止卸载可用"
                        : "未激活。这项只能在电脑上做：tools\\provision.ps1 -Owner",
                doOn ? null : "查看命令", () -> showDpDialog());

        // 2. 默认桌面
        String homePkg = currentHomePackage();
        boolean homeOn = getPackageName().equals(homePkg);
        total++;
        if (homeOn) passed++;
        addCheck("默认桌面", homeOn,
                homeOn ? "本应用已是默认桌面，按 HOME 键会回到这里"
                        : "当前默认桌面：" + (homePkg == null ? "未知" : homePkg)
                        + "\n华为/荣耀会拒绝第三方桌面（ADB 命令和系统设置都会拦），"
                        + "这种情况下靠下面的「锁定任务模式」接管，效果一样",
                homeOn ? null : "打开桌面设置", () -> openHomeSettings());

        // 3. 悬浮窗（悬浮导航键需要）
        boolean overlay = Settings.canDrawOverlays(this);
        total++;
        if (overlay) passed++;
        addCheck("显示在其他应用上层（悬浮窗）", overlay,
                overlay ? "已授权，悬浮返回/主页/多任务键可用"
                        : "未授权，悬浮导航键无法显示",
                overlay ? null : "去授权", () -> {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                });

        // 4. 无障碍服务（悬浮键执行返回/主页需要）
        boolean a11y = isAccessibilityEnabled();
        total++;
        if (a11y) passed++;
        addCheck("无障碍服务", a11y,
                a11y ? "已开启，悬浮键可以执行返回 / 主页 / 多任务"
                        : "未开启，悬浮键点了没反应",
                a11y ? null : "去开启", () ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // 5. 通知使用权（通知白名单屏蔽需要）
        boolean notif = isNotificationListenerEnabled();
        total++;
        if (notif) passed++;
        addCheck("通知使用权", notif,
                notif ? "已开启，非白名单应用的通知会被拦下"
                        : "未开启，通知白名单不生效，垃圾通知照旧弹出",
                notif ? null : "去开启", () ->
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));

        // 6. 使用情况访问（周期清理后台的参考依据）
        boolean usage = hasUsageAccess();
        total++;
        if (usage) passed++;
        addCheck("使用情况访问", usage,
                usage ? "已开启" : "未开启，不影响清理后台，但统计不准",
                usage ? null : "去开启", () ->
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        // 7. 定位（远程定位 + 天气）
        boolean loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean bgLoc = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        total++;
        if (loc && bgLoc) passed++;
        addCheck("定位（含后台定位）", loc && bgLoc,
                (loc && bgLoc) ? "已授权，短信查询位置可用"
                        : "缺少定位或后台定位，锁屏时家人查不到位置",
                (loc && bgLoc) ? null : "去授权", () -> requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 9001));

        // 8. 短信收发（远程定位的通道）
        //    内网 WIFI 模式（不插卡）没有短信这条通道，改查「有没有连上家里 WIFI」
        if (IntranetMode.isOn(this)) {
            boolean netOk = IntranetMode.bannerText(this) == null;
            total++;
            if (netOk) passed++;
            addCheck("家里 WIFI", netOk,
                    IntranetMode.stateText(this),
                    netOk ? null : "去连 WIFI", () ->
                            startActivity(new Intent(this, WifiActivity.class)));
        } else {
            boolean sms = checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED;
            total++;
            if (sms) passed++;
            addCheck("短信收发权限", sms,
                    sms ? "已授权，家人可以发「位置」查询"
                            : "未授权，短信定位不可用",
                    sms ? null : "去授权", () -> requestPermissions(
                            new String[]{android.Manifest.permission.RECEIVE_SMS,
                                    android.Manifest.permission.SEND_SMS,
                                    android.Manifest.permission.READ_PHONE_STATE,
                                    android.Manifest.permission.READ_CALL_LOG}, 9002));
        }

        // 9. 电池优化白名单（厂商杀后台会让定时任务失效）
        boolean battery = isIgnoringBattery();
        total++;
        if (battery) passed++;
        addCheck("电池优化白名单", battery,
                battery ? "已加入，定时报时 / 清理不会被系统掐断"
                        : "未加入，长时间锁屏后定时任务可能失效",
                battery ? null : "去设置", () -> {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                });

        summary.setText("已就绪 " + passed + " / " + total + " 项"
                + (passed == total ? "　✅ 全部到位" : "　还差 " + (total - passed) + " 项"));
        summary.setTextColor(passed == total ? Ui.color(this, R.color.elder_ok)
                : Ui.color(this, R.color.elder_warning));
    }

    private void addCheck(String name, boolean ok, String detail,
                          String actionLabel, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(14), dp(20), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(this);
        dot.setText(ok ? "●" : "○");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dot.setTextColor(ok ? Ui.color(this, R.color.elder_ok) : Ui.color(this, R.color.elder_warning));
        head.addView(dot);

        TextView title = new TextView(this);
        title.setText("  " + name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(Ui.textPrimary(this));
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actionLabel != null && action != null) {
            Button btn = new Button(this);
            btn.setText(actionLabel);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setPadding(dp(12), 0, dp(12), 0);
            btn.setOnClickListener(v -> action.run());
            head.addView(btn);
        }
        card.addView(head);

        TextView desc = new TextView(this);
        desc.setText(detail);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(Ui.textSecondary(this));
        desc.setPadding(dp(18), dp(2), 0, 0);
        card.addView(desc);

        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(4), dp(12), dp(4));
        list.addView(card, lp);
    }

    private String currentHomePackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = getPackageManager().queryIntentActivities(home, 0);
            // 系统默认桌面通常是列表里的 preferred；用 resolveActivity 取最贴合的那个
            ResolveInfo best = getPackageManager().resolveActivity(home, 0);
            if (best != null && best.activityInfo != null) {
                return best.activityInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /** 通知使用权是否已授予（直接读系统设置，兼容所有版本，不依赖 API 27 的方法） */
    private boolean isNotificationListenerEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIgnoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void showDpDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("在电脑上激活 Device Owner")
                .setMessage("手机用数据线连电脑，项目目录下执行：\n\n"
                        + "tools\\provision.ps1 -Owner\n\n"
                        + "前提：手机没有登录任何账号（华为账号/谷歌账号），\n"
                        + "且没有设置锁屏密码。已登录账号的机器需要先恢复出厂。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
