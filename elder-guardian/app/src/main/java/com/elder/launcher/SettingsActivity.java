package com.elder.launcher;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 家人设置页（隐藏入口：主界面标题连点 8 次）
 *
 * 分组原则：常用的摊开，少用的点开。老人家里最常改的就那几项（锁定、桌面图标、报时），
 * 其余（应用管控、远程联网）收起来，免得一屏二十多个开关吓人。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "ElderSettings";
    private static final int REQ_FAMILY_CONTACT = 4101;
    private static final int REQ_IMPORT_PROFILE = 4102;

    private SharedPreferences prefs;
    private Button btnFamily;
    private String pendingFamilyEdit;
    private TextView inboxBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(28));
        root.setBackgroundColor(Ui.bg(this));
        scroll.addView(root);

        buildHeader(root);

        final boolean isDo = ElderDeviceAdminReceiver.isDeviceOwner(this);
        final boolean intranet = IntranetMode.isOn(this);

        // ================= 常用 =================
        LinearLayout common = addGroup(root, "常用", true);
        addAppearance(common);
        addHomeRow(common);
        addIconRow(common);
        addLockRow(common, isDo);
        if (!intranet) addCallReturnRow(common);
        addFloatingNavRow(common);

        // ================= 内网 WIFI 模式 =================
        LinearLayout net = addGroup(root, "内网 WIFI 模式", intranet);
        addIntranetRows(net, intranet);

        // ================= 应用管控 =================
        LinearLayout guard = addGroup(root, "应用管控与兜底", false);
        addBlockRows(guard, isDo);
        if (!intranet) addDataLimitRow(guard, isDo);
        addWatchdogRow(guard);

        // ================= 语音与提醒 =================
        LinearLayout voice = addGroup(root, "语音与提醒", false);
        addChimeRows(voice);
        if (!intranet) addCallerAnnounce(voice);

        // ================= 后台与通知 =================
        LinearLayout bg = addGroup(root, "后台与通知", false);
        addKillRows(bg);
        addNotifRows(bg);
        addTrackRow(bg);

        // ================= 定位和传输 =================
        LinearLayout remote = addGroup(root, "定位和传输", false);
        addLocateModeRow(remote);
        cardRemote = addRemoteRow(remote);
        cardReport = addReportRows(remote);
        addFastLinkRow(remote);
        applyLocateMode();
        addTransferRow(remote);
        addInboxRow(remote);

        // ================= 家人与联系人 =================
        // 内网模式没有电话和短信，这一组（联系人 / 短信查询 / 响铃定位）整体不显示
        if (!intranet) {
            LinearLayout people = addGroup(root, "家人与联系人", false);
            addContactRows(people);
            addLocateFamilyRows(people);
        }

        // ================= 更新与备份 =================
        LinearLayout ops = addGroup(root, "更新与备份", false);
        addDiagRows(ops);
        addUpdateRows(ops);
        addProfileRows(ops);

        // ================= 恢复 =================
        LinearLayout recover = addGroup(root, "解除锁定与恢复", false);
        addRecoverRows(recover, isDo);

        setContentView(scroll);
        Ui.applyEdgeToEdge(this);

        // 进设置页先解除屏幕固定，避免家人被困在里面
        if (isDo && prefs.getBoolean(Prefs.LOCK_TASK, false)) {
            try {
                stopLockTask();
            } catch (Exception ignored) {
            }
        }
    }

    // ============ 顶部 ============

    private void buildHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("家人设置");
        title.setTextSize(24);
        title.setTextColor(Ui.textPrimary(this));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        boolean isDo = ElderDeviceAdminReceiver.isDeviceOwner(this);
        TextView state = new TextView(this);
        state.setText(isDo ? "设备已托管（Device Owner 已激活）"
                : "设备未托管：锁定、禁装禁卸不可用，看「应用管控」里的说明");
        state.setTextSize(13);
        state.setTextColor(isDo ? Ui.color(this, R.color.elder_ok) : Ui.color(this, R.color.elder_warning));
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, 0, 0, dp(10));
        root.addView(state);

        Button btnCheck = new Button(this);
        btnCheck.setText("守护自检（逐项看还缺什么）");
        btnCheck.setOnClickListener(v -> startActivity(new Intent(this, GuardianCheckActivity.class)));
        root.addView(btnCheck, matchWrap());
    }

    // ============ 常用 ============

    private void addAppearance(LinearLayout parent) {
        CheckBox cbDark = addSwitch(parent, "深色模式", "晚上看着不刺眼；切换后立即生效");
        cbDark.setChecked(prefs.getBoolean(Prefs.DARK_MODE, false));
        cbDark.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.DARK_MODE, checked).apply();
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(checked
                    ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        });

        // 装了这套之后，进设置最常用的就是装机那几天。留一张卡片比连点 8 下时间省事，
        // 但平时挂在桌面上又占位置，所以做成可关。
        CheckBox cbCard = addSwitch(parent, "主界面显示「设置」卡片",
                "方便进设置；关掉后要连点 8 下时间才能进来");
        cbCard.setChecked(prefs.getBoolean(Prefs.HOME_SETTINGS_CARD, true));
        cbCard.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.HOME_SETTINGS_CARD, checked).apply();
            Toast.makeText(this, checked ? "已经放回主界面（返回就看到了）" : "已从主界面移除",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void addHomeRow(LinearLayout parent) {
        final TextView row = rowText();
        final Runnable paint = () -> {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            boolean actual = getPackageName().equals(Prefs.firstResolvedPackage(this, homeIntent));
            boolean wanted = prefs.getBoolean(Prefs.PERSISTENT_HOME, false);
            String state;
            if (actual) {
                state = "本应用（已接管）";
            } else if (wanted) {
                state = "原桌面（本机不允许第三方桌面，用锁定任务接管）";
            } else {
                state = "系统桌面";
            }
            row.setText("默认桌面：" + state + "\n点一下切换");
        };
        paint.run();
        row.setOnClickListener(v -> {
            if (!ElderDeviceAdminReceiver.isDeviceOwner(this)) {
                showNeedDoDialog();
                return;
            }
            if (prefs.getBoolean(Prefs.PERSISTENT_HOME, false)) {
                ElderApp.clearPersistentHome(this);
            } else {
                ElderApp.setPersistentHome(this);
                Toast.makeText(this, "华为/荣耀会拒绝第三方桌面，改用「锁定任务模式」即可", Toast.LENGTH_LONG).show();
            }
            paint.run();
        });
        parent.addView(row);
    }

    private void addIconRow(LinearLayout parent) {
        Button btn = new Button(this);
        btn.setText("桌面图标（加应用 / 移出应用）");
        btn.setOnClickListener(v -> startActivity(new Intent(this, AddAppActivity.class)));
        parent.addView(btn, matchWrap());
    }

    private void addLockRow(LinearLayout parent, boolean isDo) {
        CheckBox cbLock = addSwitch(parent, "锁定任务模式（老人无法退出）",
                isDo ? "状态栏关闭、HOME/后台键失效；长按标题可临时解除"
                        : "需要设备托管才能生效");
        cbLock.setChecked(prefs.getBoolean(Prefs.LOCK_TASK, false));
        cbLock.setEnabled(isDo);
        cbLock.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Prefs.LOCK_TASK, checked).apply());
    }

    /** 悬浮导航键：三个小圆点（返回/主页/多任务），默认关 */
    private void addFloatingNavRow(LinearLayout parent) {
        CheckBox cb = addSwitch(parent, "悬浮导航键（返回 / 主页 / 多任务）",
                "三个小圆点浮在桌面右下角，会挡住一点卡片；只有实体键失灵或手势导航不好用时才需要");
        cb.setChecked(prefs.getBoolean(Prefs.FLOATING_NAV, false));
        cb.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.FLOATING_NAV, checked).apply();
            if (checked) {
                startService(new Intent(this, FloatingNavService.class));
            } else {
                stopService(new Intent(this, FloatingNavService.class));
            }
        });
    }

    private void addCallReturnRow(LinearLayout parent) {
        CheckBox cb = addSwitch(parent, "通话结束后自动回到主界面",
                "只对「从本应用拨出去」的通话生效");
        cb.setChecked(prefs.getBoolean(Prefs.CALL_RETURN_HOME, true));
        cb.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Prefs.CALL_RETURN_HOME, checked).apply());
    }

    // ============ 内网 WIFI 模式 ============

    /**
     * 不插卡的「看视频机」。
     *
     * 这种机器的特点是：老人自己用老年机打电话，这台只放在家里刷视频，
     * 没有 SIM 卡、只连家里一两个 WIFI。所以电话/短信那一整套在这里全是空转，
     * 真正需要盯的只有「有没有连上家里那个 WIFI」。
     */
    private void addIntranetRows(LinearLayout parent, final boolean on) {
        CheckBox cb = addSwitch(parent, "内网 WIFI 模式（不插卡，只用来看视频）",
                "打开后：桌面去掉打电话 / 短信 / 联系人；短信查询、响铃定位、来电播报一并停用；"
                        + "定位、轨迹、主动上报照常走家里的 WIFI。");
        cb.setChecked(on);
        cb.setOnCheckedChangeListener((b, checked) -> {
            IntranetMode.setOn(this, checked);
            Toast.makeText(this, checked
                    ? "已打开内网 WIFI 模式，电话和短信已从界面撤掉"
                    : "已关闭，电话、短信、联系人回来了", Toast.LENGTH_LONG).show();
            recreate();
        });

        final TextView wifiRow = rowCard();
        final Runnable paintWifi = () -> {
            String cur = IntranetMode.currentSsid(this);
            wifiRow.setText("当前 WIFI：" + (cur == null
                    ? (IntranetMode.wifiOn(this) ? "没连上（点一下重新读）" : "无线网络是关的")
                    : cur)
                    + "\n只记录名字，不限制连哪个网。点一下刷新");
        };
        paintWifi.run();
        wifiRow.setOnClickListener(v -> paintWifi.run());
        parent.addView(wifiRow);

        final TextView checkRow = rowCard();
        checkRow.setText("网络体检：" + IntranetMode.stateText(this) + "\n点一下看 IPv6 和上报地址");
        checkRow.setOnClickListener(v -> showNetCheckDialog());
        parent.addView(checkRow);

        parent.addView(hintText("说明：\n"
                + "· 不设白名单：只要连上任意 WIFI 就算正常，上报时带上这个 WIFI 的名字。\n"
                + "· 只有「没连 WIFI / 无线网被关掉」时，主界面才会出红条提醒。\n"
                + "· 哪天这台机器插了卡，把本模式关掉就恢复电话和短信。"));
    }

    /** 网络体检：WIFI 名称、IPv6 有没有下发、上报通不通 */
    private void showNetCheckDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("模式：").append(IntranetMode.isOn(this) ? "内网 WIFI 模式（不插卡）" : "普通模式")
                .append('\n');
        sb.append("无线网络：").append(NetState.isWifiOn(this) ? "已开启" : "已关闭").append('\n');
        String cur = IntranetMode.currentSsid(this);
        sb.append("当前 WIFI：").append(cur == null ? "没连上" : cur).append('\n');

        String v6 = IntranetMode.ipv6(this);
        sb.append("IPv6：").append(v6.isEmpty() ? "没有（路由器可能没开 IPv6）" : v6).append('\n');
        sb.append("IPv4：").append(ipv4Text()).append("\n\n");

        String url = Prefs.getString(this, Prefs.REPORT_URL, "");
        sb.append("上报地址：").append(url == null || url.trim().isEmpty() ? "没填" : url).append('\n');
        sb.append("上报开关：").append(Prefs.getBool(this, Prefs.REPORT_ENABLED, false) ? "开" : "关")
                .append("　每 ").append(ReportUploader.intervalMinutes(this)).append(" 分钟\n");
        sb.append("上次结果：").append(Prefs.getString(this, "report_last", "还没传过")).append('\n');
        sb.append("待补发：").append(ReportUploader.pendingCount(this)).append(" 条\n\n");
        sb.append("提示：家里宽带只有 IPv6 时，WIFI 连上了但 IPv6 显示「没有」，上报一样发不出去，").append('\n');
        sb.append("先检查路由器有没有开 IPv6（多数光猫/路由器在「网络设置 - IPv6」里）。");

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("网络体检")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("测试域名解析", (d, w) ->
                        testDnsAsync(Prefs.getString(this, Prefs.REPORT_URL, "")))
                // 三个按钮刚好放得下；上报是这套东西最容易出问题的一环，
                // 不给个"当场试一次"的入口，就只能等 10 分钟的定时任务
                .setNegativeButton("立即上报", (d, w) -> uploadNow())
                .show();
    }

    /** 立刻上报一次，把结果直接弹出来（不等 10 分钟的定时任务） */
    private void uploadNow() {
        if (!ReportUploader.enabled(this)) {
            Toast.makeText(this, "上报开关没打开，或还没填地址", Toast.LENGTH_LONG).show();
            return;
        }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在上报…");
        pd.setCancelable(false);
        pd.show();
        ReportUploader.uploadAsync(this, true, (ok, msg) -> runOnUiThread(() -> {
            try {
                pd.dismiss();
            } catch (Exception ignored) {
            }
            String tail = ok ? "\n\n家人端/中继那边应该马上能看到新数据。"
                    : "\n\n点「测试域名解析」看是不是 DNS；"
                    + "能解析但连不上，多半是中继没起来或者端口没放通。";
            showTextDialog(ok ? "上报成功" : "上报失败",
                    (msg == null ? "" : msg) + tail
                            + "\n\n待补发：" + ReportUploader.pendingCount(this) + " 条");
        }));
    }

    /**
     * 单独测一次域名解析。
     *
     * 为什么值得单列：上报失败最常见的两种是「域名解析不了」和「证书不对」，
     * 界面上原来只有一句"连不上"，分不清是 DNS 还是反代。
     * 这里直接把解析结果（解析到哪些 IP、花了多久）摊开给人看。
     */
    private void testDnsAsync(final String url) {
        String host = null;
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        if (host == null || host.isEmpty()) {
            Toast.makeText(this, "还没填上报地址，没有域名可测", Toast.LENGTH_LONG).show();
            return;
        }
        final String h = host;
        Toast.makeText(this, "正在解析 " + h + " …", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String result;
            try {
                java.net.InetAddress[] all = java.net.InetAddress.getAllByName(h);
                StringBuilder r = new StringBuilder();
                int v6 = 0, v4 = 0;
                for (java.net.InetAddress a : all) {
                    if (a instanceof java.net.Inet6Address) v6++;
                    else v4++;
                    r.append(a.getHostAddress()).append('\n');
                }
                result = "解析成功（" + (System.currentTimeMillis() - t0) + " ms）\n"
                        + "IPv6 " + v6 + " 个 / IPv4 " + v4 + " 个\n\n" + r;
            } catch (Exception e) {
                result = "解析失败（" + (System.currentTimeMillis() - t0) + " ms）："
                        + e.getClass().getSimpleName() + "\n"
                        + (e.getMessage() == null ? "" : e.getMessage());
            }
            final String out = result;
            runOnUiThread(() -> showTextDialog("域名解析 · " + h, out
                    + "\n\n如果这里失败，但电脑上能解析，说明手机的 DNS 不返回 AAAA：\n"
                    + "· 路由器把 IPv6 的 DNS 关了，换成 223.5.5.5 / 2400:3200::1 试试\n"
                    + "· 或者把上报地址改成中继的 IP 字面量（IPv6 要加方括号）"));
        }, "dns-test").start();
    }

    /** 本机 IPv4（拿不到就写"读不到"） */
    private String ipv4Text() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                java.net.NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "读不到";
    }

    // ============ 应用管控 ============

    private void addBlockRows(LinearLayout parent, boolean isDo) {
        CheckBox cbBlockInstall = addSwitch(parent, "禁止安装未知来源应用",
                isDo ? "已生效：老人无法自行装应用" : "需要设备托管（点下面那行看原因）");
        cbBlockInstall.setChecked(prefs.getBoolean(Prefs.BLOCK_INSTALL, true));
        cbBlockInstall.setEnabled(isDo);
        cbBlockInstall.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.BLOCK_INSTALL, checked).apply();
            applyRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, checked);
        });

        CheckBox cbBlockUninstall = addSwitch(parent, "禁止卸载应用",
                isDo ? "已生效，包含本应用自身" : "需要设备托管");
        cbBlockUninstall.setChecked(prefs.getBoolean(Prefs.BLOCK_UNINSTALL, true));
        cbBlockUninstall.setEnabled(isDo);
        cbBlockUninstall.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.BLOCK_UNINSTALL, checked).apply();
            applyRestriction(android.os.UserManager.DISALLOW_UNINSTALL_APPS, checked);
            try {
                ElderDeviceAdminReceiver.getDPM(this).setUninstallBlocked(
                        ElderDeviceAdminReceiver.getComponentName(this), getPackageName(), checked);
            } catch (Exception ignored) {
            }
        });

        if (!isDo) {
            TextView why = rowText();
            why.setText("这两项为啥控制不了？点这里看原因和解法 ›");
            why.setTextColor(Ui.accent(this));
            why.setOnClickListener(v -> showNeedDoDialog());
            parent.addView(why);
        }
    }

    private void addWatchdogRow(LinearLayout parent) {
        CheckBox cb = addSwitch(parent, "看门狗：切回系统桌面就跳回来",
                "锁定任务正常时不用开；靠无障碍服务，最多连续纠正 5 次");
        cb.setChecked(prefs.getBoolean(Prefs.WATCHDOG, false));
        cb.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.WATCHDOG, checked).apply();
            if (checked && !isAccessibilityOn()) {
                Toast.makeText(this, "还没开无障碍服务，看门狗不会生效", Toast.LENGTH_LONG).show();
            }
        });
    }

    // ============ 语音与提醒 ============

    private void addChimeRows(LinearLayout parent) {
        CheckBox cbChime = addSwitch(parent, "整点报时", "到点语音报时");
        cbChime.setChecked(prefs.getBoolean(Prefs.CHIME_ENABLED, true));
        cbChime.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.CHIME_ENABLED, checked).apply();
            ElderApp.scheduleAllTimers(this);
        });
        addTimePickerRow(parent, "报时时间", Prefs.CHIME_HOURS, "8,12,18");

        CheckBox cbWeather = addSwitch(parent, "天气播报", "到点播报天气和气温");
        cbWeather.setChecked(prefs.getBoolean(Prefs.WEATHER_ENABLED, true));
        cbWeather.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.WEATHER_ENABLED, checked).apply();
            ElderApp.scheduleAllTimers(this);
        });
        addTimePickerRow(parent, "天气时间", Prefs.WEATHER_HOURS, "8,12,18");

        final TextView placeRow = rowText();
        placeRow.setPadding(dp(28), dp(2), 0, dp(10));
        final Runnable paintPlace = () -> {
            String name = WeatherHelper.placeName(this);
            placeRow.setText("天气地点：" + (name == null || name.isEmpty() ? "跟随定位" : name)
                    + "（点一下改）");
        };
        paintPlace.run();
        placeRow.setOnClickListener(v -> showPlaceDialog(paintPlace));
        parent.addView(placeRow);

        Button test = new Button(this);
        test.setText("试听播报（检查有没有声音）");
        test.setOnClickListener(v -> TtsHelper.speak(this, "现在是上午八点整，今天多云，气温十五度"));
        parent.addView(test, matchWrap());
    }

    private void addCallerAnnounce(LinearLayout parent) {
        final TextView row = rowText();
        final Runnable paint = () -> row.setText("来电播报：" + callerModeLabel() + "（点一下改）");
        paint.run();
        row.setOnClickListener(v -> {
            final String[] modes = {"name", "number", "off"};
            final String[] labels = {"念通讯录备注名（查不到念号码）", "念电话号码", "不播报，保持原铃声"};
            int cur = 0;
            String mode = prefs.getString(Prefs.CALLER_ANNOUNCE, "name");
            for (int i = 0; i < modes.length; i++) {
                if (modes[i].equals(mode)) cur = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("来电时怎么提醒")
                    .setSingleChoiceItems(labels, cur, (d, which) -> {
                        prefs.edit().putString(Prefs.CALLER_ANNOUNCE, modes[which]).apply();
                        paint.run();
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        parent.addView(row);
    }

    // ============ 后台与通知 ============

    /**
     * 轨迹：老人端本地记，家人端来拉。
     * 采样规则写在 TrackReceiver / TrackStore 里（10 分钟看一次，走够 80 米才记点）。
     */
    private void addTrackRow(LinearLayout parent) {
        CheckBox cb = addSwitch(parent, "记录行动轨迹",
                "每 10 分钟看一次位置，走出 80 米以上才记一个点；最多存 500 个（约 3–5 天）。"
                        + "只存在这台手机里，家人端点「拉取轨迹」才会上传。");
        cb.setChecked(prefs.getBoolean(Prefs.TRACK_ENABLED, true));
        cb.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Prefs.TRACK_ENABLED, checked).apply());

        final TextView row = rowText();
        final Runnable paint = () -> row.setText(
                "轨迹点：" + TrackStore.load(this).size() + " 个（点一下清空）");
        paint.run();
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空轨迹")
                .setMessage("删掉这台手机上已记录的 " + TrackStore.load(this).size()
                        + " 个点？\n家人端已经拉取过的路线不会被删。")
                .setPositiveButton("清空", (d, w) -> {
                    TrackStore.clear(this);
                    paint.run();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show());
        parent.addView(row);
    }

    /**
     * 移动数据限制：白名单之外的应用不许用流量。
     *
     * 只有 Android 11（API 30）以上才提供 setMeteredDataDisabledPackages，
     * 而且是 Device Owner 专属接口，所以低版本和未激活时只能如实说明"做不到"，
     * 不要给一个点了没反应的开关。
     */
    private void addDataLimitRow(LinearLayout parent, boolean isDo) {
        boolean supported = android.os.Build.VERSION.SDK_INT >= 30;
        boolean usable = supported && isDo;

        CheckBox cb = addSwitch(parent, "限制移动数据（白名单之外禁止用流量）", null);
        cb.setEnabled(usable);
        cb.setChecked(usable && prefs.getBoolean(Prefs.DATA_LIMIT, false));
        cb.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.DATA_LIMIT, checked).apply();
            int n = applyDataLimit(checked);
            String msg;
            if (n == -1) msg = "这台手机的系统版本不支持（需要 Android 11 及以上）";
            else if (n == -2) msg = "还没有激活 Device Owner，做不了";
            else if (n == -3) msg = "系统拒绝了，可能被 ROM 限制";
            else if (checked) msg = "已限制 " + n + " 个应用用移动数据（WiFi 不受影响）";
            else msg = "已取消限制";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });

        String hint;
        if (!supported) {
            hint = "当前系统是 Android " + android.os.Build.VERSION.RELEASE
                    + "，没有这个接口（需要 Android 11 及以上），开关不可用。\n"
                    + "替代办法：下拉菜单关掉移动数据，或者只在 WiFi 下给老人用。";
        } else if (!isDo) {
            hint = "需要先用 tools\\provision.ps1 -Owner 激活设备所有者，激活后这里才能打开。";
        } else {
            hint = "白名单沿用「通知白名单 + 清理白名单」，系统应用不动（避免连系统更新一起断掉）。"
                    + "关掉这个开关会立即恢复。";
        }
        parent.addView(hintText(hint));
    }

    /** @return 被限制的应用数；-1 系统不支持、-2 无 Device Owner、-3 系统拒绝 */
    private int applyDataLimit(boolean enable) {
        if (android.os.Build.VERSION.SDK_INT < 30) return -1;
        if (!ElderDeviceAdminReceiver.isDeviceOwner(this)) return -2;
        try {
            android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(this);
            android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(this);
            if (!enable) {
                dpm.setMeteredDataDisabledPackages(admin, new java.util.ArrayList<>());
                return 0;
            }

            java.util.Set<String> allow = new java.util.HashSet<>();
            allow.add(getPackageName());
            allow.add("android");
            allow.add("com.android.systemui");
            allow.add("com.android.settings");
            allow.add("com.android.phone");
            for (String csv : new String[]{
                    prefs.getString(Prefs.NOTIF_WHITELIST, ""),
                    prefs.getString(Prefs.KILL_WHITELIST, "")}) {
                for (String one : csv.split(",")) {
                    one = one.trim();
                    if (!one.isEmpty()) allow.add(one);
                }
            }

            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<String> disable = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                // 只动用户自己装的：系统应用一刀切会断掉推送、更新、时间同步等链路
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (allow.contains(ai.packageName)) continue;
                if (pm.checkPermission(android.Manifest.permission.INTERNET, ai.packageName)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) continue;
                disable.add(ai.packageName);
            }
            java.util.List<String> failed = dpm.setMeteredDataDisabledPackages(admin, disable);
            int bad = failed == null ? 0 : failed.size();
            int ok = Math.max(disable.size() - bad, 0);
            Log.i(TAG, "限制移动数据：" + ok + "/" + disable.size() + " 个应用生效");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "限制移动数据失败", e);
            return -3;
        }
    }

    private void addKillRows(LinearLayout parent) {
        CheckBox cbKill = addSwitch(parent, "定时清理后台",
                "只清第三方应用，白名单和系统应用不动");
        cbKill.setChecked(prefs.getBoolean(Prefs.KILL_ENABLED, true));
        cbKill.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.KILL_ENABLED, checked).apply();
            ElderApp.scheduleAllTimers(this);
        });

        final Button btnCycle = new Button(this);
        btnCycle.setText("清理周期：" + formatInterval(prefs.getInt(Prefs.KILL_INTERVAL, 120)));
        btnCycle.setOnClickListener(v -> {
            final int[] values = {30, 60, 120, 240, 480};
            final String[] labels = {"每 30 分钟", "每 1 小时", "每 2 小时", "每 4 小时", "每 8 小时"};
            int cur = prefs.getInt(Prefs.KILL_INTERVAL, 120);
            int idx = 2;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == cur) idx = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("清理周期")
                    .setSingleChoiceItems(labels, idx, (d, which) -> {
                        prefs.edit().putInt(Prefs.KILL_INTERVAL, values[which]).apply();
                        btnCycle.setText("清理周期：" + labels[which]);
                        ElderApp.scheduleAllTimers(this);
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        parent.addView(btnCycle, matchWrap());

        Button btnWhite = new Button(this);
        btnWhite.setText("清理白名单（" + countCsv(prefs.getString(
                Prefs.KILL_WHITELIST, ProcessKiller.DEFAULT_KILL_WHITELIST)) + " 个）");
        btnWhite.setOnClickListener(v -> showPackagePicker(
                Prefs.KILL_WHITELIST, ProcessKiller.DEFAULT_KILL_WHITELIST, "后台清理白名单",
                btn -> btn.setText("清理白名单（" + countCsv(prefs.getString(
                        Prefs.KILL_WHITELIST, ProcessKiller.DEFAULT_KILL_WHITELIST)) + " 个）"),
                btnWhite));
        parent.addView(btnWhite, matchWrap());

        Button btnNow = new Button(this);
        btnNow.setText("立即清理一次");
        btnNow.setOnClickListener(v -> {
            int n = ProcessKiller.killBackground(this);
            Toast.makeText(this, "已清理 " + n + " 个后台应用", Toast.LENGTH_SHORT).show();
        });
        parent.addView(btnNow, matchWrap());
    }

    private void addNotifRows(LinearLayout parent) {
        CheckBox cbFilter = addSwitch(parent, "启用通知过滤",
                "关掉后所有应用的通知都正常显示（默认只放行白名单里的应用）");
        cbFilter.setChecked(prefs.getBoolean(Prefs.NOTIF_FILTER, true));
        cbFilter.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.NOTIF_FILTER, checked).apply();
            Toast.makeText(this, checked ? "已启用通知过滤" : "已关闭，所有通知都会显示",
                    Toast.LENGTH_SHORT).show();
        });

        Button btn = new Button(this);
        btn.setText("允许通知的应用（" + countCsv(prefs.getString(
                Prefs.NOTIF_WHITELIST, defaultNotifWhitelist())) + " 个）");
        btn.setOnClickListener(v -> showPackagePicker(
                Prefs.NOTIF_WHITELIST, defaultNotifWhitelist(), "通知白名单",
                b -> b.setText("允许通知的应用（" + countCsv(prefs.getString(
                        Prefs.NOTIF_WHITELIST, defaultNotifWhitelist())) + " 个）"),
                btn));
        parent.addView(btn, matchWrap());

        TextView hint = hintText("不在白名单里的通知一律不显示；需要先开「通知使用权」，自检页可查。");
        parent.addView(hint);
    }

    // ============ 定位和传输 ============

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 定位方式二选一。
     *
     * A「家人直接连我」：老人机当服务器，家人端用域名+端口来问。
     *    省电、数据不出门；但只有家人主动查的时候才知道位置，做不了围栏告警。
     * B「推给 NAS 中继」：老人端按间隔把位置推出去，围栏判定在中继做。
     *    能出门用、能做告警；代价是每 N 分钟一次上报。
     *
     * 两条路同时开会让家人端不知道该看哪一条，所以做成单选。
     */
    private void addLocateModeRow(LinearLayout parent) {
        final TextView row = rowCard();
        final Runnable paint = () -> {
            boolean push = prefs.getBoolean(Prefs.REPORT_ENABLED, false);
            boolean serve = prefs.getBoolean(Prefs.LOCATE_SERVER, false);

            String mode;
            if (push && serve) {
                mode = "两个都开着（建议只留一个）";
            } else if (push) {
                mode = "推给 NAS 中继";
            } else if (serve) {
                mode = "家人直接连我";
            } else {
                mode = "都没开";
            }

            StringBuilder sb = new StringBuilder("定位方式：").append(mode);
            if (push) {
                String u = prefs.getString(Prefs.REPORT_URL, "");
                if (u == null || u.trim().isEmpty()) {
                    sb.append("\n⚠ 还没填中继地址，点下面「主动上报」去填");
                } else {
                    sb.append("\n每 ").append(ReportUploader.intervalMinutes(this)).append(" 分钟推一次");
                }
            }
            row.setText(sb.toString());
        };
        paint.run();

        row.setOnClickListener(v -> {
            boolean push = prefs.getBoolean(Prefs.REPORT_ENABLED, false);
            int cur = push ? 1 : 0;

            new AlertDialog.Builder(this)
                    .setTitle("定位方式")
                    .setSingleChoiceItems(new String[]{
                            "家人直接连我（老人机当服务器）",
                            "推给 NAS 中继（能做围栏告警）"
                    }, cur, (d, which) -> {
                        if (which == 0) {
                            prefs.edit()
                                    .putBoolean(Prefs.LOCATE_SERVER, true)
                                    .putBoolean(Prefs.REPORT_ENABLED, false)
                                    .apply();
                            LocateServer.start(this);
                            ReportUploader.cancel(this);
                            toast("已切换：家人端填域名+端口来连");
                        } else {
                            prefs.edit()
                                    .putBoolean(Prefs.LOCATE_SERVER, false)
                                    .putBoolean(Prefs.REPORT_ENABLED, true)
                                    .apply();
                            LocateServer.stop(this);
                            ReportUploader.schedule(this);
                            String u = prefs.getString(Prefs.REPORT_URL, "");
                            toast((u == null || u.trim().isEmpty())
                                    ? "已切换：还要填中继地址（下面「主动上报」）"
                                    : "已切换：开始按间隔推给中继");
                        }
                        d.dismiss();
                        paint.run();
                        applyLocateMode();
                    })
                    .show();
        });
        parent.addView(row);
        parent.addView(hintText("两种方式互斥：选「中继」才能做电子围栏告警；选「直接连我」最省电，"
                + "但只有家人来查时才知道位置。"));
    }

    private View cardRemote, cardReport;

    /**
     * 两张卡片只留一张：选哪个由「定位方式」决定。
     * 同时开着但只有一个真正在工作，最容易让人以为"两个都配好了"。
     */
    private void applyLocateMode() {
        boolean push = prefs.getBoolean(Prefs.REPORT_ENABLED, false);
        if (cardRemote != null) cardRemote.setVisibility(push ? View.GONE : View.VISIBLE);
        if (cardReport != null) cardReport.setVisibility(push ? View.VISIBLE : View.GONE);
    }

    // ===== 扫一扫：拍中继首页的「老人端」二维码，自动配好上报地址 =====

    private static final int REQ_SCAN = 8801;
    private static final int REQ_SCAN_ALBUM = 8802;
    private android.net.Uri scanUri;
    /** 扫码时临时放行过的相机包名，回到本页要立刻收回 */
    private String scanPkg;

    /**
     * 扫码：调系统相机拍一张，回来再用 ZXing 解码。
     *
     * 三件事必须都做对，少一件就"点了没反应"：
     * 1. FileProvider 要有 cache-path（res/xml/file_paths.xml），否则 getUriForFile 直接抛异常；
     * 2. 锁定任务模式下，相机不在白名单里会被系统拦掉 —— 扫之前临时加进去，扫完收回；
     * 3. 没装相机 / 被拦时要给一句人话，别只是静默失败。
     */
    private void startScanForReport() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "qr");
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "建不了临时目录，扫码用不了", Toast.LENGTH_LONG).show();
                return;
            }
            java.io.File f = new java.io.File(dir, "scan.jpg");

            scanUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);

            android.content.Intent i = new android.content.Intent(
                    android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, scanUri);
            i.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

            android.content.pm.ResolveInfo ri = getPackageManager()
                    .resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) {
                Toast.makeText(this, "这台手机没装相机应用，扫码用不了。\n"
                        + "可以在别的手机上扫，把地址抄过来填。", Toast.LENGTH_LONG).show();
                return;
            }
            scanPkg = ri.activityInfo.packageName;

            // 锁定任务下必须临时放行，不然 startActivity 直接 SecurityException
            boolean allowed = allowPkgInLockTask(scanPkg);
            Log.i(TAG, "扫码：相机包 " + scanPkg + "，锁定任务临时放行=" + allowed);

            startActivityForResult(i, REQ_SCAN);
        } catch (android.content.ActivityNotFoundException e) {
            restoreLockTaskList();
            Toast.makeText(this, "没找到能拍照的应用", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            restoreLockTaskList();
            Toast.makeText(this, "系统不允许从锁定任务里打开相机。\n"
                    + "先用手机相机拍下二维码，再在「家人设置 → 解除锁定与恢复」里临时解除后扫码。",
                    Toast.LENGTH_LONG).show();
            Log.w(TAG, "扫码被锁定任务拦下", e);
        } catch (Exception e) {
            restoreLockTaskList();
            Toast.makeText(this, "打不开相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.w(TAG, "扫码失败", e);
        }
    }

    /**
     * 从相册/文件里选一张二维码截图来解。
     * 拍照受对焦、反光、摩尔纹影响太大，家人把二维码截图发过来反而一次就成。
     * 锁定任务下 DocumentsUI（选图界面）同样要先临时放行。
     */
    private void pickQrFromAlbum() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);

            android.content.pm.ResolveInfo ri = getPackageManager()
                    .resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) {
                Toast.makeText(this, "这台手机没有选图的应用", Toast.LENGTH_LONG).show();
                return;
            }
            scanPkg = ri.activityInfo.packageName;
            allowPkgInLockTask(scanPkg);
            startActivityForResult(i, REQ_SCAN_ALBUM);
        } catch (Exception e) {
            restoreLockTaskList();
            Toast.makeText(this, "打不开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 把某个包临时加进锁定任务白名单（只有设备所有者能改） */
    private boolean allowPkgInLockTask(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty()) return false;
            if (!ElderDeviceAdminReceiver.isDeviceOwner(this)) return false;
            android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(this);
            android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(this);
            if (dpm == null || admin == null) return false;

            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (String s : ElderDeviceAdminReceiver.buildLockTaskPackages(this)) set.add(s);
            if (!set.add(pkg)) return true;   // 已经在名单里

            dpm.setLockTaskPackages(admin, set.toArray(new String[0]));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "放行相机失败", e);
            return false;
        }
    }

    /** 扫码结束（无论成败）把白名单恢复成"只有桌面卡片"，别长期把相机留在里面 */
    private void restoreLockTaskList() {
        try {
            if (!ElderDeviceAdminReceiver.isDeviceOwner(this)) return;
            android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(this);
            android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(this);
            if (dpm == null || admin == null) return;
            dpm.setLockTaskPackages(admin, ElderDeviceAdminReceiver.buildLockTaskPackages(this));
            Log.i(TAG, "扫码结束，锁定任务白名单已恢复");
        } catch (Exception e) {
            Log.w(TAG, "恢复白名单失败", e);
        } finally {
            scanPkg = null;
        }
    }

    private void handleScannedReportUrl() {
        android.graphics.Bitmap bmp = null;
        try {
            // 先量尺寸再缩到 1600px 解码。12MP 照片直接转 int[] 要 48MB，老人机必挂。
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            java.io.InputStream is = getContentResolver().openInputStream(scanUri);
            android.graphics.BitmapFactory.decodeStream(is, null, o);
            is.close();

            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 2400) {
                sample *= 2;
            }

            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            is = getContentResolver().openInputStream(scanUri);
            bmp = android.graphics.BitmapFactory.decodeStream(is, null, o2);
            is.close();

            String text = QrUtil.decode(bmp);
            if (text == null || text.trim().isEmpty()) {
                // 用对话框而不是 Toast：扫码失败多半发生在家人在手机上折腾的时候，
                // Toast 3 秒就消失，等于没说清
                String kept = QrUtil.keepFailedPhoto(this, bmp, "上报码");
                new AlertDialog.Builder(this)
                        .setTitle("没识别出二维码")
                        .setMessage("照片里没找到二维码。\n\n"
                                + "· 手机离二维码 20–30 厘米，别太近（太近对不上焦）\n"
                                + "· 别开闪光灯，屏幕反光会读不出来\n"
                                + "· 二维码要完整入画，四角别被裁掉\n\n"
                                + "实在扫不上，可以用「从相册选一张截图」，"
                                + "或者把地址直接填进「接收地址」。"
                                + (kept != null ? "\n\n这张照片已留档：\n" + kept : ""))
                        .setPositiveButton("再试一次", (d, w) -> startScanForReport())
                        .setNegativeButton("算了", null)
                        .show();
                return;
            }

            text = text.trim();
            if (!text.matches("(?i)^https?://.*")) {
                text = "https://" + text;
            }
            if (text.indexOf("/api/report") < 0) {
                new AlertDialog.Builder(this)
                        .setTitle("这个码不是上报地址")
                        .setMessage("扫到的是：\n" + text + "\n\n"
                                + "要扫的是中继网页上那台老人的「老人端」二维码，"
                                + "内容是 …/api/report?k=令牌。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }

            final String url = text;
            prefs.edit()
                    .putString(Prefs.REPORT_URL, url)
                    .putBoolean(Prefs.REPORT_ENABLED, true)
                    .putBoolean(Prefs.LOCATE_SERVER, false)
                    .putInt(Prefs.REPORT_INTERVAL, ReportUploader.intervalMinutes(this))
                    .apply();

            LocateServer.stop(this);
            ReportUploader.schedule(this);
            applyLocateMode();

            new AlertDialog.Builder(this)
                    .setTitle("已扫码配置")
                    .setMessage("上报地址：\n" + url + "\n\n已切到「推给 NAS 中继」，正在测试…")
                    .setPositiveButton("好", (d, w) ->
                            ReportUploader.uploadAsync(this, true, (ok, msg) ->
                                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()))
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "识别失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /**
     * 远程访问：DDNS 与定位服务本来就是一件事（域名指过来 + 端口开出去），
     * 合成一个入口，状态一屏看完，省得家人在两个按钮之间来回跳。
     */
    private View addRemoteRow(LinearLayout parent) {
        final TextView row = rowCard();
        final Runnable paint = () -> {
            boolean ddnsOn = DdnsService.RUNNING;
            boolean locOn = LocateServer.RUNNING;
            String msg = DdnsUpdater.lastMessage(this);
            row.setText("远程访问（域名 + 定位服务）"
                    + "\n域名：" + DdnsUpdater.hostname(this)
                    + "（" + DdnsUpdater.providerLabel(this) + (ddnsOn ? "·已开启" : "·未开启") + "）"
                    + "\n定位：" + (locOn ? "已开启" : "未开启")
                    + "\n" + (msg == null || msg.isEmpty() ? "还没同步过" : msg)
                    + "\n点一下查看 / 扫码给家人");
        };
        paint.run();
        row.setOnClickListener(v -> showRemoteDialog());
        parent.addView(row);
        parent.addView(hintText("域名同步好之后，家人端填域名就能连；二维码也会自动用域名而不是一长串 IPv6。"));
        return row;
    }

    /** 二维码/家人端该填的地址：DDNS 同步成功就用域名，否则退回本机 IPv6 */
    private String preferredHost() {
        if (DdnsUpdater.enabled(this) && DdnsUpdater.lastOk(this)) {
            String host = DdnsUpdater.hostname(this);
            if (host != null && host.contains(".") && !host.startsWith("http")) return host;
        }
        return pickHost(LocateServer.localAddresses());
    }

    private void showRemoteDialog() {
        final LinearLayout box = verticalBox();

        final TextView status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Ui.textPrimary(this));
        status.setTextIsSelectable(true);
        status.setText(remoteStatusText());
        box.addView(status);

        // 二维码：优先域名
        final String host = preferredHost();
        final String key = currentKey();
        if (host != null) {
            ImageView qr = new ImageView(this);
            android.graphics.Bitmap bmp = QrUtil.encode(
                    QrUtil.locateConfigUri(host, LocateServer.PORT, key), dp(230));
            if (bmp != null) {
                qr.setImageBitmap(bmp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(230), dp(230));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(8);
                qr.setLayoutParams(lp);
                box.addView(qr);
                TextView hint = hintText("家人端扫这个码即可自动配置（当前用：" + host + "）");
                hint.setGravity(Gravity.CENTER_HORIZONTAL);
                hint.setPadding(0, dp(4), 0, dp(6));
                box.addView(hint);
            }
        }

        Button btnDdns = new Button(this);
        btnDdns.setText("配置域名同步（DDNS）");
        box.addView(btnDdns);

        Button btnNow = new Button(this);
        btnNow.setText("立即同步一次");
        box.addView(btnNow);

        Button btnLocate = new Button(this);
        btnLocate.setText(LocateServer.RUNNING ? "停止定位服务" : "开启定位服务");
        box.addView(btnLocate);

        Button btnKey = new Button(this);
        btnKey.setText("修改查询密钥");
        box.addView(btnKey);

        box.addView(hintText("提示：家人端只认「域名 + 端口 + 密钥」三样东西。"
                + "\n域名没同步成功时二维码会用本机 IPv6，换网络后就会失效——所以建议先把 DDNS 配好。"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("远程访问")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .create();

        btnDdns.setOnClickListener(v -> {
            dialog.dismiss();
            showDdnsEditDialog();
        });
        btnNow.setOnClickListener(v -> {
            status.setText(remoteStatusText() + "\n\n正在同步…");
            DdnsUpdater.updateAsync(this, true, (ok, ip, msg) -> {
                status.setText(remoteStatusText());
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
        btnLocate.setOnClickListener(v -> {
            if (LocateServer.RUNNING) {
                LocateServer.stop(this);
                Prefs.putBool(this, Prefs.LOCATE_SERVER, false);
                dialog.dismiss();
                new android.os.Handler().postDelayed(this::showRemoteDialog, 700);
            } else {
                dialog.dismiss();
                toggleLocateService(true);
            }
        });
        btnKey.setOnClickListener(v -> {
            dialog.dismiss();
            showKeyDialog();
        });

        dialog.show();
    }

    private String currentKey() {
        String key = prefs.getString(Prefs.LOCATE_KEY, "");
        if (key == null || key.isEmpty()) {
            key = randomKey();
            prefs.edit().putString(Prefs.LOCATE_KEY, key).apply();
        }
        return key;
    }

    private String remoteStatusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("域名同步：").append(DdnsService.RUNNING ? "已开启" : "未开启")
                .append("（").append(DdnsUpdater.providerLabel(this)).append("）");
        sb.append("\n  ").append(DdnsUpdater.hostname(this));
        String msg = DdnsUpdater.lastMessage(this);
        sb.append("\n  ").append(msg == null || msg.isEmpty() ? "还没同步过" : msg);
        long t = DdnsUpdater.lastTime(this);
        if (t > 0) {
            sb.append("\n  ").append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.CHINA).format(new java.util.Date(t)));
        }
        sb.append("\n\n定位服务：").append(LocateServer.RUNNING ? "已开启" : "未开启");
        sb.append("\n  端口 ").append(LocateServer.PORT).append("，密钥 ").append(currentKey());
        return sb.toString();
    }

    /**
     * 开启定位服务前先确认域名同步是否就绪。
     * 不强制拦死（家里人可能只想先内网试），但要让人清楚知道公网会用不了。
     */
    private void toggleLocateService(boolean enable) {
        if (!enable) {
            LocateServer.stop(this);
            Prefs.putBool(this, Prefs.LOCATE_SERVER, false);
            return;
        }
        boolean ddnsReady = DdnsUpdater.enabled(this) && DdnsUpdater.lastOk(this);
        if (ddnsReady) {
            Prefs.putBool(this, Prefs.LOCATE_SERVER, true);
            LocateServer.start(this);
            Toast.makeText(this, "定位服务已开启", Toast.LENGTH_SHORT).show();
            new android.os.Handler().postDelayed(this::showRemoteDialog, 800);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("还没配好域名同步")
                .setMessage("定位服务要能被家人从外网访问，前提是域名跟着本机 IPv6 自动更新。"
                        + "现在还没同步成功，直接开启的话：\n\n"
                        + "· 在同一 WiFi 下可以查\n"
                        + "· 换到移动数据就失效（地址变了，家人端不知道）\n\n"
                        + "建议先点「配置域名同步」，同步成功后再开。")
                .setPositiveButton("先去配 DDNS", (d, w) -> showDdnsEditDialog())
                .setNeutralButton("仅内网临时开启", (d, w) -> {
                    Prefs.putBool(this, Prefs.LOCATE_SERVER, true);
                    LocateServer.start(this);
                    new android.os.Handler().postDelayed(this::showRemoteDialog, 800);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDdnsEditDialog() {
        LinearLayout box = verticalBox();

        box.addView(hintText("服务商"));
        final android.widget.Spinner providerSpinner = new android.widget.Spinner(this);
        String[] providers = {
                "Dynv6（免费，最简单）",
                "Cloudflare（us.kg 等自有域名）",
                "自定义 Webhook（自己写接口）"
        };
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, providers);
        providerSpinner.setAdapter(adapter);
        String currentProvider = DdnsUpdater.provider(this);
        providerSpinner.setSelection(DdnsUpdater.PROVIDER_CLOUDFLARE.equals(currentProvider) ? 1
                : DdnsUpdater.PROVIDER_CUSTOM.equals(currentProvider) ? 2 : 0);
        box.addView(providerSpinner);

        box.addView(hintText("域名（要同步的那条记录的完整名字）"));
        final EditText hostInput = new EditText(this);
        hostInput.setText(DdnsUpdater.hostname(this));
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(hostInput);

        box.addView(hintText("Token / 密钥（Cloudflare 用 API Token；自定义可留空）"));
        final EditText tokenInput = new EditText(this);
        tokenInput.setText(DdnsUpdater.token(this));
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(tokenInput);

        box.addView(hintText("Cloudflare 区域名（可留空自动识别，例如 us.kg）"));
        final EditText zoneInput = new EditText(this);
        zoneInput.setText(DdnsUpdater.zone(this));
        zoneInput.setHint("us.kg");
        zoneInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(zoneInput);

        box.addView(hintText("自定义 URL（只给「自定义 Webhook」用），支持占位符 {ip} {ip4} {hostname}"));
        final EditText urlInput = new EditText(this);
        urlInput.setText(DdnsUpdater.customUrl(this));
        urlInput.setHint("https://example.com/ddns?host={hostname}&ip={ip}&key=xxx");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(urlInput);

        box.addView(hintText("自定义请求头（可空，形如 Authorization: Bearer xxx）"));
        final EditText headerInput = new EditText(this);
        headerInput.setText(DdnsUpdater.customHeader(this));
        headerInput.setHint("Authorization: Bearer xxx");
        headerInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(headerInput);

        final CheckBox cbIpv4 = new CheckBox(this);
        cbIpv4.setText("同时同步 IPv4（A 记录）");
        cbIpv4.setTextSize(15);
        cbIpv4.setChecked(DdnsUpdater.syncIpv4(this));
        box.addView(cbIpv4);
        box.addView(hintText("手机拿到 192.168 / 10.x / 100.64 这类内网或运营商 NAT 地址时会自动跳过，"
                + "只有真公网 IPv4 才有意义。"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("域名同步（DDNS）")
                .setView(sv)
                .setPositiveButton("保存并立即同步", (d, w) -> {
                    int idx = providerSpinner.getSelectedItemPosition();
                    String host = hostInput.getText().toString().trim();
                    String token = tokenInput.getText().toString().trim();
                    String zone = zoneInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();

                    if (host.isEmpty() && idx != 2) {
                        Toast.makeText(this, "域名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (idx == 2 && url.isEmpty()) {
                        Toast.makeText(this, "自定义模式要填 URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    DdnsUpdater.setProvider(this, idx == 1 ? DdnsUpdater.PROVIDER_CLOUDFLARE
                            : idx == 2 ? DdnsUpdater.PROVIDER_CUSTOM
                            : DdnsUpdater.PROVIDER_DYNV6);
                    Prefs.putString(this, "ddns_host", host);
                    Prefs.putString(this, "ddns_token", token);
                    Prefs.putString(this, "ddns_zone", zone);
                    Prefs.putString(this, "ddns_custom_url", url);
                    Prefs.putString(this, "ddns_custom_header",
                            headerInput.getText().toString().trim());
                    Prefs.putBool(this, "ddns_ipv4", cbIpv4.isChecked());

                    Prefs.putBool(this, "ddns_enabled", true);
                    if (!DdnsService.RUNNING) DdnsService.start(this);

                    DdnsUpdater.updateAsync(this, true, (ok, ip, msg) ->
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
                    new android.os.Handler().postDelayed(this::showRemoteDialog, 1600);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addTransferRow(LinearLayout parent) {
        Button btn = new Button(this);
        btn.setText("网页传包（扫码打开，10 分钟自动关）");
        btn.setOnClickListener(v -> showTransferDialog());
        parent.addView(btn, matchWrap());
        parent.addView(hintText("只在当前 WiFi 内可访问；收到的文件存到「下载 / 老人守护」。"));
    }

    private void addInboxRow(LinearLayout parent) {
        inboxBtn = new Button(this);
        updateInboxLabel();
        inboxBtn.setOnClickListener(v -> showInbox());
        parent.addView(inboxBtn, matchWrap());
    }

    private void updateInboxLabel() {
        if (inboxBtn != null) inboxBtn.setText("已收到的文件（" + inboxCount() + " 个）");
    }

    // ============ 家人与联系人 ============

    private void addContactRows(LinearLayout parent) {
        Button btn = new Button(this);
        btn.setText("联系人卡片（" + ContactStore.load(this).size() + " 个）");
        btn.setOnClickListener(v -> startActivity(new Intent(this, ContactManageActivity.class)));
        parent.addView(btn, matchWrap());
        parent.addView(hintText("添加后主界面多一个大卡片，点一下直接拨号，可换照片。"));
    }

    private void addLocateFamilyRows(LinearLayout parent) {
        CheckBox cbLoc = addSwitch(parent, "短信定位查询", "家人发短信「位置」查询，不用服务器");
        cbLoc.setChecked(prefs.getBoolean(LocConfig.KEY_ENABLED, true));
        cbLoc.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(LocConfig.KEY_ENABLED, checked).apply());

        CheckBox cbCall = addSwitch(parent, "响铃挂断也能查位置", "家人号码响一两声挂断即可");
        cbCall.setChecked(prefs.getBoolean(LocConfig.KEY_CALL_TRIGGER, true));
        cbCall.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(LocConfig.KEY_CALL_TRIGGER, checked).apply());

        btnFamily = new Button(this);
        updateFamilyLabel();
        btnFamily.setOnClickListener(v -> showFamilyNumberDialog());
        parent.addView(btnFamily, matchWrap());

        Button test = new Button(this);
        test.setText("测试：立即定位并发短信给第一个家人");
        test.setOnClickListener(v -> testLocate());
        parent.addView(test, matchWrap());

        parent.addView(hintText("指令（只认上面的号码）：\n"
                + "位置 / 跟踪 / 跟踪 10 / 跟踪 10 30\n"
                + "短信里带高德链接，点开就能看。"));
    }

    // ============ 恢复 ============

    // ============ 运维与迁移 ============

    /** 诊断 / 使用统计 / 短信只读 */
    private void addDiagRows(LinearLayout parent) {
        Button diag = new Button(this);
        diag.setText("诊断（看哪一项失效了）");
        diag.setOnClickListener(v -> showTextDialog("诊断", DiagReport.buildText(this)));
        parent.addView(diag, matchWrap());

        Button usage = new Button(this);
        usage.setText("使用统计（近 7 天）");
        usage.setOnClickListener(v -> {
            Toast.makeText(this, "正在统计…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final String text = UsageStore.summaryText(this);
                runOnUiThread(() -> showTextDialog("使用统计", text));
            }).start();
        });
        parent.addView(usage, matchWrap());

        if (!IntranetMode.isOn(this)) {
            Button sms = new Button(this);
            sms.setText("短信查看（只读，不能发）");
            sms.setOnClickListener(v -> startActivity(new Intent(this, SmsInboxActivity.class)));
            parent.addView(sms, matchWrap());
            parent.addView(hintText("诊断结果可以直接发给家人/我；短信页只显示，没有任何发送入口。"));
        } else {
            parent.addView(hintText("诊断结果可以直接发给家人/我。内网 WIFI 模式没有短信，短信页已隐藏。"));
        }
    }

    /**
     * 秒级响应（快通道）。
     *
     * 家人按「催一次定位」时，原来最坏要等一个上报周期（10 分钟）才轮到老人机取命令。
     * 开了这个，老人机对中继挂长轮询，几秒内就能下发并回报。
     * 代价：每 25 秒一次心跳，约 3–4 MB/天，耗电略增（低电量且没充电时会自动暂停）。
     */
    private void addFastLinkRow(LinearLayout parent) {
        CheckBox cb = addSwitch(parent, "秒级响应（家人查询几秒内到）",
                "每 25 秒和 NAS 中继保持一次心跳；关掉就退回「每 10 分钟上报时才收命令」");
        cb.setChecked(FastLinkService.enabled(this));
        cb.setOnCheckedChangeListener((b, checked) -> FastLinkService.setEnabled(this, checked));
    }

    /** 主动上报（NAS 中继 / webhook） */
    private View addReportRows(LinearLayout parent) {
        final TextView row = rowCard();
        final Runnable paint = () -> {
            boolean on = ReportUploader.enabled(this);
            String url = prefs.getString(Prefs.REPORT_URL, "");
            String last = prefs.getString("report_last", "");

            StringBuilder sb = new StringBuilder("主动上报：");
            if (on) {
                sb.append("已开启（每 ").append(ReportUploader.intervalMinutes(this)).append(" 分钟）");
            } else {
                sb.append("未开启");
                if (url == null || url.trim().isEmpty()) {
                    sb.append("（还没填地址）");
                } else {
                    sb.append("（有地址但开关没打开）");
                }
            }
            if (last != null && !last.isEmpty()) {
                sb.append("\n").append(last);
            }
            int pend = ReportUploader.pendingCount(this);
            if (pend > 0) {
                sb.append("\n待补发 ").append(pend).append(" 条（网络恢复后自动发）");
            }
            row.setText(sb.toString());
        };
        paint.run();
        row.setOnClickListener(v -> {
            LinearLayout box = verticalBox();
            final AlertDialog[] dlgHolder = new AlertDialog[1];

            final CheckBox cb = new CheckBox(this);
            cb.setText("开启主动上报");
            cb.setTextSize(15);
            cb.setChecked(prefs.getBoolean(Prefs.REPORT_ENABLED, false));
            box.addView(cb);

            box.addView(hintText("上报间隔（分钟）——围栏告警越短越及时，也更耗电"));
            final RadioGroup rg = new RadioGroup(this);
            rg.setOrientation(RadioGroup.HORIZONTAL);
            int cur = ReportUploader.intervalMinutes(this);
            int[] opts = {5, 10, 15, 30, 60};
            for (int m : opts) {
                RadioButton rb = new RadioButton(this);
                rb.setId(m);
                rb.setText(String.valueOf(m));
                rb.setTextSize(14);
                rg.addView(rb);
                if (m == cur) rb.setChecked(true);
            }
            box.addView(rg);

            box.addView(hintText("接收地址（POST JSON）"));
            final EditText url = new EditText(this);
            url.setText(prefs.getString(Prefs.REPORT_URL, ""));
            url.setHint("https://你的域名:812/api/report?k=设备令牌");
            url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            box.addView(url);

            Button scan = new Button(this);
            scan.setText("扫一扫（扫中继首页的「老人端」二维码）");
            scan.setOnClickListener(x -> {
                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                startScanForReport();
            });
            box.addView(scan);

            // 拍不清楚时的兜底：让别人把二维码截图发过来，直接从相册选
            Button fromAlbum = new Button(this);
            fromAlbum.setText("从相册选一张二维码截图");
            fromAlbum.setOnClickListener(x -> {
                if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                pickQrFromAlbum();
            });
            box.addView(fromAlbum);

            box.addView(hintText("发送内容：当前位置 + 电量等状态 + 上次以来的轨迹点。\n"
                    + "填 NAS 中继时，地址要以 /api/report?k= 结尾，k 是设备令牌。\n"
                    + "不填就完全不上报，老人机自己也能正常用。"));

            dlgHolder[0] = new AlertDialog.Builder(this)
                    .setTitle("主动上报")
                    .setView(box)
                    .setPositiveButton("保存并测试", (d, w) -> {
                        int picked = rg.getCheckedRadioButtonId();
                        if (picked < 5) picked = 10;

                        prefs.edit()
                                .putBoolean(Prefs.REPORT_ENABLED, cb.isChecked())
                                .putString(Prefs.REPORT_URL, url.getText().toString().trim())
                                .putInt(Prefs.REPORT_INTERVAL, picked)
                                .apply();

                        ReportUploader.schedule(this);
                        applyLocateMode();
                        ReportUploader.uploadAsync(this, true, (ok, msg) -> {
                            paint.run();
                            if (ok) {
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // 失败要能看清原因，别用一闪而过的 Toast
                            new AlertDialog.Builder(this)
                                    .setTitle("上报没成功")
                                    .setMessage(msg + "\n\n最常见的原因：\n"
                                            + "· 地址少了 /api/report 或 ?k=令牌\n"
                                            + "· 手机当前网络没有 IPv6（域名只有 AAAA 记录）\n"
                                            + "· 反代用了自签证书\n"
                                            + "· 开关没勾上")
                                    .setPositiveButton("知道了", null)
                                    .show();
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        parent.addView(row);
        return row;
    }

    /** 检查更新 */
    private void addUpdateRows(LinearLayout parent) {
        final TextView row = rowText();
        final Runnable paint = () -> {
            String last = prefs.getString("update_last", "");
            row.setText("检查更新：" + (last == null || last.isEmpty()
                    ? (UpdateChecker.configured(this) ? "还没检查过" : "未配置更新地址")
                    : last.replace('\n', ' ')));
        };
        paint.run();
        row.setOnClickListener(v -> {
            LinearLayout box = verticalBox();
            box.addView(hintText("更新源（一个静态 JSON 文件）"));
            final EditText url = new EditText(this);
            url.setText(prefs.getString("update_url", ""));
            url.setHint("https://example.com/elder-update.json");
            url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            box.addView(url);
            box.addView(hintText("JSON 格式：{\"versionCode\":4,\"versionName\":\"2.1\","
                    + "\"apk\":\"https://…/app.apk\",\"notes\":\"改了什么\"}\n"
                    + "只做「发现新版 + 给下载链接」，不会静默安装。"));

            new AlertDialog.Builder(this)
                    .setTitle("检查更新")
                    .setView(box)
                    .setPositiveButton("保存并检查", (d, w) -> {
                        prefs.edit().putString("update_url", url.getText().toString().trim()).apply();
                        UpdateChecker.schedule(this);
                        UpdateChecker.checkAsync(this, (ok, msg, apk) -> {
                            paint.run();
                            new AlertDialog.Builder(this)
                                    .setTitle(ok ? "检查结果" : "检查失败")
                                    .setMessage(msg)
                                    .setPositiveButton("好", null)
                                    .setNeutralButton(apk == null || apk.isEmpty() ? "关闭" : "打开下载页",
                                            (d2, w2) -> {
                                                if (apk != null && !apk.isEmpty()) {
                                                    try {
                                                        startActivity(new Intent(Intent.ACTION_VIEW,
                                                                android.net.Uri.parse(apk)));
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                            })
                                    .show();
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        parent.addView(row);
    }

    /** 配置导出 / 导入 */
    private void addProfileRows(LinearLayout parent) {
        Button export = new Button(this);
        export.setText("导出配置（JSON，换机用）");
        export.setOnClickListener(v -> {
            String path = ProfileStore.exportToFile(this);
            if (path == null) {
                Toast.makeText(this, "导出失败（可能没有存储权限）", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("已导出")
                    .setMessage(path + "\n\n" + ProfileStore.summary(this)
                            + "\n\n⚠ 文件里有定位密钥和 DDNS Token，只发给自己人。")
                    .setPositiveButton("好", null)
                    .show();
        });
        parent.addView(export, matchWrap());

        Button importBtn = new Button(this);
        importBtn.setText("导入配置（选一个 JSON）");
        importBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(i, REQ_IMPORT_PROFILE);
            } catch (Exception e) {
                Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
            }
        });
        parent.addView(importBtn, matchWrap());

        parent.addView(hintText("换手机、或者换签名必须卸载重装时，先导出，再在新机上导入，"
                + "不用重新点一遍二十多个开关。"));

        // ---- 配置快照：本机上的"后悔药"，也是远程恢复的来源 ----
        final TextView snapRow = rowCard();
        final Runnable paintSnap = () -> snapRow.setText(
                "配置快照\n" + SnapshotStore.info(this)
                        + "\n\n首次基线在装好第一次启动时自动存，之后不再改；"
                        + "家人端/中继可以远程让它恢复到基线。");
        paintSnap.run();
        parent.addView(snapRow);

        Button saveSnap = new Button(this);
        saveSnap.setText("把当前配置存为快照");
        saveSnap.setOnClickListener(v -> {
            boolean ok = SnapshotStore.save(this, false);
            Toast.makeText(this, ok ? "已保存当前配置" : "保存失败", Toast.LENGTH_SHORT).show();
            if (ok) paintSnap.run();
        });
        parent.addView(saveSnap, matchWrap());

        Button restoreFirst = new Button(this);
        restoreFirst.setText("恢复到首次基线");
        restoreFirst.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("恢复到首次基线")
                .setMessage("会把桌面卡片、锁定开关、报时、上报地址等配置全部恢复成"
                        + "第一次装好时的样子。\n\n当前这些设置会被覆盖（联系人卡片和已收到的文件不受影响）。"
                        + "\n\n确定恢复？")
                .setPositiveButton("恢复", (d, w) -> {
                    int n = SnapshotStore.restore(this, true);
                    if (n <= 0) {
                        Toast.makeText(this, "还没有首次基线，先跑一次初始设置或手动存一份",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "已恢复 " + n + " 项配置，正在刷新…", Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show());
        parent.addView(restoreFirst, matchWrap());
    }

    private void importProfileFrom(android.net.Uri uri) {
        try {
            String path = copyToCache(uri);
            String text = path == null ? null : ProfileStore.readFile(path);
            int n = ProfileStore.importJson(this, text);
            if (n <= 0) {
                Toast.makeText(this, "这个文件不是守护的配置档案", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "已导入 " + n + " 项配置，正在刷新…", Toast.LENGTH_LONG).show();
            recreate();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String copyToCache(android.net.Uri uri) {
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            java.io.File f = new java.io.File(getCacheDir(), "import-profile.json");
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 长文本弹窗（可选中复制） */
    private void showTextDialog(String title, String text) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制全文", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(title, text));
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void addRecoverRows(LinearLayout parent, boolean isDo) {
        // 家人接手一台已经装过的手机、或者想重新调整桌面时，从这里重跑 4 步向导
        Button rerun = new Button(this);
        rerun.setText("重新走一遍初始设置（4 步）");
        rerun.setOnClickListener(v -> startActivity(new Intent(this, FirstSetupActivity.class)));
        parent.addView(rerun, matchWrap());

        Button unlock = new Button(this);
        unlock.setText("立即解除屏幕固定");
        unlock.setOnClickListener(v -> {
            try {
                stopLockTask();
            } catch (Exception e) {
                Log.w(TAG, "stopLockTask 失败", e);
            }
            prefs.edit().putBoolean(Prefs.LOCK_TASK, false).apply();
            Toast.makeText(this, "已解除，回到设置页顶部重新打开即可恢复", Toast.LENGTH_SHORT).show();
        });
        parent.addView(unlock, matchWrap());

        parent.addView(hintText("紧急恢复（手机被锁死时）：\n"
                + "1. USB 连电脑，开 USB 调试\n"
                + "2. 电脑执行 tools\\provision.ps1 -Unlock\n"
                + "3. 限制立即解除，无需恢复出厂"));
    }

    // ============ 通用小工具 ============

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private TextView rowText() {
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(Ui.textPrimary(this));
        tv.setPadding(dp(4), dp(10), 0, dp(10));
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        return tv;
    }

    /** 灰色圆角卡片：「定位和传输」里三块主配置用它，比一行文字更像"能点的地方" */
    private TextView rowCard() {
        TextView tv = rowText();
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.surface(this));
        bg.setCornerRadius(dp(12));
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        tv.setLineSpacing(0, 1.15f);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList ripple = android.content.res.ColorStateList
                    .valueOf(0x22000000);
            tv.setBackground(new android.graphics.drawable.RippleDrawable(ripple, bg, null));
        } else {
            tv.setBackground(bg);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView hintText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Ui.textSecondary(this));
        tv.setPadding(dp(8), dp(2), 0, dp(10));
        return tv;
    }

    /**
     * 可折叠分组：标题点一下就展开/收起。
     * 折叠的组只在标题上留一个 ▸，展开变 ▾ —— 一屏最多看到 6 行，不至于吓人。
     */
    private LinearLayout addGroup(LinearLayout parent, String title, boolean expanded) {
        final TextView header = new TextView(this);
        header.setText((expanded ? "▾ " : "▸ ") + title);
        header.setTextSize(17);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Ui.textPrimary(this));
        header.setPadding(dp(4), dp(16), dp(4), dp(10));
        header.setBackgroundResource(android.R.drawable.list_selector_background);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        body.setPadding(dp(8), 0, 0, dp(4));

        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            header.setText((show ? "▾ " : "▸ ") + title);
        });

        parent.addView(header);
        parent.addView(body);
        return body;
    }

    private CheckBox addSwitch(LinearLayout parent, String text, String hint) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(16);
        cb.setTextColor(Ui.textPrimary(this));
        cb.setPadding(0, dp(10), 0, dp(2));
        parent.addView(cb);
        if (hint != null && !hint.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(hint);
            tv.setTextSize(12);
            tv.setTextColor(Ui.textSecondary(this));
            tv.setPadding(dp(28), 0, 0, dp(8));
            parent.addView(tv);
        }
        return cb;
    }

    private void addTimePickerRow(LinearLayout parent, final String label,
                                  final String key, final String defVal) {
        final TextView row = new TextView(this);
        row.setTextSize(15);
        row.setTextColor(Ui.textPrimary(this));
        row.setPadding(dp(28), dp(2), 0, dp(10));
        row.setText(label + "：" + formatHours(prefs.getString(key, defVal)));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(v -> {
            final String[] items = new String[24];
            for (int i = 0; i < 24; i++) items[i] = i + " 点";
            final boolean[] checked = new boolean[24];
            for (String s : prefs.getString(key, defVal).split(",")) {
                try {
                    int h = Integer.parseInt(s.trim());
                    if (h >= 0 && h < 24) checked[h] = true;
                } catch (Exception ignored) {
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("保存", (d, w) -> {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 24; i++) {
                            if (checked[i]) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(i);
                            }
                        }
                        prefs.edit().putString(key, sb.toString()).apply();
                        row.setText(label + "：" + formatHours(sb.toString()));
                        ElderApp.scheduleAllTimers(this);
                        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        parent.addView(row);
    }

    private String formatHours(String csv) {
        if (csv == null || csv.trim().isEmpty()) return "未设置";
        StringBuilder sb = new StringBuilder();
        for (String s : csv.split(",")) {
            if (s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(s.trim()).append(" 点");
        }
        return sb.length() == 0 ? "未设置" : sb.toString();
    }

    private String callerModeLabel() {
        String mode = prefs.getString(Prefs.CALLER_ANNOUNCE, "name");
        if ("number".equals(mode)) return "念电话号码";
        if ("off".equals(mode)) return "不播报（原铃声）";
        return "念备注名";
    }

    private String formatInterval(int minutes) {
        if (minutes < 60) return "每 " + minutes + " 分钟";
        return "每 " + (minutes / 60) + " 小时";
    }

    private int countCsv(String csv) {
        return csvToSet(csv).size();
    }

    private java.util.Set<String> csvToSet(String csv) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (csv == null) return set;
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) set.add(s.trim());
        }
        return set;
    }

    private String defaultNotifWhitelist() {
        return "com.tencent.mm,com.tencent.mobileqq,com.android.mms,"
                + "com.google.android.apps.messaging,com.android.dialer,"
                + "com.google.android.dialer,com.android.phone,"
                + "com.eg.android.AlipayGphone,com.elder.launcher";
    }

    private boolean isAccessibilityOn() {
        try {
            String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ============ 对话框 ============

    /** 需要设备托管时的说明（旧文案里"恢复出厂"这句容易吓人，这里讲清楚前提） */
    private void showNeedDoDialog() {
        new AlertDialog.Builder(this)
                .setTitle("为什么这两项控制不了")
                .setMessage("「禁止安装 / 禁止卸载」是系统级策略，普通应用没这个权力，"
                        + "必须让老人守护成为这台手机的「设备所有者（Device Owner）」。\n\n"
                        + "【怎么激活】只能用电脑，一次即可：\n"
                        + "1. 数据线连电脑，打开 USB 调试\n"
                        + "2. 在项目目录执行 tools\\provision.ps1 -Owner\n"
                        + "3. 回到本页，这两项自动可用\n\n"
                        + "【前提】手机没登录华为/谷歌账号、没设锁屏密码。已登录的机器需要恢复出厂后再激活。\n\n"
                        + "【注意】Device Owner 激活后无法撤销，撤销的唯一方式是恢复出厂——"
                        + "所以脚本会先检查条件，不满足会直接拒绝，不会装到一半卡住。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制命令", (d, w) -> copyText(
                        "powershell -ExecutionPolicy Bypass -File tools\\provision.ps1 -Owner", "命令已复制"))
                .show();
    }

    private void copyText(String text, String toast) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("elder", text));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    /** 天气地点：可以跟随定位，也可以手动指定城市 */
    private void showPlaceDialog(final Runnable onChanged) {
        LinearLayout box = verticalBox();
        final EditText input = new EditText(this);
        input.setHint("城市名，例如：杭州");
        String current = WeatherHelper.placeName(this);
        if (current != null && !current.isEmpty()) input.setText(current);
        box.addView(input);

        Button useGps = new Button(this);
        useGps.setText("改回跟随手机定位");
        box.addView(useGps);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("天气地点（播报用哪里的天气）")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        useGps.setOnClickListener(v -> {
            WeatherHelper.clearPlace(this);
            onChanged.run();
            Toast.makeText(this, "已改为跟随定位", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) {
                        Toast.makeText(this, "请输入城市名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "正在查找…", Toast.LENGTH_SHORT).show();
                    WeatherHelper.searchPlaceAsync(this, q, (label, lat, lon, error) -> {
                        if (error != null) {
                            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        WeatherHelper.setPlace(this, label, lat, lon);
                        onChanged.run();
                        Toast.makeText(this, "已设为：" + label, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
                }));
        dialog.show();
    }

    /**
     * 定位服务：配置二维码 + 密钥 + 本机地址
     *
     * 家人不用手敲一长串 IPv6 —— 家人端 App 扫码即自动填好地址和密钥。
     */
    private void showLocateDialog() {
        String key = prefs.getString(Prefs.LOCATE_KEY, "");
        if (key == null || key.isEmpty()) {
            key = randomKey();
            prefs.edit().putString(Prefs.LOCATE_KEY, key).apply();
        }
        final boolean on = LocateServer.RUNNING;

        java.util.List<String> addrs = LocateServer.localAddresses();
        String host = preferredHost();
        int port = LocateServer.PORT;

        LinearLayout box = verticalBox();

        TextView state = new TextView(this);
        state.setText(on ? "状态：已开启，家人现在可以查" : "状态：未开启");
        state.setTextSize(15);
        state.setTextColor(on ? Ui.color(this, R.color.elder_ok) : Ui.color(this, R.color.elder_warning));
        box.addView(state);

        ImageView qr = new ImageView(this);
        android.graphics.Bitmap bmp = host == null ? null
                : QrUtil.encode(QrUtil.locateConfigUri(host, port, key), dp(240));
        if (bmp != null) {
            qr.setImageBitmap(bmp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(240));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(10);
            qr.setLayoutParams(lp);
            box.addView(qr);
            TextView qrHint = hintText("用「家人端」App 扫这个码，地址和密钥自动填好");
            qrHint.setGravity(Gravity.CENTER_HORIZONTAL);
            qrHint.setPadding(0, dp(4), 0, dp(8));
            box.addView(qrHint);
        } else {
            box.addView(hintText("没拿到本机地址，确认手机已联网后再看；也可以手填密钥给家人"));
        }

        TextView detail = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append("密钥：").append(key).append("\n");
        sb.append("端口：").append(port).append("\n");
        sb.append("本机地址：\n");
        if (addrs.isEmpty()) {
            sb.append("  （没拿到，确认已联网）");
        } else {
            for (String a : addrs) sb.append("  ").append(a).append("\n");
        }
        sb.append("\n家人手动填的格式：\nhttp://[IPv6]:").append(port).append("/loc?key=").append(key);
        detail.setText(sb.toString());
        detail.setTextSize(12);
        detail.setTextColor(Ui.textSecondary(this));
        detail.setPadding(0, dp(6), 0, 0);
        detail.setTextIsSelectable(true);
        box.addView(detail);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("定位服务")
                .setView(sv)
                .setPositiveButton(on ? "停止服务" : "开启服务", (d, w) -> {
                    if (on) {
                        LocateServer.stop(this);
                        prefs.edit().putBoolean(Prefs.LOCATE_SERVER, false).apply();
                    } else {
                        prefs.edit().putBoolean(Prefs.LOCATE_SERVER, true).apply();
                        LocateServer.start(this);
                    }
                    new android.os.Handler().postDelayed(this::showLocateDialog, 700);
                })
                .setNeutralButton("改密钥", (d, w) -> showKeyDialog())
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 密钥可以手动指定（家人想跟自己设备对得上时用），也可以随机生成 */
    private void showKeyDialog() {
        LinearLayout box = verticalBox();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("4-32 位字母数字，例如 abcd1234");
        String cur = prefs.getString(Prefs.LOCATE_KEY, "");
        if (cur != null) input.setText(cur);
        box.addView(input);
        box.addView(hintText("改完记得让家人端重新扫一次二维码。"));

        new AlertDialog.Builder(this)
                .setTitle("设置查询密钥")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.matches("[A-Za-z0-9]{4,32}")) {
                        Toast.makeText(this, "只能字母数字，4-32 位", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.edit().putString(Prefs.LOCATE_KEY, v).apply();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    if (LocateServer.RUNNING) {
                        LocateServer.stop(this);
                        LocateServer.start(this);
                    }
                    showLocateDialog();
                })
                .setNeutralButton("随机生成", (d, w) -> {
                    prefs.edit().putString(Prefs.LOCATE_KEY, randomKey()).apply();
                    if (LocateServer.RUNNING) {
                        LocateServer.stop(this);
                        LocateServer.start(this);
                    }
                    showLocateDialog();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 网页传包：二维码直接带上校验码，家人扫码即进 */
    private void showTransferDialog() {
        if (FileTransferServer.RUNNING) {
            showTransferInfo();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("网页传包")
                .setMessage("开启后：\n"
                        + "• 只在当前 WiFi 局域网内可访问\n"
                        + "• 10 分钟后自动关闭\n"
                        + "• 上传需要 6 位校验码（二维码里已带上）\n\n"
                        + "现在开启？")
                .setPositiveButton("开启", (d, w) -> {
                    prefs.edit().putBoolean(Prefs.TRANSFER_ENABLED, true).apply();
                    FileTransferServer.start(this);
                    new android.os.Handler().postDelayed(this::showTransferInfo, 1000);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTransferInfo() {
        String ip = FileTransferServer.lanIp();
        String code = FileTransferServer.sessionCode;
        if (ip == null || code == null || code.isEmpty()) {
            Toast.makeText(this, "没连 WiFi 或服务还没启动", Toast.LENGTH_LONG).show();
            return;
        }
        final String url = "http://" + ip + ":" + FileTransferServer.PORT + "/?code=" + code;

        LinearLayout box = verticalBox();
        ImageView qr = new ImageView(this);
        android.graphics.Bitmap bmp = QrUtil.encode(url, dp(240));
        if (bmp != null) {
            qr.setImageBitmap(bmp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(240));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            qr.setLayoutParams(lp);
            box.addView(qr);
        }
        TextView msg = new TextView(this);
        msg.setText("手机扫上面的码，浏览器会带着校验码打开，不用手输。\n\n"
                + "地址：" + url + "\n校验码：" + code + "\n\n10 分钟后自动关闭。");
        msg.setTextSize(13);
        msg.setTextColor(Ui.textSecondary(this));
        msg.setPadding(0, dp(10), 0, 0);
        msg.setTextIsSelectable(true);
        box.addView(msg);

        new AlertDialog.Builder(this)
                .setTitle("网页传包已开启")
                .setView(box)
                .setPositiveButton("停止", (d, w) -> {
                    FileTransferServer.stop(this);
                    Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("复制地址", (d, w) -> copyText(url, "已复制"))
                .setNegativeButton("知道了", null)
                .show();
    }

    private int inboxCount() {
        java.io.File[] files = FileTransferServer.inboxDir(this).listFiles();
        return files == null ? 0 : files.length;
    }

    private void showInbox() {
        final java.io.File[] files = FileTransferServer.inboxDir(this).listFiles();
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("还没有收到文件")
                    .setMessage("在「网页传包」里上传，文件会存到手机的「下载 / 老人守护」目录。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        final String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName() + "（" + files[i].length() / 1024 + " KB）";
        }
        new AlertDialog.Builder(this)
                .setTitle("点一项打开 / 安装")
                .setItems(names, (d, which) -> openFile(files[which]))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openFile(java.io.File f) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            String mime = f.getName().toLowerCase().endsWith(".apk")
                    ? "application/vnd.android.package-archive" : "*/*";
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打不开：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout verticalBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        return box;
    }

    /** 优先给公网 IPv6（家人跨网也能用），没有就给内网 IPv4 */
    private String pickHost(java.util.List<String> addrs) {
        String v6 = null, v4 = null;
        for (String a : addrs) {
            if (a.startsWith("IPv6 ") && v6 == null) v6 = a.substring(5).trim();
            if (a.startsWith("IPv4 ") && v4 == null) v4 = a.substring(5).trim();
        }
        return v6 != null ? v6 : v4;
    }

    private String randomKey() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    // ============ 家人号码 ============

    private void showFamilyNumberDialog() {
        final EditText input = new EditText(this);
        input.setText(prefs.getString(LocConfig.KEY_FAMILY, ""));
        input.setHint("例如 13800138000，多个用逗号分隔");
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("家人手机号（只有这些号码能查询位置）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.edit().putString(LocConfig.KEY_FAMILY,
                            LocConfig.cleanCsv(input.getText().toString())).apply();
                    updateFamilyLabel();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("从通讯录选", (d, w) -> {
                    pendingFamilyEdit = input.getText().toString();
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_PICK,
                                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                                REQ_FAMILY_CONTACT);
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开通讯录：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN) {
            restoreLockTaskList();
            if (resultCode == RESULT_OK && scanUri != null) handleScannedReportUrl();
            return;
        }
        if (requestCode == REQ_SCAN_ALBUM) {
            restoreLockTaskList();
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                scanUri = data.getData();
                handleScannedReportUrl();
            }
            return;
        }
        if (requestCode == REQ_IMPORT_PROFILE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importProfileFrom(data.getData());
            return;
        }
        if (requestCode != REQ_FAMILY_CONTACT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        String number = readPickedNumber(data.getData());
        if (number == null || number.isEmpty()) {
            Toast.makeText(this, "没有读到号码", Toast.LENGTH_SHORT).show();
            return;
        }
        String merged = LocConfig.cleanCsv(
                (pendingFamilyEdit == null ? "" : pendingFamilyEdit) + "," + number);
        prefs.edit().putString(LocConfig.KEY_FAMILY, merged).apply();
        pendingFamilyEdit = null;
        updateFamilyLabel();
        Toast.makeText(this, "已添加 " + LocConfig.mask(number), Toast.LENGTH_SHORT).show();
    }

    private String readPickedNumber(android.net.Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                return LocConfig.normalize(c.getString(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "读取联系人号码失败", e);
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

    private void updateFamilyLabel() {
        if (btnFamily == null) return;
        btnFamily.setText("家人手机号：" + LocConfig.familySummary(this));
    }

    private void testLocate() {
        java.util.List<String> family = LocConfig.familyNumbers(this);
        if (family.isEmpty()) {
            Toast.makeText(this, "请先添加家人手机号", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在定位，几秒后会收到短信", Toast.LENGTH_LONG).show();
        LocationFetchService.start(this, family.get(0), 1, 60, "test");
    }

    // ============ 列表选择 ============

    private void showPackagePicker(final String key, final String defVal, String title,
                                   final LabelUpdater onSaved, final Button target) {
        final java.util.List<String[]> apps = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.Set<String> chosen = csvToSet(prefs.getString(key, defVal));

        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            CharSequence label = pm.getApplicationLabel(ai);
            apps.add(new String[]{ai.packageName, label == null ? ai.packageName : label.toString()});
        }
        for (String pkg : chosen) {
            boolean exists = false;
            for (String[] a : apps) {
                if (a[0].equals(pkg)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) apps.add(new String[]{pkg, friendlyName(pm, pkg)});
        }
        java.util.Collections.sort(apps, (a, b) -> a[1].compareTo(b[1]));

        final String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[1];
            checked[i] = chosen.contains(apps.get(i)[0]);
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(apps.get(i)[0]);
                        }
                    }
                    prefs.edit().putString(key, sb.toString()).apply();
                    onSaved.update(target);
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface LabelUpdater {
        void update(Button b);
    }

    private String friendlyName(android.content.pm.PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        switch (pkg) {
            case "com.tencent.mm": return "微信";
            case "com.tencent.mobileqq": return "QQ";
            case "com.android.mms": return "短信";
            case "com.google.android.apps.messaging": return "短信（谷歌）";
            case "com.android.dialer": return "电话";
            case "com.google.android.dialer": return "电话（谷歌）";
            case "com.android.phone": return "通话服务";
            case "com.android.contacts":
            case "com.huawei.contacts":
            case "com.hihonor.contacts": return "联系人";
            case "com.eg.android.AlipayGphone": return "支付宝";
            default: return pkg;
        }
    }

    private void applyRestriction(String key, boolean enable) {
        android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(this);
        if (dpm == null || !ElderDeviceAdminReceiver.isDeviceOwner(this)) {
            Toast.makeText(this, "未激活 Device Owner，无法修改", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(this);
        try {
            if (enable) {
                dpm.addUserRestriction(admin, key);
            } else {
                dpm.clearUserRestriction(admin, key);
            }
        } catch (Exception e) {
            Log.e(TAG, "修改限制失败: " + key, e);
            Toast.makeText(this, "修改失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        // 扫码中途被打断（相机崩溃、用户强杀）时 onActivityResult 不会来，
        // 这里兜一下，避免相机一直被留在锁定任务白名单里
        if (scanPkg != null) restoreLockTaskList();
        super.onResume();
        updateInboxLabel();
    }
}
