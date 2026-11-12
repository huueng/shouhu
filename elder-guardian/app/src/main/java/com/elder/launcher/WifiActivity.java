package com.elder.launcher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全的 WiFi 界面
 *
 * 设计原则（防误触）：
 * 1. 不提供「关闭 WiFi」「断开连接」「忘记网络」任何入口
 * 2. 连接网络必须二次确认
 * 3. WiFi 处于关闭状态时只提供「开启」，不提供关闭
 */
public class WifiActivity extends AppCompatActivity {

    private static final int REQ_LOCATION = 1001;

    private WifiManager wifi;
    private ConnectivityManager cm;
    private LinearLayout listContainer;
    private LinearLayout statusCard;
    /** 按住多久才生效：2 秒，手指乱点不会触发 */
    private static final long HOLD_MS = 2000L;

    /** 扫描完成广播：结果异步返回后重新渲染列表 */
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                renderResults();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        buildUi();
        ensurePermission();
        registerReceiver(scanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(scanReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("无线网络");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // 状态卡片：无线网络 / 移动数据，两行都能"按住 2 秒"切换（防误触）
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(0, dp(12), 0, dp(8));
        root.addView(statusCard);

        Button refresh = new Button(this);
        refresh.setText("重新搜索");
        refresh.setOnClickListener(v -> {
            refresh();
            Toast.makeText(this, "正在搜索附近的网络…", Toast.LENGTH_SHORT).show();
        });
        root.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("\n可连接的网络（点击连接）");
        sectionTitle.setTextSize(16);
        sectionTitle.setPadding(0, dp(16), 0, dp(8));
        root.addView(sectionTitle);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        TextView tip = new TextView(this);
        tip.setText("\n说明：\n"
                + "· 上面的开关都要「按住 2 秒」才会生效，点一下不算，避免老人误触。\n"
                + "· 本页面不会断开或忘记已保存的网络。\n"
                + "· 本机系统若不支持应用直接开关（Android 10 起常见），按住后会带你去系统的开关页面。");
        tip.setTextSize(12);
        root.addView(tip);

        setContentView(scroll);
        Ui.applyEdgeToEdge(this);
    }

    // ==================== 状态 + 受保护的开关 ====================

    private void renderStatus() {
        if (statusCard == null) return;
        statusCard.removeAllViews();

        // 内网 WIFI 模式：只显示现在连的是哪个网（不限制连哪个）
        if (IntranetMode.isOn(this)) {
            TextView tip = new TextView(this);
            tip.setText("内网 WIFI 模式 · " + IntranetMode.stateText(this)
                    + "\n连上任意 WIFI 都可以，上报时只带这个网络的名字。");
            tip.setTextSize(13);
            tip.setTextColor(Ui.textSecondary(this));
            tip.setPadding(dp(12), 0, dp(12), dp(8));
            statusCard.addView(tip);
        }

        // ---- 无线网络 ----
        boolean wifiOn = NetState.isWifiOn(this);
        String ssid = wifiOn ? NetState.wifiSsid(this) : null;
        String wifiValue = !wifiOn ? "已关闭"
                : (ssid == null ? "已开启，未连接" : "已开启 · " + ssid);
        addToggleRow("无线网络", wifiValue,
                NetState.canToggleWifi()
                        ? (wifiOn ? "按住 2 秒 关闭" : "按住 2 秒 开启")
                        : "按住 2 秒 去设置",
                () -> onWifiToggle(wifiOn));

        // ---- 移动数据 ----
        // 内网模式的机器没插卡，这一行永远是"已关闭/无服务"，只会让人困惑
        if (IntranetMode.isOn(this)) return;

        boolean dataOn = NetState.isMobileDataOn(this);
        String carrier = NetState.carrier(this);
        String type = NetState.networkType(this);
        StringBuilder dv = new StringBuilder(dataOn ? "已开启" : "已关闭");
        if (!carrier.isEmpty()) dv.append(" · ").append(carrier);
        if (dataOn && !type.isEmpty()) dv.append(" ").append(type);
        addToggleRow("移动数据", dv.toString(),
                NetState.canToggleMobileData()
                        ? (dataOn ? "按住 2 秒 关闭" : "按住 2 秒 开启")
                        : "按住 2 秒 去设置",
                () -> onDataToggle(dataOn));
    }

    /**
     * 一行开关：左边标题+状态，右边动作提示。
     * 整行都要"按住 2 秒"才触发——点一下就松手只会提示"要按住 2 秒"。
     */
    private void addToggleRow(String title, String value, String action, final Runnable onDone) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(14), dp(8), dp(14));
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTextColor(Ui.textPrimary(this));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setPadding(0, dp(4), 0, 0);
        v.setTextColor(Ui.textSecondary(this));
        texts.addView(t);
        texts.addView(v);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView act = new TextView(this);
        act.setText(action);
        act.setTextSize(14);
        act.setTextColor(Ui.accent(this));
        act.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(act);

