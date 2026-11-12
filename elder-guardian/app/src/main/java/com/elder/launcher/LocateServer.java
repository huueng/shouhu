package com.elder.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 定位服务 —— 老人机自己当服务器（方案 B）
 *
 * 家人端（网页或 App）直接访问：
 *   http://[老人机公网地址]:8899/loc?key=密钥
 * 返回 JSON：经纬度、精度、时间、电量、高德链接。
 *
 * 为什么反着做（老人机当服务端）：
 * 老人机不用装客户端、不用常驻长连接推数据，只在有人来问的时候取一次位置，最省电；
 * 家人端随便用浏览器就能查，不需要额外服务器。前提是老人机有公网 IPv6（移动数据一般都有）。
 */
public class LocateServer extends Service {

    public static final int PORT = 8899;
    private static final String TAG = "ElderLocateSrv";
    private static final String CHANNEL_ID = "elder_server";

    /** 是否正在监听（设置页用它显示真实状态，比读开关更可靠） */
    public static volatile boolean RUNNING = false;
    /** 可能同时开 IPv6 与 IPv4 两个监听（见 startLoop 里的说明） */
    private final java.util.List<ServerSocket> servers = new java.util.ArrayList<>();
    private Thread acceptThread;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, LocateServer.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, LocateServer.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2201, buildNotification());
        if (!RUNNING) startLoop();
        // 不用 START_STICKY：这是家人手动开/关的服务，进程被杀就该停，
        // 否则用户关了开关它还会被系统自动拉起来耗电。
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "守护服务",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("定位服务已开启")
                .setContentText("家人可通过公网地址查询位置（端口 " + PORT + "）")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void startLoop() {
        RUNNING = true;
        servers.clear();

        // 依次尝试监听，能开几个开几个：
        // 1) IPv6 通配 [::]：绑成功后 Linux 默认 v6only=0，IPv4 也能连
        // 2) IPv4 通配 0.0.0.0：如果 1 已经是双栈，这会 EADDRINUSE，忽略即可
        // 3) 本机公网 IPv6 具体地址：极少数机型不允许绑通配时的兜底
        //
        // 为什么不用 new InetSocketAddress(PORT)：那个地址是 Inet4Address，
        // Linux 上会建 AF_INET 套接字，结果只监听 IPv4 ——
        // 家人端用公网 IPv6 域名访问会直接被 RST（实测踩过）。
        try {
            tryBind(java.net.InetAddress.getByAddress(new byte[16]), "IPv6 [::]");
        } catch (Exception e) {
            Log.w(TAG, "构造 IPv6 通配地址失败", e);
        }
        try {
            tryBind(java.net.InetAddress.getByAddress(new byte[]{0, 0, 0, 0}), "IPv4 0.0.0.0");
        } catch (Exception e) {
            Log.w(TAG, "构造 IPv4 通配地址失败", e);
        }
        if (servers.isEmpty()) {
            String ip = DdnsUpdater.pickIpv6();
            if (ip != null && !ip.isEmpty()) {
                try {
                    tryBind(java.net.InetAddress.getByName(ip), "IPv6 " + ip);
                } catch (Exception ignored) {
                }
            }
        }
        if (servers.isEmpty()) {
            Log.e(TAG, "端口 " + PORT + " 监听失败，服务停止");
            RUNNING = false;
            return;
        }
        Log.i(TAG, "定位服务已监听 " + PORT + "（共 " + servers.size() + " 个套接字）");

        for (final ServerSocket ss : servers) {
            new Thread(() -> acceptLoop(ss), "locate-accept").start();
        }
    }

    private void tryBind(java.net.InetAddress addr, String label) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(addr, PORT));
            servers.add(ss);
            Log.i(TAG, "监听成功：" + label + ":" + PORT);
        } catch (Exception e) {
            Log.w(TAG, "监听失败：" + label + " -> " + e);
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (RUNNING) {
            try {
                Socket s = ss.accept();
                new Thread(() -> handle(s)).start();
            } catch (Exception e) {
                if (RUNNING) Log.w(TAG, "accept 异常", e);
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(8000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }
            // 把请求头读干净（这里不需要它们，但不清会被浏览器当成半开连接）
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // no-op
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }

            String key = param(query, "key");
            String expect = Prefs.getString(this, Prefs.LOCATE_KEY, "");

            boolean known = "/loc".equals(path) || "/status".equals(path)
                    || "/track".equals(path) || "/cmd".equals(path);
            if (!known) {
                respond(socket, 404, "application/json", "{\"ok\":false,\"error\":\"not found\"}");
                return;
            }
            if (expect == null || expect.isEmpty() || !expect.equals(key)) {
                respond(socket, 403, "application/json", "{\"ok\":false,\"error\":\"key\"}");
                return;
            }

            if ("/status".equals(path)) {
                respond(socket, 200, "application/json", StatusReport.build(this).toString());
                return;
            }
            if ("/track".equals(path)) {
                long since = 0L;
                try {
                    String raw = param(query, "since");
                    if (raw != null && !raw.isEmpty()) since = Long.parseLong(raw);
                } catch (Exception ignored) {
                }
                org.json.JSONObject body = new org.json.JSONObject();
                try {
                    body.put("ok", true);
                    body.put("points", TrackStore.toJson(this, since));
                } catch (Exception e) {
                    try {
                        body.put("ok", false);
                    } catch (Exception ignored) {
                    }
                }
                respond(socket, 200, "application/json", body.toString());
                return;
            }

            // /cmd?key=…&c=volume&arg=up
            //
            // 直连模式以前只能看点位，调音量、存快照都得跑到老人机上点。
            // 这里把中继那套命令执行器直接暴露出来，两条通道行为一致；
            // 认证还是同一个密钥，不额外开口子。
            if ("/cmd".equals(path)) {
                String c = param(query, "c");
                String a = param(query, "arg");
                if (c == null) c = "";
                org.json.JSONObject body = new org.json.JSONObject();
                try {
                    if ("snaps".equals(c)) {
                        body.put("ok", true);
                        body.put("snaps", SnapshotStore.list(this));
                    } else {
                        RemoteCommand.Res res = RemoteCommand.exec(this, c, a == null ? "" : a);
                        body.put("ok", res.ok);
                        body.put("msg", res.msg);
                        body.put("cmd", c);
                    }
                } catch (Exception e) {
                    try {
                        body = new org.json.JSONObject();
                        body.put("ok", false);
                        body.put("msg", "执行失败：" + e.getClass().getSimpleName());
                    } catch (Exception ignored) {
                    }
                }
                respond(socket, 200, "application/json", body.toString());
                return;
            }

            LocationHelper.Result r = LocationHelper.getOnce(this, 9000);
            if (r == null || !r.ok()) {
                respond(socket, 200, "application/json",
                        "{\"ok\":false,\"error\":\"" + (r == null ? "timeout" : r.error) + "\"}");
                return;
            }
            double[] gcj = LocationHelper.wgs84ToGcj02(r.lat, r.lng);
            String body = "{"
                    + "\"ok\":true,"
                    + "\"lat\":" + r.lat + ","
                    + "\"lng\":" + r.lng + ","
                    + "\"gcj_lat\":" + gcj[0] + ","
                    + "\"gcj_lng\":" + gcj[1] + ","
                    + "\"acc\":" + r.accuracy + ","
                    + "\"time\":" + System.currentTimeMillis() + ","
                    + "\"battery\":" + battery() + ","
                    + "\"link\":\"" + LocationHelper.buildAmapLink(r.lat, r.lng) + "\""
                    + "}";
            respond(socket, 200, "application/json", body);
        } catch (Exception e) {
            Log.w(TAG, "处理请求失败", e);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private int battery() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从 a=1&b=2 里取值 */
    private static String param(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (name.equals(kv.substring(0, eq))) {
                return android.net.Uri.decode(kv.substring(eq + 1));
            }
        }
        return null;
    }

    private void respond(Socket socket, int code, String type, String body) {
        try {
            byte[] data = body.getBytes("UTF-8");
            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
                    + "Content-Type: " + type + "; charset=utf-8\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        for (ServerSocket ss : servers) {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }
        servers.clear();
        Prefs.putBool(this, Prefs.LOCATE_SERVER, false);
        super.onDestroy();
    }

    /**
     * 取本机的局域网 / 公网地址，用来提示家人填什么。
     * 返回形如 ["IPv6 2408:xxxx:...", "IPv4 192.168.1.23"]
     */
    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (!name.startsWith("wlan") && !name.startsWith("rmnet")
                        && !name.startsWith("eth")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (addr instanceof Inet6Address) {
                        // 去掉 %wlan0 这类作用域后缀
                        int pct = host.indexOf('%');
                        if (pct > 0) host = host.substring(0, pct);
                        if (host.startsWith("fe80")) continue;   // 链路本地地址家人访问不到
                        out.add("IPv6 " + host);
                    } else {
                        out.add("IPv4 " + host);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
