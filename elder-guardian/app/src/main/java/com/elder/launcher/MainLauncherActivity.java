package com.elder.launcher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主 Launcher 界面 —— 老人守护的核心展示层（手机版）
 *
 * 职责：
 * 1. 2 列大图标网格，内容由 Prefs.tilesCsv + 联系人卡片 + 固定入口组成
 * 2. Device Owner 激活时进入锁定任务模式（老人退不出去）
 * 3. 隐藏入口：标题连点 8 次进家人设置；长按标题 5 秒临时解除屏幕固定
 * 4. 每次 onResume 重新下发的设备限制是幂等的，升级安装后也能自动生效
 *
 * 已下线：电视模式（横屏 3 列/随机背景/遥控器）、云盘、电视直播、内置抖音安装。
 */
@SuppressLint({"WrongConstant", "NewApi"})
public class MainLauncherActivity extends AppCompatActivity {

    private static final String TAG = "ElderLauncher";
    private static final int SPAN_COUNT = 2;
    private static final int SECRET_CLICK_LIMIT = 8;
    private static final long SECRET_CLICK_INTERVAL = 1200L;

    private RecyclerView grid;
    private TileAdapter adapter;
    private final List<TileItem> tiles = new ArrayList<>();
    private SharedPreferences prefs;
    private TextView weatherChip;
    private int secretClickCount = 0;
    /** 顶部时间/日期/农历，每 20 秒刷一次（分钟级就够，没必要每秒） */
    private TextView clockView;
    private TextView dateView;
    private TextView lunarView;
    private TextView weatherDesc;
    /** 电量竖条 + 百分比 */
    private BatteryBarView batteryBar;
    private TextView batteryText;
    /** 信号 4 格 + 运营商名 */
    private SignalBarsView signalBars;
    private TextView signalText;
    private BroadcastReceiver batteryReceiver;
    private TelephonyManager telephony;
    private PhoneStateListener signalListener;
    private int batteryPct = -1;
    private boolean charging;
    private int signalLevel = -1;
    private String carrierName = "";
    /** 内网 WIFI 模式：不插卡的看视频机，信号区改成 WIFI 区 */
    private boolean intranet;
    /** 内网模式下「网络不对」的红条（正常时隐藏） */
    private TextView netBanner;
    private Runnable netTick;
    /** 单击时间后的延迟播报（连点时会取消，避免一直念） */
    private Runnable clockSpeak;
    /** 连点达标后延迟打开设置：多出来的几下先落在本页，不会漏到下面华为桌面去点开别的应用 */
    private Runnable openSettings;
    /** 已经凑够 8 下，等手指停下来再跳 */
    private boolean pendingOpenSettings;
    private final android.os.Handler clockHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable clockTick;
    private long lastClickTime = 0;
    /** 直接启动（尚未解锁）时为 false，此时不能碰 CE 存储 */
    private boolean ready = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 本 Activity 声明了 directBootAware：华为的默认桌面候选要求它，
        // 代价是开机未解锁时也可能被拉起，这时读 shared_prefs 会抛异常，
        // 所以先做一个极简兜底页，解锁后系统会重新创建本界面。
        android.os.UserManager um =
                (android.os.UserManager) getSystemService(android.content.Context.USER_SERVICE);
        if (um != null && !um.isUserUnlocked()) {
            TextView tip = new TextView(this);
            tip.setText("请先解锁手机");
            tip.setTextSize(24);
            tip.setGravity(android.view.Gravity.CENTER);
            setContentView(tip);
            return;
        }
        ready = true;

        // 远程能力一次性初始化：DDNS 域名同步 + 定位服务默认打开，
        // 只做一次（用 remote_bootstrapped 标记），之后完全由家人设置页控制。
        if (!Prefs.get(this).getBoolean("remote_bootstrapped", false)) {
            Prefs.putBool(this, "ddns_enabled", true);
            Prefs.putBool(this, Prefs.LOCATE_SERVER, true);
            Prefs.get(this).edit().putBoolean("remote_bootstrapped", true).apply();
        }

        // 不隐藏状态栏：锁定任务模式下状态栏由 Device Owner 直接禁用，
        // 没锁定时老人还能看到时间、电量和信号，比一片空白更安心。
        setContentView(R.layout.activity_main_launcher);

        prefs = Prefs.get(this);
        intranet = IntranetMode.isOn(this);

