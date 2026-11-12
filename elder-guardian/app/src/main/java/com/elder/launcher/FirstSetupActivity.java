package com.elder.launcher;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 首次配置向导（4 步，每步都能跳过）
 *
 * 为什么要向导：设置页有二十多个开关，家人第一次面对它只会一路乱点或者直接退出。
 * 向导只是把这二十多项砍成 4 个真正需要「当下决定」的问题：
 *   ① 欢迎（知道这是什么）
 *   ② 桌面放哪几个应用
 *   ③ 家人号码 + 联系谁
 *   ④ 报时 / 天气 / 清理 / 通知，用推荐值还是自己勾
 *
 * 原则：
 *   - 每步都能跳过，跳过 = 保持默认值，绝不弹「必须选择」；
 *   - 这里写的都只是预填，进主界面后在设置页随时能改；
 *   - 顺手把老的「导航方式 / 悬浮按键」并进第 4 步，不再单独占一页。
 */
public class FirstSetupActivity extends AppCompatActivity {

    private static final int REQ_PICK_CONTACT = 4201;
    private static final int STEP_COUNT = 4;

    private SharedPreferences prefs;

    private LinearLayout content;
    private TextView stepLabel;
    private Button backBtn;
    private int step = 0;

    /** 第 2 步勾选的应用包名 */
    private final List<String> pickedPkgs = new ArrayList<>();

