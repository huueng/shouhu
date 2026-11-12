package com.elder.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

/**
 * 局域网传包 —— 电脑/家人手机用浏览器把文件传到老人机
 *
 * 安全约束（三件事同时成立才可用）：
 * 1. 默认关闭，必须在设置里手动开启；
 * 2. 开启后 10 分钟自动关闭；
 * 3. 上传需要 6 位随机码（手机屏幕上显示），只监听 WiFi 局域网地址。
 *
 * 上传的文件落在 Android/data/com.elder.launcher/files/incoming/，
 * 需要在设置页手动点「安装」才会真正安装 —— 不做静默安装，避免把手机交出去。
 */
public class FileTransferServer extends Service {

    public static final int PORT = 8898;
    private static final String TAG = "ElderTransfer";
    private static final String CHANNEL_ID = "elder_transfer";
    private static final long AUTO_STOP_MS = 10 * 60 * 1000L;
    private static final long MAX_BODY = 300L * 1024 * 1024;

    /** 本次会话的 6 位码，界面显示它 */
    public static volatile String sessionCode = "";

    /** 是否正在监听（设置页用它判断真实状态） */
    public static volatile boolean RUNNING = false;
    private ServerSocket serverSocket;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * 收到的文件统一放公共下载目录，家人用文件管理器就能找到，
     * 不用去 Android/data 里翻（Android 10 上普通文件管理器看不到那儿）。
     */
    public static File inboxDir(Context ctx) {
        try {
            File pub = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "老人守护");
            if (pub.exists() || pub.mkdirs()) return pub;
        } catch (Exception ignored) {
        }
        // 没存储权限时退回应用私有目录，功能不至于直接坏掉
        File fallback = new File(ctx.getExternalFilesDir(null), "incoming");
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, FileTransferServer.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, FileTransferServer.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sessionCode = String.format("%06d", new Random().nextInt(1000000));
        startForeground(2202, buildNotification());
        if (!RUNNING) startLoop();

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            Log.i(TAG, "10 分钟到期，自动关闭传包服务");
            stopSelf();
        }, AUTO_STOP_MS);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "传包服务",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("网页传包已开启（10 分钟后自动关闭）")
                .setContentText("校验码 " + sessionCode + "，端口 " + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void startLoop() {
        RUNNING = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                // 只绑 WiFi 的局域网地址：不暴露到移动数据/公网
                InetSocketAddress addr = new InetSocketAddress(PORT);
                String lan = lanIp();
                if (lan != null) {
                    addr = new InetSocketAddress(java.net.InetAddress.getByName(lan), PORT);
                    Log.i(TAG, "只监听局域网地址 " + lan + ":" + PORT);
                }
                serverSocket.bind(addr);
                while (RUNNING) {
                    Socket s = serverSocket.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "传包服务监听失败", e);
                RUNNING = false;
            }
        }, "transfer-server").start();
    }

    /** WiFi 的 IPv4 地址（拿不到就返回 null，此时退回通配绑定） */
    public static String lanIp() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ElderApp.getAppContext().getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return String.format("%d.%d.%d.%d",
                    ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0, cur;
            // 读到 \r\n\r\n 为止
            while ((cur = in.read()) != -1) {
                headBuf.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') break;
                prev3 = prev2;
                prev2 = prev1;
                prev1 = cur;
            }
            String head = headBuf.toString("UTF-8");
            String[] lines = head.split("\r\n");
            if (lines.length == 0) {
                socket.close();
                return;
            }
            String[] parts = lines[0].split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            String query = "";
            int qi = path.indexOf('?');
            if (qi >= 0) {
                query = path.substring(qi + 1);
                path = path.substring(0, qi);
            }

            int contentLength = 0;
            for (String l : lines) {
                if (l.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(l.substring(15).trim());
                    } catch (Exception ignored) {
                    }
                }
            }

            String code = param(query, "code");
            boolean authed = sessionCode != null && !sessionCode.isEmpty() && sessionCode.equals(code);

            if ("/".equals(path) || "/index.html".equals(path)) {
                respond(socket, 200, "text/html; charset=utf-8", PAGE);
                return;
            }
            if (!authed) {
                respond(socket, 403, "text/plain; charset=utf-8", "校验码不对");
                return;
            }
            if ("/api/list".equals(path)) {
                respond(socket, 200, "text/html; charset=utf-8", listFiles(code));
                return;
            }
            if ("/api/file".equals(path)) {
                String name = param(query, "name");
                if (name == null || name.trim().isEmpty()) {
                    respond(socket, 400, "text/plain; charset=utf-8", "缺少文件名");
                    return;
                }
                File f = new File(inboxDir(this), name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                if (!f.exists()) {
                    respond(socket, 404, "text/plain; charset=utf-8", "文件不存在");
                    return;
                }
                respondFile(socket, f, mimeOf(f.getName()), f.getName());
                return;
            }
            if ("/api/apk".equals(path)) {
                File apk = new File(getApplicationInfo().sourceDir);
                respondFile(socket, apk, "application/vnd.android.package-archive", "elder-guardian.apk");
                return;
            }
            if ("/api/upload".equals(path) && "POST".equals(method)) {
                if (contentLength <= 0 || contentLength > MAX_BODY) {
                    respond(socket, 400, "text/plain; charset=utf-8", "文件大小不合法");
                    return;
                }
                String name = param(query, "name");
                if (name == null || name.trim().isEmpty()) name = "upload.bin";
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (name.length() > 80) name = name.substring(name.length() - 80);

                File out;
                try {
                    out = new File(inboxDir(this), name);
                } catch (Exception e) {
                    respond(socket, 500, "text/plain; charset=utf-8", "保存失败：" + e.getMessage());
                    return;
                }
                byte[] buf = new byte[8192];
                int remain = contentLength;
                FileOutputStream fos = new FileOutputStream(out);
                while (remain > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, remain));
                    if (n <= 0) break;
                    fos.write(buf, 0, n);
                    remain -= n;
                }
                fos.close();
                if (out.length() <= 0 && contentLength > 0) {
                    respond(socket, 500, "text/plain; charset=utf-8",
                            "写入失败（可能没有存储权限）");
                    return;
                }
                respond(socket, 200, "text/plain; charset=utf-8",
                        "已收到 " + name + "（" + out.length() / 1024 + " KB）\n"
                                + "存到：" + out.getAbsolutePath() + "\n"
                                + "请在手机「设置 → 已收到的文件」里点安装。");
                return;
            }
            respond(socket, 404, "text/plain; charset=utf-8", "not found");
        } catch (Exception e) {
            Log.w(TAG, "处理请求失败", e);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 文件列表直接给 HTML 片段，页面上每一项都能点开 */
    private String listFiles(String code) {
        File[] files = inboxDir(this).listFiles();
        if (files == null || files.length == 0) {
            return "<p class=hint>还没有文件</p>";
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            String enc;
            try {
                enc = java.net.URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
            } catch (Exception e) {
                enc = f.getName();
            }
            sb.append("<a class=item href='/api/file?code=").append(code)
                    .append("&name=").append(enc)
                    .append("' target='_blank'>")
                    .append(f.getName().replace("<", "&lt;"))
                    .append("<span class=size>").append(f.length() / 1024).append(" KB</span></a>");
        }
        return sb.toString();
    }

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
            String head = "HTTP/1.1 " + code + " X\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
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

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private void respondFile(Socket socket, File file, String type, String fileName) {
        try {
            long len = file.length();
            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Disposition: inline; filename=\"" + fileName + "\"\r\n"
                    + "Content-Length: " + len + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            fis.close();
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
        sessionCode = "";
        handler.removeCallbacksAndMessages(null);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        Prefs.putBool(this, Prefs.TRANSFER_ENABLED, false);
        super.onDestroy();
    }

    /** 极简页面：输码 → 看文件 → 上传。不用任何外部资源，老人机也能秒开 */
    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>老人守护 · 传包</title><style>"
            + "*{box-sizing:border-box}"
            + "body{font-family:-apple-system,system-ui,'PingFang SC',sans-serif;margin:0;padding:18px;background:#f5f6f8;color:#1f2329}"
            + ".card{background:#fff;border-radius:14px;padding:16px;margin-bottom:12px;box-shadow:0 2px 10px rgba(0,0,0,.06)}"
            + "h1{font-size:19px;margin:0 0 6px;color:#1976d2}"
            + "p.hint{color:#6b7280;font-size:13px;line-height:1.7;margin:6px 0}"
            + "input,button{width:100%;font-size:16px;padding:12px;margin:8px 0;border-radius:10px;border:1px solid #d5d9e0;"
            + "background:#fff;color:inherit}"
            + "button{background:#1976d2;color:#fff;border:0;font-weight:600}"
            + "button.ghost{background:#eef2f7;color:#1f2937;font-weight:500}"
            + "a.item{display:flex;justify-content:space-between;align-items:center;padding:12px;border-radius:10px;"
            + "background:#f7f9fb;color:#1976d2;text-decoration:none;margin:6px 0;font-size:15px;word-break:break-all}"
            + ".size{color:#8a9099;font-size:12px;margin-left:10px;flex:none}"
            + "@media(prefers-color-scheme:dark){body{background:#14171a;color:#e8eaed}"
            + ".card{background:#1d2125;box-shadow:none}input{background:#24292e;border-color:#333a41;color:#e8eaed}"
            + "button.ghost{background:#262c31;color:#e8eaed}a.item{background:#24292e;color:#7cb7f5}"
            + "p.hint,.size{color:#9aa4af}}"
            + "</style></head><body>"
            + "<div class=card>"
            + "<h1>老人守护 · 网页传包</h1>"
            + "<p class=hint>手机上「设置 → 网页传包」会显示二维码，用手机扫一下就自动带校验码进来；"
            + "也可以手动输入那 6 位数字。</p>"
            + "<input id=code placeholder='6 位校验码' inputmode=numeric maxlength=6>"
            + "<button onclick='load()'>查看已收到的文件</button>"
            + "</div>"
            + "<div class=card>"
            + "<h1>已收到的文件</h1>"
            + "<div id=list><p class=hint>输入校验码后显示</p></div>"
            + "</div>"
            + "<div class=card>"
            + "<h1>上传到手机</h1>"
            + "<input type=file id=f>"
            + "<button onclick='up()'>上传</button>"
            + "<p class=hint>文件会存到手机的「下载 / 老人守护」目录。</p>"
            + "</div>"
            + "<script>var code='';"
            + "function q(i){return document.getElementById(i)}"
            + "function set(t){q('list').innerHTML='<p class=hint>'+t+'</p>'}"
            + "function init(){var i=location.search.indexOf('code=');if(i<0)return;"
            + "var c=location.search.substr(i+5,6);"
            + "if(/^[0-9][0-9][0-9][0-9][0-9][0-9]$/.test(c)){q('code').value=c;load();}}"
            + "async function load(){code=q('code').value.trim();"
            + "if(code.length!=6){set('请输入 6 位校验码');return;}"
            + "var r=await fetch('/api/list?code='+code);var t=await r.text();"
            + "q('list').innerHTML=(r.ok?t:'<p class=hint>'+t+'</p>');}"
            + "async function up(){var f=q('f').files[0];if(!f){set('先选一个文件');return;}"
            + "if(code.length!=6){set('先输入校验码并点「查看已收到的文件」');return;}"
            + "set('上传中…');"
            + "var r=await fetch('/api/upload?code='+code+'&name='+encodeURIComponent(f.name),{method:'POST',body:f});"
            + "set(await r.text());load();}"
            + "init();"
            + "</script></body></html>";
}