        // 第一次进到桌面 = 初始设置已经跑完，把这一刻的配置存成基线，
        // 以后远程「恢复布局」就是回到这里（没有就存，有就不动）
        SnapshotStore.saveFirstIfAbsent(this);

        grid = findViewById(R.id.launcher_grid);
        grid.setLayoutManager(new GridLayoutManager(this, SPAN_COUNT));
        adapter = new TileAdapter(tiles, this::onTileClick);
        grid.setAdapter(adapter);

        loadTiles();

        // 幂等下发设备限制 + Device Owner 静默授权
        ElderDeviceAdminReceiver.enableAllRestrictions(this);
        grantRuntimePermissions();

        // 家人在设置里开启锁定后才锁定，避免一装就锁死没有退路
        if (ElderDeviceAdminReceiver.isDeviceOwner(this) && isLockTaskEnabled()) {
            lockNow();
        }

        setupStatusViews();
        setupNetworkBanner();
        loadWeatherChip();

        // 隐藏入口绑在「时间」整块上（时间 / 日期 / 农历任何一处都算，触摸区 137×101dp）。
        //
        // 长按解锁已经去掉：老人想听时间经常是"按住不放"，和长按解锁撞在一起，
        // 一按久就弹出"解除屏幕固定"，误触概率太高。解锁改走两条路：
        //   1) 连点时间 8 次进家人设置 → 设置页顶部的「解除锁定」
        //   2) 电脑端 tools\provision.ps1 -Unlock（手机被锁死时）
        View timeBox = findViewById(R.id.launcher_time_box);
        if (timeBox != null) {
            timeBox.setOnClickListener(v -> onClockTap());
        }
        disableAutoRotate();
        Ui.applyEdgeToEdge(this);
    }

    private void lockNow() {
        try {
            startLockTask();
            Log.i(TAG, "进入锁定任务模式");
        } catch (Exception e) {
            Log.e(TAG, "startLockTask 失败", e);
        }
    }

    private boolean isLockTaskEnabled() {
        return prefs != null && prefs.getBoolean(Prefs.LOCK_TASK, false);
    }

    /**
     * 时间区点击：单击 = 念时间；1.2 秒内连点 8 次 = 进设置页。
     *
     * 播报故意延迟 420ms —— 连点时先把上一击的播报取消，
     * 否则敲 8 下会听到 8 个"现在是一点"叠在一起。
     */
    private void onClockTap() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < SECRET_CLICK_INTERVAL) {
            secretClickCount++;
        } else {
            secretClickCount = 1;
        }
        lastClickTime = now;

        if (clockHandler != null && clockSpeak != null) {
            clockHandler.removeCallbacks(clockSpeak);
        }

        if (secretClickCount >= SECRET_CLICK_LIMIT) {
            secretClickCount = 0;
            pendingOpenSettings = true;
        }

        // 凑够 8 下后不立刻跳，等手指真的停下来（450ms 内没有新点击）再跳。
        // 之前实测：直接 startActivity 会让后面多敲的一两下漏到下层华为桌面，误开别的应用。
        if (pendingOpenSettings) {
            if (clockHandler != null && openSettings != null) {
                clockHandler.removeCallbacks(openSettings);
                clockHandler.postDelayed(openSettings, 450);
            } else {
                startActivity(new Intent(this, SettingsActivity.class));
            }
            return;
        }
        if (secretClickCount >= 5) {
            // 给家人一点反馈，避免以为没点上
            Toast.makeText(this, "再点 " + (SECRET_CLICK_LIMIT - secretClickCount) + " 次进入设置",
                    Toast.LENGTH_SHORT).show();
        }
        if (clockHandler != null && clockSpeak != null) {
            clockHandler.postDelayed(clockSpeak, 420);
        }
    }

    /** 念时间：现在是 19 点 5 分，10 月 25 日 星期日，农历九月十六 */
    private void speakClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int h = c.get(java.util.Calendar.HOUR_OF_DAY);
        int m = c.get(java.util.Calendar.MINUTE);
        String time = "现在是 " + h + " 点" + (m == 0 ? "整" : " " + m + " 分");
        String text = time + "，" + (c.get(java.util.Calendar.MONTH) + 1) + " 月"
                + c.get(java.util.Calendar.DAY_OF_MONTH) + " 日 " + Lunar.weekdayText(c)
                + "，" + Lunar.lunarLine(c).replace(" · ", "，");
        Log.i(TAG, "播报时间：" + text);
        TtsHelper.speak(this, text);
    }

    private void loadWeatherChip() {
        weatherChip = findViewById(R.id.launcher_weather);
        weatherDesc = findViewById(R.id.launcher_weather_desc);
        clockView = findViewById(R.id.launcher_clock);
        dateView = findViewById(R.id.launcher_date);
        lunarView = findViewById(R.id.launcher_lunar);
        clockSpeak = this::speakClock;
        openSettings = () -> {
            pendingOpenSettings = false;
            startActivity(new Intent(this, SettingsActivity.class));
        };
        clockTick = () -> {
            updateClock();
            clockHandler.postDelayed(clockTick, 20000);
        };
        updateClock();

        // 天气：先显示上次缓存，再异步刷新。文案读不成也留空，不留「--」
        if (weatherChip != null) {
            weatherChip.setText(prefs.getString(Prefs.LAST_WEATHER_SHORT, ""));
            if (weatherDesc != null) {
                weatherDesc.setText(prefs.getString(Prefs.LAST_WEATHER_DESC, ""));
            }
            // 点击绑在「天气整块」上：点温度或点"晴朗"都念天气
            View weatherBox = findViewById(R.id.launcher_weather_box);
            View.OnClickListener speakWeather = v -> {
                Log.i(TAG, "点天气，开始播报");
                WeatherHelper.fetchAsync(this, text -> TtsHelper.speak(this,
                        text == null ? "天气暂时查不到" : "现在" + text));
            };
            if (weatherBox != null) weatherBox.setOnClickListener(speakWeather);
            else weatherChip.setOnClickListener(speakWeather);
            WeatherHelper.fetchHeaderAsync(this, (main, desc) -> {
                if (main != null && weatherChip != null) weatherChip.setText(main);
                if (desc != null && weatherDesc != null) weatherDesc.setText(desc);
            });
        }
    }

    /** Device Owner 下静默授予运行时权限，老人点卡片即可直接拨号 */
    private void grantRuntimePermissions() {
        if (!ElderDeviceAdminReceiver.isDeviceOwner(this)) return;
        try {
            android.app.admin.DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(this);
            android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(this);
            String[] perms = {
                    android.Manifest.permission.CALL_PHONE,
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG,
                    // 短信只读页
                    android.Manifest.permission.READ_SMS,
                    // 网页传包存到公共下载目录
                    "android.permission.WRITE_EXTERNAL_STORAGE"
            };
            for (String p : perms) {
                dpm.setPermissionGrantState(admin, getPackageName(), p,
                        android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            }
        } catch (Exception e) {
            Log.w(TAG, "授予运行时权限失败", e);
        }
    }

    /** 关闭系统自动旋转（需要「修改系统设置」权限，未授权则静默跳过） */
    private void disableAutoRotate() {
        try {
            if (android.provider.Settings.System.canWrite(this)) {
                android.provider.Settings.System.putInt(getContentResolver(),
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
            }
        } catch (Exception ignored) {
        }
    }

    /** 顶部：时间 / 星期 / 农历·节气（离线算，不联网） */
    private void updateClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        if (clockView != null) {
            clockView.setText(String.format(java.util.Locale.CHINA, "%02d:%02d",
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));
        }
        if (dateView != null) {
            dateView.setText((c.get(java.util.Calendar.MONTH) + 1) + "月"
                    + c.get(java.util.Calendar.DAY_OF_MONTH) + "日 " + Lunar.weekdayText(c));
        }
        if (lunarView != null) {
            lunarView.setText(Lunar.lunarLine(c));
        }
    }

    @Override
    public void onPause() {
        if (clockTick != null) clockHandler.removeCallbacks(clockTick);
        if (netTick != null) {
            clockHandler.removeCallbacks(netTick);
            netTick = null;
        }
        unregisterBattery();
        stopSignal();
        super.onPause();
    }

    // ==================== 电量 / 信号 ====================

    /**
     * 电量与信号：老人最常问的两件事（"还有多少电""怎么没信号"）。
     *
     * 电量放正中竖条——正中间是视线的落点，比右上角小图标显眼得多；
     * 信号放天气下面，因为"能不能打出去"和"能不能收到位置"都依赖它。
     * 两个都能点，点一下就用语音念出来，老人不用眯着眼看。
     */
    private void setupStatusViews() {
        batteryBar = findViewById(R.id.launcher_battery_bar);
        batteryText = findViewById(R.id.launcher_battery_text);
        signalBars = findViewById(R.id.launcher_signal_bars);
        signalText = findViewById(R.id.launcher_signal_text);

        View batteryBox = findViewById(R.id.launcher_battery_box);
        if (batteryBox != null) batteryBox.setOnClickListener(v -> speakBattery());
        View signalRow = findViewById(R.id.launcher_signal_row);
        if (signalRow != null) signalRow.setOnClickListener(v -> speakNetwork());

        // 真正的注册放到 onResume —— 以前只在 onCreate 注册一次，
        // 而 onPause 会注销，导致点开任何应用再回到桌面，电量和信号就永远停在那一刻。
    }

    /**
     * 注册电量 / 信号（或 WIFI）监听。
     * 必须每次 onResume 都调：onPause 里已经注销，不重注册就会"冻住"。
     */
    private void startStatusListeners() {
        Log.i(TAG, "状态监听：intranet=" + intranet
                + " netTick=" + (netTick != null) + " signalListener=" + (signalListener != null));
        registerBattery();
        if (intranet) {
            if (netTick == null) startWifiStatus();
        } else if (signalListener == null) {
            startSignal();
        }
    }

    // ==================== 内网 WIFI 模式：状态区 ====================

    private void startWifiStatus() {
        updateWifiStatus();
        netTick = () -> {
            updateWifiStatus();
            clockHandler.postDelayed(netTick, 30000);
        };
        clockHandler.postDelayed(netTick, 30000);
    }

    /** 当前 WIFI 名字 + 格数写进原来"信号"的位置；红条只在不正常时出现 */
    private void updateWifiStatus() {
        try {
            boolean wifiOn = IntranetMode.wifiOn(this);
            String ssid = wifiOn ? IntranetMode.currentSsid(this) : null;
            int level = wifiOn ? IntranetMode.wifiLevel(this) : -1;
            if (ssid != null) carrierName = ssid;
            Log.i(TAG, "WIFI 状态：on=" + wifiOn + " ssid=" + ssid + " level=" + level);
            if (signalBars != null) signalBars.setLevel(level);
            if (signalText != null) {
                signalText.setText(!wifiOn ? "WIFI 关" : (ssid == null ? "未连 WIFI" : ssid));
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 WIFI 状态失败", e);
        }
        updateNetBanner();
    }

    /**
     * 顶部红条：只在这台机器「该连却没连对」时出现。
     * 老人看见红条会担心，所以文案写成"家人看不到位置"而不是"网络故障"，
     * 点一下还能再念一遍。
     */
    private void setupNetworkBanner() {
        if (!intranet) return;
        try {
            ViewGroup root = findViewById(R.id.launcher_root);
            if (root == null) return;
            netBanner = new TextView(this);
            netBanner.setTextSize(14);
            netBanner.setTextColor(0xFFFFFFFF);
            netBanner.setGravity(android.view.Gravity.CENTER);
            netBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFC62828);
            bg.setCornerRadius(dp(10));
            netBanner.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(16), 0, dp(16), dp(8));
            netBanner.setLayoutParams(lp);
            netBanner.setVisibility(View.GONE);
            netBanner.setOnClickListener(v ->
                    TtsHelper.speak(this, netBanner.getText().toString()));
            // index 0 是时间那一行，插在它下面、图标网格上面
            root.addView(netBanner, 1);
        } catch (Exception e) {
            Log.w(TAG, "加网络提示条失败", e);
        }
    }

    private void updateNetBanner() {
        if (netBanner == null) return;
        String text = IntranetMode.bannerText(this);
        if (text == null) {
            netBanner.setVisibility(View.GONE);
            return;
        }
        netBanner.setText(text);
        netBanner.setVisibility(View.VISIBLE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void registerBattery() {
        if (batteryReceiver != null) return;
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                applyBatteryIntent(intent);
            }
        };
        // 注册时返回的是 sticky intent，能立刻拿到当前电量，不用等第一次变化
        Intent sticky = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            applyBatteryIntent(sticky);
        } else {
            // 极个别 ROM 不给 sticky，退回 BatteryManager 读一次
            applyBatteryFallback();
        }
    }

    /** 把 ACTION_BATTERY_CHANGED 里的 level/scale 换算成百分比 */
    private void applyBatteryIntent(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean chg = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (level < 0 || scale <= 0) {
            applyBatteryFallback();
            return;
        }
        applyBattery(Math.round(level * 100f / scale), chg);
    }

    /** 兜底读法：和系统状态栏同源，读不到就保持原值 */
    private void applyBatteryFallback() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return;
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (pct < 0 || pct > 100) return;
            boolean chg = false;
            try {
                chg = bm.isCharging();
            } catch (Exception ignored) {
            }
            applyBattery(pct, chg);
        } catch (Exception ignored) {
        }
    }

    private void unregisterBattery() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
            batteryReceiver = null;
        }
    }

    private void applyBattery(int pct, boolean chg) {
        batteryPct = pct;
        charging = chg;
        if (batteryBar != null) batteryBar.setStatus(pct, chg);
        if (batteryText != null) {
            batteryText.setText(pct < 0 ? "--" : (chg ? pct + "%⚡" : pct + "%"));
            // 低电量时连数字一起变红：老人未必看得出"条短了"，红字更直接。
            // 充电中不标红 —— 已经在充了，再红只会让人以为出问题。
            boolean low = pct >= 0 && pct < BatteryBarView.LOW_PCT && !chg;
            batteryText.setTextColor(low
                    ? Ui.color(this, R.color.elder_warning)
                    : Ui.textPrimary(this));
        }
    }

    private void startSignal() {
        Log.i(TAG, "启动手机信号监听（会覆盖信号区文字）");
        try {
            telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) {
                applySignal(-1, "");
                return;
            }
            // 注意：这里不能直接赋给 carrierName —— 荣耀 EMUI 会返回 "FF" 这种占位符，
            // 之前就是这么把 "FF" 写上屏幕的。统一交给 applySignal 里过滤。
            String opName = telephony.getNetworkOperatorName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SignalStrength ss = telephony.getSignalStrength();
                applySignal(ss == null ? -1 : ss.getLevel(), opName);
            }
            signalListener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength ss) {
                    applySignal(ss == null ? -1 : ss.getLevel(),
                            telephony == null ? "" : telephony.getNetworkOperatorName());
                }
            };
            telephony.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (Exception e) {
            Log.w(TAG, "读取信号失败（可能没装卡或没有权限）", e);
            applySignal(-1, "");
        }
    }

    private void stopSignal() {
        if (telephony != null && signalListener != null) {
            try {
                telephony.listen(signalListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception ignored) {
            }
        }
        signalListener = null;
    }

    private void applySignal(int level, String carrier) {
        signalLevel = level;
        if (isUsableCarrier(carrier)) carrierName = carrier;
        if (signalBars != null) signalBars.setLevel(level);
        if (signalText != null) {
            if (level < 0) {
                signalText.setText("无卡");
            } else if (level == 0) {
                signalText.setText("无信号");
            } else if (isUsableCarrier(carrierName)) {
                signalText.setText(carrierName);
            } else {
                // 有些 ROM（实测荣耀 EMUI）getNetworkOperatorName() 返回 "FF" 这种占位符，
                // 直接显示会变成"信号满格 · FF"，老人和我都看不懂，干脆退成格数。
                signalText.setText(level + " 格");
            }
        }
    }

    /**
     * 运营商名是否像个真名字。
     *
     * 实测荣耀 COL-AL10（EMUI 10）getNetworkOperatorName() 返回的是 "FF"，
     * 而且字符串里可能夹着零宽/控制字符，用整串正则匹配会漏过，屏幕上就是"4 格 · FF"。
     * 判定改成：含中文（中国移动/联通/电信/广电）直接认；否则至少 4 位字母数字才认，
     * "FF"、"46000" 这类占位符一律当没有，退化成显示格数。
     */
    private boolean isUsableCarrier(String name) {
        if (name == null) return false;
        String s = name.trim();
        if (s.isEmpty()) return false;
        if (s.matches(".*[\\u4e00-\\u9fa5].*")) return true;
        String letters = s.replaceAll("[^A-Za-z]", "");
        return letters.length() >= 4;
    }

    /** 点电量：念电量（+信号/WIFI）；点信号：只念信号 */
    /**
     * 点电量：只念电量。
     *
     * 原来是一个 speakStatus(boolean withSignal) 管两头，参数还用反了：
     * 点电量念「电量 + 信号」，点信号却只念电量、一个字都不提信号。
     * 拆成两个方法，点哪儿念哪儿，名实相符。
     */
    private void speakBattery() {
        String text = batteryPct < 0
                ? "电量未知"
                : "电量百分之" + batteryPct + (charging ? "，正在充电" : "");
        Log.i(TAG, "念电量：" + text);
        TtsHelper.speak(this, text);
    }

    /** 点信号 / 点 WIFI：只念网络 */
    private void speakNetwork() {
        String text = intranet ? wifiPhrase() : signalPhrase();
        Log.i(TAG, "念网络：" + text);
        TtsHelper.speak(this, text);
    }

    /** 内网 WIFI 模式下念网络，不念"没插卡" */
    private String wifiPhrase() {
        if (!IntranetMode.wifiOn(this)) return "无线网是关的，要让家人打开";
        String ssid = IntranetMode.currentSsid(this);
        if (ssid == null) return "还没连上无线网";
        return "连着 " + ssid;
    }

    private String signalPhrase() {
        if (signalLevel < 0) return "没有插卡或者读不到信号";
        if (signalLevel == 0) return "信号很弱，几乎没有";
        String[] cn = {"", "一", "两", "三", "四"};
        String s = "信号" + cn[Math.min(signalLevel, 4)] + "格";
        if (carrierName != null && !carrierName.isEmpty()) s += "，" + carrierName;
        return s;
    }

    /**
     * 主界面卡片：打电话 + 家人选定的应用 + 联系人卡片 + 无线网络
     *
     * 内网 WIFI 模式（不插卡的看视频机）下没有电话、短信、通讯录，
     * 这三类卡片点了只会报错，干脆不出现在桌面上。
     */
    private void loadTiles() {
        tiles.clear();
        intranet = IntranetMode.isOn(this);

        // 打电话固定放第一个：老人最常用，位置永远不变最好找
        if (!intranet) {
            tiles.add(new TileItem("打电话", android.R.drawable.ic_menu_call,
                    TileItem.ACTION_CALLWALL, null));
        }

        // 短信应用的包名：普通模式下换成「发短信」卡，内网模式下整张卡都不要
        String smsPkg = Prefs.firstResolvedPackage(this,
                new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")));

        for (String pkg : Prefs.tilesCsv(this).split(",")) {
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            // 自己的包不用再放一张卡片（点开还是自己，纯占位置）
            if (getPackageName().equals(pkg)) continue;
            // 没有卡的机器上，短信 App 打开是空的，留着只会让老人反复点
            if (intranet && pkg.equals(smsPkg)) continue;
            TileItem tile = resolvePackageTile(pkg);
            if (tile == null) continue;
            // 短信应用：点卡片直接进收件箱，比先进 App 首页少一步
            if (!intranet && pkg.equals(smsPkg)) {
                tile = new TileItem("发短信", 0, TileItem.ACTION_SMS, null);
                tile.iconDrawable = iconOf(pkg);
            }
            tiles.add(tile);
        }

        if (!intranet) {
            for (ContactStore.Contact c : ContactStore.load(this)) {
                TileItem t = new TileItem(
                        c.name == null || c.name.isEmpty() ? c.phone : c.name,
                        android.R.drawable.ic_menu_call, TileItem.ACTION_CONTACT, null);
                t.phone = c.phone;
                t.imagePath = c.image;
                tiles.add(t);
            }
        }

        tiles.add(new TileItem("无线网络", R.drawable.ic_wifi, TileItem.ACTION_WIFI, null));

        // 设置卡片：原来只能连点 8 下时间才进设置，装机调试太累。
        // 默认显示，家人可以在设置里关掉（关掉后回到原来的手势）。
        if (Prefs.getBool(this, Prefs.HOME_SETTINGS_CARD, true)) {
            tiles.add(new TileItem("设置", android.R.drawable.ic_menu_manage,
                    TileItem.ACTION_SETTINGS, null));
        }

        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private TileItem resolvePackageTile(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            if (pm.getLaunchIntentForPackage(pkg) == null) return null;
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            TileItem tile = new TileItem(label.toString(), 0, TileItem.ACTION_PACKAGE, pkg);
            tile.iconDrawable = pm.getApplicationIcon(pkg);
            return tile;
        } catch (Exception e) {
            Log.w(TAG, "解析包失败: " + pkg, e);
            return null;
        }
    }

    private android.graphics.drawable.Drawable iconOf(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 打开短信「收件箱列表」
     *
     * 老写法 `ACTION_VIEW + vnd.android-dir/mms-sms` 在 EMUI 上会直接跳到「写新短信」页，
     * 老人进去就是一片空白输入框，什么也看不了。
     * CATEGORY_APP_MESSAGING 才是「打开短信应用主界面」的标准入口，再按兜底顺序降级。
     */
    private void openMessagingApp() {
        try {
            Intent main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_MESSAGING);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(main);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setType("vnd.android-dir/mms-sms");
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
            return;
        } catch (Exception ignored) {
        }
        String smsPkg = Prefs.firstResolvedPackage(this,
                new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")));
        Intent launch = smsPkg == null ? null : getPackageManager().getLaunchIntentForPackage(smsPkg);
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "找不到短信应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void onTileClick(TileItem item) {
        switch (item.action) {
            case TileItem.ACTION_PACKAGE:
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(item.packageName);
                    if (launch != null) {
                        startActivity(launch);
                    } else {
                        Toast.makeText(this, item.label + " 已卸载，请让家人更新桌面图标",
                                Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "启动 App 失败", e);
                    Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show();
                }
                break;

            case TileItem.ACTION_DIAL:
                startActivity(new Intent(Intent.ACTION_DIAL));
                break;

            case TileItem.ACTION_SMS:
                openMessagingApp();
                break;

            case TileItem.ACTION_CONTACT: {
                String number = item.phone == null ? "" : item.phone.replace(" ", "");
                CallReturn.markPending(this);
                try {
                    startActivity(new Intent(Intent.ACTION_CALL,
                            android.net.Uri.parse("tel:" + android.net.Uri.encode(number))));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Intent.ACTION_DIAL,
                                android.net.Uri.parse("tel:" + android.net.Uri.encode(number))));
                    } catch (Exception ignored) {
                    }
                }
                break;
            }

            case TileItem.ACTION_CALLWALL:
                startActivity(new Intent(this, CallWallActivity.class));
                break;

            case TileItem.ACTION_WIFI:
                startActivity(new Intent(this, WifiActivity.class));
                break;

            case TileItem.ACTION_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;

            default:
                Toast.makeText(this, "未知操作", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!ready) return;
        updateClock();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        // 家人改完卡片 / 联系人回来立即刷新
        loadTiles();
        // 内网模式可能在设置页刚被打开/关闭：红条按需补建，状态立即刷一遍
        intranet = IntranetMode.isOn(this);
        if (intranet) {
            if (netBanner == null) setupNetworkBanner();
            updateWifiStatus();
        } else {
            updateNetBanner();
        }
        // 电量和信号每次回到桌面都重新挂上（onPause 里注销过）
        startStatusListeners();
        // 卡片变了，锁定任务白名单要同步（幂等，几毫秒）
        if (ElderDeviceAdminReceiver.isDeviceOwner(this)) {
            ElderDeviceAdminReceiver.enableAllRestrictions(this);
        }
        if (ElderDeviceAdminReceiver.isDeviceOwner(this) && isLockTaskEnabled()) {
            lockNow();
        }

        // 远程能力保活：开关开着但服务没在跑（被系统清理 / 重启过）就重新拉起
        try {
            if (Prefs.getBool(this, "ddns_enabled", false) && !DdnsService.RUNNING) {
                DdnsService.start(this);
            }
            if (Prefs.getBool(this, Prefs.LOCATE_SERVER, false) && !LocateServer.RUNNING) {
                LocateServer.start(this);
            }
            // 悬浮导航键：默认关，关掉时顺手把服务停掉，避免三个圆点一直浮着
            if (Prefs.getBool(this, Prefs.FLOATING_NAV, false)) {
                // 已经在跑时重复 start 也安全，只是再走一次 onStartCommand
                startService(new Intent(this, FloatingNavService.class));
            } else {
                stopService(new Intent(this, FloatingNavService.class));
            }
        } catch (Exception ignored) {
        }
    }

    /** 拦截 HOME 离开，尽量把老人留在桌面（系统级仍需默认桌面配合） */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
    }
}