    /** 第 3 步的家人号码输入框（选完联系人要回填，所以存字段） */
    private EditText familyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));
        root.setPadding(dp(20), dp(18), dp(20), dp(20));

        // ---- 顶栏：标题 + 第几步 + 上一步 ----
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("初始设置");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.textPrimary(this));
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stepLabel = new TextView(this);
        stepLabel.setTextSize(13);
        stepLabel.setTextColor(Ui.textSecondary(this));
        top.addView(stepLabel);

        backBtn = new Button(this);
        backBtn.setText("上一步");
        backBtn.setTextSize(13);
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(v -> {
            if (step > 0) {
                step--;
                render();
            }
        });
        top.addView(backBtn);

        root.addView(top);
        root.addView(divider());

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyEdgeToEdge(this);
        render();
    }

    // ==================== 步骤调度 ====================

    private void render() {
        content.removeAllViews();
        stepLabel.setText((step + 1) + " / " + STEP_COUNT);
        backBtn.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        switch (step) {
            case 0:
                renderWelcome();
                break;
            case 1:
                renderTiles();
                break;
            case 2:
                // 内网 WIFI 模式没有短信，填家人号码没意义，换成"确认连上 WIFI"
                if (IntranetMode.isOn(this)) {
                    renderNetwork();
                } else {
                    renderFamily();
                }
                break;
            default:
                renderReminders();
                break;
        }
    }

    private void goStep(int next) {
        step = next;
        render();
    }

    // ==================== ① 欢迎 ====================

    private void renderWelcome() {
        content.addView(heading("这是给老人用的桌面"));
        content.addView(body("装好之后，老人打开手机只会看到几个大卡片：打电话、发短信、微信……"
                + "点一下就能用，不会掉进设置里，也不会被乱装的软件骗。\n\n"
                + "接下来问 4 个问题，全部可以跳过。想改随时在设置里改。"));
        content.addView(body("锁屏、禁止安装这类硬能力，需要电脑上用 tools\\provision.ps1 激活一次；"
                + "没激活也能正常用，只是少了那几项。"));

        content.addView(primary("开始配置（1 分钟）", v -> goStep(1)));
        content.addView(ghost("直接进入主界面", v -> goToMain()));
    }

    // ==================== ② 桌面放什么 ====================

    private void renderTiles() {
        content.addView(heading("桌面上放哪些应用？"));
        content.addView(body("已经帮你勾好了常用的几个。没勾的以后还能在「设置 → 常用 → 桌面图标」里加。"));

        // 默认值：当前配置（首次就是按机型生成的默认卡片）
        if (pickedPkgs.isEmpty()) {
            for (String pkg : Prefs.tilesCsv(this).split(",")) {
                pkg = pkg.trim();
                if (!pkg.isEmpty()) pickedPkgs.add(pkg);
            }
        }

        // 按钮放在列表上方：这台机器有 30 多个可启动应用，
        // 放在下面要滚五六屏才找得到「下一步」，家人很容易以为卡住了。
        content.addView(primary("下一步", v -> {
            saveTiles();
            goStep(2);
        }));
        content.addView(ghost("先跳过（保持默认）", v -> goStep(2)));

        List<AppEntry> apps = launchableApps();
        if (apps.isEmpty()) {
            content.addView(body("没找到可添加的应用。"));
        }
        for (AppEntry app : apps) {
            CheckBox cb = new CheckBox(this);
            cb.setText(app.label);
            cb.setTextSize(16);
            cb.setTextColor(Ui.textPrimary(this));
            cb.setPadding(0, dp(6), 0, dp(2));
            cb.setChecked(pickedPkgs.contains(app.pkg));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    if (!pickedPkgs.contains(app.pkg)) pickedPkgs.add(app.pkg);
                } else {
                    pickedPkgs.remove(app.pkg);
                }
            });
            content.addView(cb);
        }
    }

    private void saveTiles() {
        StringBuilder sb = new StringBuilder();
        for (String pkg : pickedPkgs) {
            if (pkg.equals(getPackageName())) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
        }
        Prefs.putTilesCsv(this, sb.toString());
    }

    // ==================== ③ 家人与联系人 ====================

    /**
     * 内网 WIFI 模式的第 3 步：不填家人号码，改成确认网络。
     * 这种机器没有 SIM 卡，短信查位置用不了，唯一要保证的是连上 WIFI。
     */
    private void renderNetwork() {
        content.addView(heading("连上家里的 WIFI"));
        content.addView(body("这台手机不插卡，全靠 WIFI 上网：\n"
                + "· 连上任意 WIFI 就行，不限制连哪个；\n"
                + "· 上报时只带这个 WIFI 的名字，家人端能看到它连的是哪个网；\n"
                + "· 没连上 WIFI 时，主界面顶上会出现红条提醒。"));

        TextView now = new TextView(this);
        now.setText("现在：" + IntranetMode.stateText(this));
        now.setTextSize(16);
        now.setTextColor(Ui.textPrimary(this));
        now.setPadding(0, dp(6), 0, dp(10));
        content.addView(now);

        Button open = new Button(this);
        open.setText("打开无线网络");
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, WifiActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, "打不开无线网络页", Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(open, matchWrap());

        content.addView(primary("下一步", v -> goStep(3)));
        content.addView(ghost("先跳过（连上 WIFI 就行）", v -> goStep(3)));
    }

    private void renderFamily() {
        content.addView(heading("谁是家人？"));
        content.addView(body("填上家人自己的手机号。填了之后：\n"
                + "· 家人给老人机发短信「位置」，老人机自动回一条带地图链接的位置；\n"
                + "· 家人打个电话、响两声就挂断，老人机也会自动把位置发过来；\n"
                + "· 只认这几个号码，陌生人发短信查不到位置。"));

        familyInput = new EditText(this);
        familyInput.setInputType(InputType.TYPE_CLASS_PHONE);
        familyInput.setText(prefs.getString(LocConfig.KEY_FAMILY, ""));
        familyInput.setHint("手机号，多个用逗号隔开");
        familyInput.setTextSize(16);
        content.addView(familyInput, matchWrap());

        Button pick = new Button(this);
        pick.setText("从通讯录选一个");
        pick.setOnClickListener(v -> pickContact());
        content.addView(pick, matchWrap());

        content.addView(primary("下一步", v -> {
            saveFamily();
            goStep(3);
        }));
        content.addView(ghost("先跳过（以后在设置里填）", v -> goStep(3)));
    }

    private void saveFamily() {
        String raw = familyInput == null ? "" : familyInput.getText().toString().trim();
        String cleaned = LocConfig.cleanCsv(raw);
        if (cleaned.isEmpty()) return;                // 空的就当跳过，不覆盖已有配置
        prefs.edit()
                .putString(LocConfig.KEY_FAMILY, cleaned)
                .putBoolean(LocConfig.KEY_ENABLED, true)
                .putBoolean(LocConfig.KEY_CALL_TRIGGER, true)
                .apply();
        Toast.makeText(this, "已保存 " + LocConfig.parseCsv(cleaned).size() + " 个家人号码",
                Toast.LENGTH_SHORT).show();
    }

    private void pickContact() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            startActivityForResult(i, REQ_PICK_CONTACT);
        } catch (Exception e) {
            Toast.makeText(this, "打不开通讯录：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_CONTACT || resultCode != RESULT_OK) return;
        if (data == null || data.getData() == null) return;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(data.getData(),
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
            if (c != null && c.moveToFirst() && familyInput != null) {
                String number = c.getString(0);
                String existing = familyInput.getText().toString().trim();
                familyInput.setText(LocConfig.cleanCsv(
                        existing.isEmpty() ? number : existing + "," + number));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== ④ 提醒与后台 ====================

    private CheckBox cbChime, cbWeather, cbKill, cbNotif, cbFloating;

    private void renderReminders() {
        content.addView(heading("提醒和后台（用推荐值就行）"));
        content.addView(body("这几项默认开着对老人更友好；不想要就取消勾选。"));

        cbChime = switchRow("整点报时（8:00–20:00 每小时念一句）",
                prefs.getBoolean(Prefs.CHIME_ENABLED, true));
        cbWeather = switchRow("天气播报（8 / 12 / 18 点各念一次）",
                prefs.getBoolean(Prefs.WEATHER_ENABLED, true));
        cbKill = switchRow("定时清理后台（每 2 小时，白名单不动）",
                prefs.getBoolean(Prefs.KILL_ENABLED, true));
        cbNotif = switchRow("只让白名单应用发通知（推荐）",
                prefs.getBoolean(Prefs.NOTIF_FILTER, true));
        cbFloating = switchRow("悬浮导航键（实体键不好用时才需要）",
                prefs.getBoolean(Prefs.FLOATING_NAV, false));

        Button nav = new Button(this);
        nav.setText("改系统导航方式（建议三键）");
        nav.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "打不开系统设置", Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(nav, matchWrap());

        content.addView(primary("完成，进入主界面", v -> {
            saveReminders();
            goToMain();
        }));
        content.addView(ghost("全部用默认值", v -> {
            saveDefaults();
            goToMain();
        }));
    }

    private void saveReminders() {
        prefs.edit()
                .putBoolean(Prefs.CHIME_ENABLED, cbChime.isChecked())
                .putBoolean(Prefs.WEATHER_ENABLED, cbWeather.isChecked())
                .putBoolean(Prefs.KILL_ENABLED, cbKill.isChecked())
                .putBoolean(Prefs.FLOATING_NAV, cbFloating.isChecked())
                .apply();
        prefs.edit().putBoolean(Prefs.NOTIF_FILTER, cbNotif.isChecked()).apply();
        if (cbNotif.isChecked() && prefs.getString(Prefs.NOTIF_WHITELIST, "").isEmpty()) {
            prefs.edit().putString(Prefs.NOTIF_WHITELIST, defaultNotifWhitelist()).apply();
        }
        if (cbFloating.isChecked()) startFloatingNav();
        ElderApp.scheduleAllTimers(this);
        prefs.edit().putBoolean("setup_done", true).apply();
    }

    private void saveDefaults() {
        // 「全部用默认值」只把开关写成默认值，不动家人号码和桌面卡片
        prefs.edit()
                .putBoolean(Prefs.CHIME_ENABLED, true)
                .putBoolean(Prefs.WEATHER_ENABLED, true)
                .putBoolean(Prefs.KILL_ENABLED, true)
                .apply();
        prefs.edit().putBoolean(Prefs.NOTIF_FILTER, true).apply();
        if (prefs.getString(Prefs.NOTIF_WHITELIST, "").isEmpty()) {
            prefs.edit().putString(Prefs.NOTIF_WHITELIST, defaultNotifWhitelist()).apply();
        }
        ElderApp.scheduleAllTimers(this);
        prefs.edit().putBoolean("setup_done", true).apply();
    }

    private void startFloatingNav() {
        try {
            Intent svc = new Intent(this, FloatingNavService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception ignored) {
        }
    }

    private String defaultNotifWhitelist() {
        return "com.tencent.mm,com.tencent.mobileqq,com.android.mms,"
                + "com.google.android.apps.messaging,com.android.dialer,"
                + "com.google.android.dialer,com.android.phone,"
                + "com.eg.android.AlipayGphone,com.elder.launcher";
    }

    // ==================== 组装控件 ====================

    /** 桌面可选应用：有启动图标、不是自己 */
    private List<AppEntry> launchableApps() {
        List<AppEntry> list = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (!seen.add(pkg)) continue;
                String label = ri.loadLabel(pm).toString();
                list.add(new AppEntry(pkg, label));
            }
            Collections.sort(list, (a, b) -> {
                // 勾选的排前面，方便确认
                boolean pa = pickedPkgs.contains(a.pkg), pb = pickedPkgs.contains(b.pkg);
                if (pa != pb) return pa ? -1 : 1;
                return a.label.compareTo(b.label);
            });
        } catch (Exception ignored) {
        }
        return list;
    }

    static class AppEntry {
        final String pkg;
        final String label;

        AppEntry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(19);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Ui.textPrimary(this));
        tv.setPadding(0, dp(4), 0, dp(8));
        return tv;
    }

    private TextView body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setLineSpacing(dp(4), 1f);
        tv.setTextColor(Ui.textSecondary(this));
        tv.setPadding(0, 0, 0, dp(12));
        return tv;
    }

    private CheckBox switchRow(String text, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(16);
        cb.setTextColor(Ui.textPrimary(this));
        cb.setChecked(checked);
        cb.setPadding(0, dp(6), 0, dp(2));
        content.addView(cb);
        return cb;
    }

    private Button primary(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(Ui.accent(this));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button ghost(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setOnClickListener(l);
        return b;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x22888888);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainLauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