        bindHold(row, v, value, onDone);
        statusCard.addView(row);
    }

    /** 按住 2 秒才执行；中途松手就取消并恢复文案 */
    private void bindHold(final View row, final TextView valueView, final String baseValue,
                          final Runnable onDone) {
        row.setOnTouchListener(new View.OnTouchListener() {
            private final Handler h = new Handler(Looper.getMainLooper());
            private Runnable tick;
            private long startAt;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startAt = SystemClock.elapsedRealtime();
                        view.setPressed(true);
                        tick = new Runnable() {
                            @Override
                            public void run() {
                                long ms = SystemClock.elapsedRealtime() - startAt;
                                if (ms >= HOLD_MS) {
                                    tick = null;
                                    view.setPressed(false);
                                    valueView.setText(baseValue);
                                    onDone.run();
                                    return;
                                }
                                valueView.setText(String.format(java.util.Locale.CHINA,
                                        "继续按住… 还需 %.1f 秒", (HOLD_MS - ms) / 1000f));
                                h.postDelayed(this, 100);
                            }
                        };
                        h.post(tick);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (tick != null) {
                            h.removeCallbacks(tick);
                            tick = null;
                        }
                        view.setPressed(false);
                        valueView.setText(baseValue);
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            Toast.makeText(WifiActivity.this,
                                    "要按住 2 秒才会生效", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void onWifiToggle(boolean currentlyOn) {
        if (NetState.canToggleWifi()) {
            boolean target = !currentlyOn;
            boolean ok = NetState.setWifi(this, target);
            renderStatus();
            refresh();
            Toast.makeText(this, ok ? ("无线网络已" + (target ? "开启" : "关闭"))
                    : "这台手机不允许应用直接开关无线网络，请去系统设置里操作",
                    Toast.LENGTH_LONG).show();
            return;
        }
        confirmThenOpen("无线网络",
                "Android 10 起，系统不允许应用直接开关 WiFi。\n"
                        + "点「去设置」会打开系统的无线网络页面，在那里手动开关。",
                android.provider.Settings.ACTION_WIFI_SETTINGS);
    }

    private void onDataToggle(boolean currentlyOn) {
        if (NetState.canToggleMobileData()) {
            NetState.setMobileData(this, !currentlyOn);
            renderStatus();
            return;
        }
        confirmThenOpen("移动数据",
                "移动数据的开关是系统权限，任何第三方应用都改不了（设备所有者也不行）。\n"
                        + "点「去设置」打开系统的移动数据页面，在那里手动开关。",
                android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS);
    }

    private void confirmThenOpen(String title, String message, String action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        startActivity(new Intent(action));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void ensurePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            }
        }
    }

    private void refresh() {
        renderStatus();
        listContainer.removeAllViews();

        if (wifi == null) return;

        if (!wifi.isWifiEnabled()) {
            TextView off = new TextView(this);
            off.setText("无线网络现在是关闭状态。\n按住上面「无线网络」那一行 2 秒即可打开。");
            off.setTextSize(15);
            off.setPadding(0, dp(12), 0, 0);
            listContainer.addView(off);
            return;
        }

        if (!hasLocationPermission()) {
            TextView noPerm = new TextView(this);
            noPerm.setText("需要「位置信息」权限才能搜索附近网络，请在家人设置页授权。");
            listContainer.addView(noPerm);
            return;
        }

        // 先渲染缓存（往往已经有上一次扫描的结果），再触发一次新扫描
        renderResults();

        boolean started = false;
        try {
            started = wifi.startScan();
        } catch (Exception ignored) {
        }
        if (!started) {
            Toast.makeText(this, "系统限制了搜索频率，先显示上次的结果，过几秒再点「重新搜索」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // 不指望 SCAN_RESULTS_AVAILABLE 广播（部分 ROM 不按时发），自己轮询几次
        scanPollCount = 0;
        handler.removeCallbacks(scanPoll);
        handler.postDelayed(scanPoll, 1200);
    }

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int scanPollCount = 0;
    private final Runnable scanPoll = new Runnable() {
        @Override
        public void run() {
            scanPollCount++;
            renderResults();
            if (scanPollCount < 5) handler.postDelayed(this, 1200);
        }
    };

    /** 系统定位总开关（Android 6 起，定位关着就搜不到 WIFI） */
    private boolean locationOn() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    getSystemService(LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) {
            return false;
        }
    }

    /** 渲染扫描结果：先列"当前已连接"，再列附近的网络 */
    private void renderResults() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        String currentSsid = currentSsid();
        int shown = 0;

        // 1) 当前已连接的那一条：以前被 skip 掉了，老人会以为"什么都没搜到"
        if (currentSsid != null) {
            TextView cur = new TextView(this);
            cur.setText("已连接：" + currentSsid + "\n（要换网络，点下面的名字）");
            cur.setTextSize(15);
            cur.setTextColor(Ui.textSecondary(this));
            cur.setPadding(dp(12), dp(8), dp(12), dp(12));
            listContainer.addView(cur);
        }

        // 2) 附近网络
        List<ScanResult> results = null;
        try {
            results = wifi == null ? null : wifi.getScanResults();
        } catch (Exception ignored) {
        }

        final int rawCount = results == null ? 0 : results.size();
        final StringBuilder rawNames = new StringBuilder();
        if (rawCount > 0) {
            for (ScanResult r : results) {
                if (rawNames.length() > 0) rawNames.append('、');
                rawNames.append(r.SSID);
            }
        }
        android.util.Log.i("ElderWifi", "扫描：原始 " + rawCount + " 个" + (rawCount > 0 ? "（" + rawNames + "）" : "")
                + "，当前已连接=" + currentSsid);

        if (results != null && !results.isEmpty()) {
            Set<String> seen = new HashSet<>();
            List<ScanResult> sorted = new ArrayList<>(results);
            java.util.Collections.sort(sorted, (a, b) -> b.level - a.level);
            for (ScanResult r : sorted) {
                String ssid = r.SSID;
                if (ssid == null || ssid.isEmpty()) continue;
                if (ssid.equals(currentSsid)) continue;
                if (!seen.add(ssid)) continue;
                listContainer.addView(makeNetworkRow(ssid, r.level, isOpen(r)));
                shown++;
            }
        }

        if (shown > 0) return;

        // 3) 一条都没有：把原因说清，别只留一句"没搜到"
        TextView empty = new TextView(this);
        empty.setTextSize(15);
        empty.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (!locationOn()) {
            empty.setText("搜不到网络，是因为手机的「位置信息」关着。\n"
                    + "Android 从 6.0 起规定：定位关着就不给应用返回 WIFI 列表。\n\n"
                    + "点下面的按钮去打开定位，回来再点「重新搜索」。");
            listContainer.addView(empty);

            Button go = new Button(this);
            go.setText("去打开定位");
            go.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "打不开系统设置，请手动打开定位", Toast.LENGTH_LONG).show();
                }
            });
            listContainer.addView(go);
        } else if (rawCount > 0) {
            // 扫到了网络，但除了当前连着的那个没有别的。
            // 以前一律写"没搜到"，会让人以为功能坏了（自测就踩到：附近只有 FF 一个）。
            empty.setText("附近只搜到 " + rawCount + " 个网络"
                    + (currentSsid == null ? "" : "，就是现在连着的「" + currentSsid + "」")
                    + "。\n\n"
                    + "· 想连别的网络，先确认那个路由器开着、而且离得不太远\n"
                    + "· 点「重新搜索」再试一次（系统限制两次搜索的间隔）\n"
                    + "· 也可以点下面的按钮，去系统 WLAN 页连");
            listContainer.addView(empty);
        } else {
            empty.setText("这附近暂时没有搜到网络。\n\n"
                    + "· 路由器是不是关了或者离得太远？\n"
                    + "· 点「重新搜索」再试一次（系统限制两次搜索的间隔）\n"
                    + "· 也可以点下面的按钮，直接去系统 WLAN 页连。");
            listContainer.addView(empty);

            Button go = new Button(this);
            go.setText("打开系统 WLAN 设置");
            go.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "打不开系统设置", Toast.LENGTH_LONG).show();
                }
            });
            listContainer.addView(go);
        }
    }

    private TextView makeNetworkRow(String ssid, int level, boolean open) {
        TextView row = new TextView(this);
        int bars = WifiManager.calculateSignalLevel(level, 4) + 1;
        String lock = open ? "（开放）" : "（加密）";
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < Math.max(1, bars); i++) {
            bar.append("▮");
        }
        row.setText(ssid + "  " + bar + lock);
        row.setTextSize(17);
        row.setPadding(dp(12), dp(16), dp(12), dp(16));
        row.setOnClickListener(v -> onNetworkClicked(ssid, open));
        // 电视端遥控器：让每一行都能获得焦点，方向键选中后按 OK 连接
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackgroundColor(hasFocus ? 0x332196F3 : 0x00000000));
        return row;
    }

    private void onNetworkClicked(final String ssid, final boolean open) {
        connectFlow(ssid, open);
    }

    private void connectFlow(String ssid, boolean open) {
        if (open) {
            new AlertDialog.Builder(this)
                    .setTitle("连接网络")
                    .setMessage("是否连接到「" + ssid + "」？\n\n当前网络不会受到影响。")
                    .setPositiveButton("连接", (d, w) -> doConnect(ssid, null))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("请输入 WiFi 密码");
        new AlertDialog.Builder(this)
                .setTitle("连接「" + ssid + "」")
                .setView(input)
                .setPositiveButton("连接", (d, w) -> {
                    String pwd = input.getText().toString().trim();
                    if (pwd.isEmpty()) {
                        Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doConnect(ssid, pwd);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doConnect(String ssid, String password) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder().setSsid(ssid);
                if (password != null) {
                    builder.setWpa2Passphrase(password);
                }
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(builder.build())
                        .build();
                cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        cm.bindProcessToNetwork(network);
                        runOnUiThread(() -> {
                            Toast.makeText(WifiActivity.this, "已连接「" + ssid + "」", Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    }

                    @Override
                    public void onUnavailable() {
                        runOnUiThread(() -> Toast.makeText(WifiActivity.this,
                                "未能连接，请在系统弹窗中确认或检查密码", Toast.LENGTH_LONG).show());
                    }
                });
                Toast.makeText(this, "请在系统弹窗中确认连接", Toast.LENGTH_LONG).show();
            } else {
                WifiConfiguration conf = new WifiConfiguration();
                conf.SSID = "\"" + ssid + "\"";
                if (password == null) {
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else {
                    conf.preSharedKey = "\"" + password + "\"";
                }
                int netId = wifi.addNetwork(conf);
                if (netId == -1) {
                    Toast.makeText(this, "添加网络失败，请检查密码", Toast.LENGTH_LONG).show();
                    return;
                }
                wifi.enableNetwork(netId, true);
                wifi.reconnect();
                Toast.makeText(this, "正在连接「" + ssid + "」", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "连接失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String currentConnectionText() {
        if (wifi == null) return "无法访问无线网络服务";
        if (!wifi.isWifiEnabled()) return "当前未连接无线网络（无线网络已关闭）";
        String ssid = currentSsid();
        if (ssid == null) return "当前未连接任何无线网络";
        return "当前连接：" + ssid;
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        try {
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            ssid = ssid.replace("\"", "");
            if (ssid.equals("<unknown ssid>") || ssid.isEmpty()) return null;
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isOpen(ScanResult r) {
        String caps = r.capabilities;
        return caps == null || !caps.contains("WPA") && !caps.contains("WEP");
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
