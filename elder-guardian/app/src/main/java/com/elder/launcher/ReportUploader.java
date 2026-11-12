package com.elder.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * 主动上报：把「当前位置 + 体检 + 轨迹增量 + 使用统计」POST 出去。
 *
 * 两种玩法共用这一个类：
 *   · 老方案（直连）：家人端来查老人机，不需要上报
 *   · 新方案（中继）：老人端主动往外推，围栏判定在 NAS 上做
 *
 * 三个设计要点：
 *   1. **每次上报都带 pos（当前位置）**，即使人没动过。围栏判定靠 pos，
 *      不靠轨迹——轨迹是"动了才记点"，人站着不动时没有新点。
 *   2. 定时上报走轻量包（pos + 电量 + 轨迹增量），手动/首包走完整包（加诊断与使用统计）。
 *   3. 失败原因要分类：域名解析失败 / 超时 / 证书问题 / 401 / 404 / 504 各说各的，
 *      否则设置页只有一句"连不上"，没法排查。
 */
public final class ReportUploader {

    private static final String TAG = "ElderReport";
    // 走 Cloudflare 隧道时，冷启动那一下握手可能要十几秒，8 秒必挂
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 25000;

    /** 最近一次定位，格式 "lat,lon,acc" */
    public static final String KEY_LAST_POS = "last_pos";
    public static final String KEY_LAST_POS_T = "last_pos_t";

    // ===== 断网补发 =====
    // 村里信号差、电梯里、移动数据短暂断开都是常态。上报失败就丢的话，
    // 家人看到的是"轨迹断了"，而这恰恰是最该有数据的时候。
    // 所以失败先落到本地队列，下一轮先补发旧的再发新的。
    private static final String QFILE = "report_queue.jsonl";
    private static final int QMAX = 120;                   // 10 分钟一包 ≈ 20 小时
    private static final long QTTL_MS = 24 * 3600 * 1000L; // 超过一天的点补发也没意义了
    private static final int FLUSH_PER_ROUND = 40;         // 一轮最多补发几条；积压几十条时别拖到下一轮

    private ReportUploader() {
    }

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    public static boolean enabled(Context ctx) {
        String url = Prefs.getString(ctx, Prefs.REPORT_URL, "");
        return Prefs.getBool(ctx, Prefs.REPORT_ENABLED, false)
                && url != null && !url.trim().isEmpty();
    }

    /** 上报间隔（分钟）。默认 10 分钟，这是围栏告警能接受的最大延迟。 */
    public static int intervalMinutes(Context ctx) {
        int m = Prefs.getInt(ctx, Prefs.REPORT_INTERVAL, 10);
        if (m < 5) m = 5;
        if (m > 180) m = 180;
        return m;
    }

    /** 记下最近一次定位，下一次上报会带上去 */
    public static void rememberPos(Context ctx, double lat, double lng, float acc) {
        Prefs.putString(ctx, KEY_LAST_POS, lat + "," + lng + "," + acc);
        Prefs.putLong(ctx, KEY_LAST_POS_T, System.currentTimeMillis());
    }

    /** 拼请求体。light=true 时不带诊断与使用统计，省流量。 */
    private static JSONObject buildPayload(Context ctx, boolean light) {
        JSONObject body = new JSONObject();
        try {
            body.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            body.put("version", DiagReport.version(ctx));
            body.put("time", System.currentTimeMillis());
            body.put("event", light ? "tick" : "manual");
            body.put("status", StatusReport.build(ctx));

            // 当前位置：围栏判定用的就是它
            String lp = Prefs.getString(ctx, KEY_LAST_POS, "");
            if (lp != null && !lp.trim().isEmpty()) {
                String[] p = lp.split(",");
                if (p.length >= 3) {
                    try {
                        JSONObject pos = new JSONObject();
                        pos.put("t", Prefs.getLong(ctx, KEY_LAST_POS_T, 0));
                        pos.put("lat", Double.parseDouble(p[0].trim()));
                        pos.put("lon", Double.parseDouble(p[1].trim()));
                        pos.put("acc", (int) Float.parseFloat(p[2].trim()));
                        body.put("pos", pos);
                    } catch (Exception ignored) {
                    }
                }
            }

            // 轨迹增量：只发上次之后的新点，避免重复
            long since = Prefs.getLong(ctx, "report_last_track_t", 0L);
            JSONArray fresh = new JSONArray();
            long maxT = since;
            for (JSONObject p : TrackStore.load(ctx)) {
                long t = p.optLong("t", 0);
                if (t <= since) continue;
                fresh.put(p);
                if (t > maxT) maxT = t;
            }
            body.put("track", fresh);
            body.put("trackSince", since);
            body.put("trackLastT", maxT);

            // 快照列表 + 上报周期：家人端「恢复布局」要能挑、要能说清"等几分钟"
            body.put("snaps", SnapshotStore.list(ctx));
            body.put("interval", intervalMinutes(ctx));

            // 上一次远程命令的执行结果：随包带回中继，家人端才知道点了按钮后发生了什么
            JSONObject cmdResult = RemoteCommand.lastResult(ctx);
            if (cmdResult != null) body.put("cmdResult", cmdResult);

            // 诊断与使用统计体积大、变化慢，只随完整包发
            if (!light) {
                body.put("diag", DiagReport.buildJson(ctx));
                body.put("usage", UsageStore.todayTop(ctx, 5));
            }
        } catch (Exception e) {
            Log.w(TAG, "拼装上报内容失败", e);
        }
        return body;
    }

    public static void uploadAsync(final Context ctx, final boolean manual, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        if (!enabled(app)) {
            String url = Prefs.getString(app, Prefs.REPORT_URL, "");
            String msg = (url == null || url.trim().isEmpty())
                    ? "还没填上报地址"
                    : "上报开关没打开（要勾选「开启主动上报」）";
            if (cb != null) cb.onResult(false, msg);
            Prefs.putString(app, "report_last", msg);
            return;
        }
        // 只要还有上报动作，就先确保快通道在跑。
        // 安装完新版本 / 被系统清掉常驻服务后，没有这一步就得等开机或改设置才会重连，
        // 家人那边看到的就是"秒级通道未连接"，点了没反应。
        if (FastLinkService.enabled(app)) FastLinkService.start(app);
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                // 先补发之前失败的：这段网络已经通了，旧数据比新数据更缺
                int done = flushQueue(app);

                JSONObject body = buildPayload(app, !manual);
                PostResult res = post(app, body.toString());
                ok = res.ok;
                if (ok) {
                    long t = body.optLong("trackLastT", 0);
                    if (t > 0) Prefs.putLong(app, "report_last_track_t", t);
                    Prefs.putLong(app, "report_last_at", System.currentTimeMillis());
                    msg = "上报成功" + (res.code > 0 ? "（HTTP " + res.code + "）" : "");
                    if (done > 0) msg += "，顺手补发了 " + done + " 条";
                    // 队列还没排空就再排一轮。以前一轮只补 5 条、剩下的等十分钟，
                    // 断网几小时后家人看到的就是"网络好了，但还剩 50 条"，很像是没发出去。
                    if (done > 0 && pendingCount(app) > 0) scheduleDrain(app);

                    // 中继可能在响应里捎了一条待执行命令（体检 / 恢复布局 / 重启桌面…）
                    try {
                        JSONObject cmd = null;
                        if (res.body != null && !res.body.trim().isEmpty()) {
                            cmd = new JSONObject(res.body).optJSONObject("cmd");
                        }
                        // 先清掉"已经随本包发出去"的旧结果，再执行新命令 ——
                        // 顺序反了会把刚产生的执行结果一起清掉
                        RemoteCommand.markResultSent(app);
                        if (cmd != null) {
                            RemoteCommand.accept(app, cmd);
                            boolean ran = RemoteCommand.runPending(app) != null;
                            // 执行完立刻回传，别等下一个 10 分钟周期：
                            // 家人按「查询位置」要的是"现在"，等一轮就完全没用了
                            if (ran) scheduleFollowUpReport(app);
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    // 没发出去就存下来，下一轮先补
                    enqueue(app, body);
                    msg = "上报失败：" + res.error
                            + (pendingCount(app) > 0 ? "（已存下，等网络恢复自动补发）" : "");
                }
            } catch (Exception e) {
                Log.w(TAG, "上报异常", e);
                ok = false;
                msg = "上报失败：" + e.getClass().getSimpleName();
            }
            Prefs.putString(app, "report_last", msg);
            if (cb != null) cb.onResult(ok, msg);
        }, "report-upload").start();
    }

    // ==================== 补发队列 ====================

    private static java.io.File queueFile(Context ctx) {
        return new java.io.File(ctx.getFilesDir(), QFILE);
    }

    /** 队列里还压着几条没发出去 */
    public static int pendingCount(Context ctx) {
        try {
            return readQueue(ctx).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static java.util.List<String> readQueue(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.io.File f = queueFile(ctx);
        if (!f.exists()) return out;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) out.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "读队列失败", e);
        } finally {
            close(br);
        }
        return out;
    }

    private static void writeQueue(Context ctx, java.util.List<String> lines) {
        java.io.File f = queueFile(ctx);
        java.io.File tmp = new java.io.File(f.getAbsolutePath() + ".tmp");
        java.io.OutputStreamWriter w = null;
        try {
            w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(tmp), "UTF-8");
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
            w.flush();
            close(w);
            w = null;
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "旧队列文件删不掉");
            }
            if (!tmp.renameTo(f)) {
                Log.w(TAG, "队列文件改名失败");
            }
        } catch (Exception e) {
            Log.w(TAG, "写队列失败", e);
        } finally {
            close(w);
        }
    }

    /** 把一份报文存进队列，顺带按容量和时间裁掉过期的 */
    private static void enqueue(Context ctx, JSONObject body) {
        try {
            java.util.List<String> lines = readQueue(ctx);

            // 报文里的点已经"交给队列"了，游标往前走，
            // 否则下一包会把同一批轨迹点重复发一遍。
            long t = body.optLong("trackLastT", 0);
            if (t > 0) Prefs.putLong(ctx, "report_last_track_t", t);

            lines.add(body.toString());

            long now = System.currentTimeMillis();
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                long ts = jsonTime(line);
                if (ts > 0 && now - ts > QTTL_MS) continue;
                kept.add(0, line);
                if (kept.size() >= QMAX) break;
            }
            writeQueue(ctx, kept);
            Log.i(TAG, "已存入待补发队列，共 " + kept.size() + " 条");
        } catch (Exception e) {
            Log.w(TAG, "入队失败", e);
        }
    }

    /**
     * 尝试把队列里的旧报文发出去，返回成功条数。
     * 遇到第一条失败就停——说明网络还是不通，继续试只是白费电。
     */
    private static int flushQueue(Context ctx) {
        java.util.List<String> lines;
        try {
            lines = readQueue(ctx);
        } catch (Exception e) {
            return 0;
        }
        if (lines.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        int okCount = 0;
        java.util.List<String> rest = new java.util.ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            long ts = jsonTime(line);

            // 太旧的直接丢，补上去只会让家人看到一堆陈年轨迹
            if (ts > 0 && now - ts > QTTL_MS) continue;

            if (okCount >= FLUSH_PER_ROUND) {
                rest.add(line); // 后面的下一轮再说
                continue;
            }
            if (okCount == 0 && !rest.isEmpty()) {
                // 前面已经失败过，别再试了
                rest.add(line);
                continue;
            }

            PostResult r = post(ctx, line, false);
            if (r.ok) {
                okCount++;
            } else {
                rest.add(line);
                // 网络性质的问题就别继续了；4xx 是我们的报文有问题，跳过这条继续
                if (r.code == 0 || r.code >= 500) {
                    for (int j = i + 1; j < lines.size(); j++) rest.add(lines.get(j));
                    break;
                }
            }
        }

        if (okCount > 0 || rest.size() != lines.size()) {
            writeQueue(ctx, rest);
            if (okCount > 0) Log.i(TAG, "补发 " + okCount + " 条，剩 " + rest.size() + " 条");
        }
        return okCount;
    }

    /** 从报文里取 time 字段，取不到返回 0 */
    private static long jsonTime(String line) {
        int i = line.indexOf("\"time\":");
        if (i < 0) return 0;
        int j = i + 7;
        int end = j;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(line.substring(j, end));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // ==================== HTTP ====================

    /** 在 URL 上补 live=0/1（保留原有查询参数，k= 令牌不能被破坏） */
    private static String withLiveFlag(String url, boolean live) {
        if (url == null || url.isEmpty()) return url;
        if (url.contains("live=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "live=" + (live ? "1" : "0");
    }

    /**
     * 执行完远程命令后补一次上报，把结果送回去。
     * 只延 1.5 秒是为了让中继先把上一条请求收好；再嵌套也不会死循环 ——
     * 中继取走一条命令就标 Taken，不会重复下发同一条。
     */
    /** 补发还没排空时，隔 2 秒再来一轮（每轮都确实少了报文，不会死循环） */
    private static void scheduleDrain(final Context app) {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                return;
            }
            try {
                uploadAsync(app, false, null);
            } catch (Exception e) {
                Log.w(TAG, "续补失败", e);
            }
        }, "report-drain").start();
    }

    private static void scheduleFollowUpReport(final Context app) {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                return;
            }
            try {
                uploadAsync(app, false, null);
            } catch (Exception e) {
                Log.w(TAG, "命令结果回传失败", e);
            }
        }, "cmd-result-report").start();
    }

    private static class PostResult {
        boolean ok;
        int code;
        String error = "";
        String body = "";
    }

    private static PostResult post(Context ctx, String json) {
        return post(ctx, json, true);
    }

    /**
     * @param live true 表示这是"当前这一刻"的上报，false 表示补发历史报文。
     *
     * 为什么要区分：中继会把待执行的远程命令捎在**任意一个**上报响应里。
     * 补发的报文虽然也走同一个地址，但它们的响应是没人看的 ——
     * 命令被捎到补发响应上就等于凭空丢了（实测就踩到：发了体检，命令被补发吞掉，
     * 队列里一直 pendingCmds=1 却永远执行不了）。
     * 所以补发请求明确标 live=0，中继只在 live=1 时才捎命令。
     */
    private static PostResult post(Context ctx, String json, boolean live) {
        PostResult res = new PostResult();
        HttpURLConnection conn = null;
        try {
            String raw = Prefs.getString(ctx, Prefs.REPORT_URL, "").trim();
            if (!live) raw = withLiveFlag(raw, false);
            URL url = new URL(raw);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            OutputStream out = conn.getOutputStream();
            out.write(json.getBytes("UTF-8"));
            out.flush();
            out.close();

            res.code = conn.getResponseCode();
            java.io.InputStream is = res.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                res.body = sb.toString();
            }
            res.ok = res.code >= 200 && res.code < 300;
            if (!res.ok) res.error = httpHint(res.code, res.body);
            Log.i(TAG, "上报返回 " + res.code);
        } catch (UnknownHostException e) {
            res.error = "域名解析不了（检查地址拼写；如果域名只有 IPv6，这台手机当前网络可能没有 IPv6）";
        } catch (SocketTimeoutException e) {
            res.error = "连接超时（网络慢，或被防火墙/反代拦了）";
        } catch (SSLException e) {
            res.error = "证书校验失败（自签证书，或证书链不完整）";
        } catch (java.net.MalformedURLException e) {
            res.error = "地址格式不对（要写全 https://域名:端口/路径）";
        } catch (Exception e) {
            String m = e.getMessage();
            res.error = "网络不通：" + e.getClass().getSimpleName() + (m == null ? "" : "（" + m + "）");
        } finally {
            if (conn != null) conn.disconnect();
        }
        return res;
    }

    private static String httpHint(int code, String body) {
        switch (code) {
            case 400: return "HTTP 400：请求格式被拒";
            case 401: return "HTTP 401：令牌不对（检查地址末尾的 ?k=）";
            case 403: return "HTTP 403：被拒绝（令牌不匹配）";
            case 404: return "HTTP 404：路径不对（地址应以 /api/report 结尾）";
            case 405: return "HTTP 405：方法不对（这里只收 POST）";
            case 502:
            case 503:
            case 504: return "HTTP " + code + "：反代连不上后端（检查 Lucky 的后端地址与超时）";
            default: return "HTTP " + code + (body == null || body.isEmpty() ? "" : "：" + shorten(body));
        }
    }

    private static String shorten(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    // ==================== 定时上报 ====================

    public static final String ACTION = "com.elder.launcher.REPORT_TICK";
    private static final int REQ = 9402;

    public static void schedule(Context ctx) {
        if (!enabled(ctx)) {
            cancel(ctx);
            return;
        }
        // 上报开着就把快通道也拉起来：家人点「催一次定位」能秒级下发
        if (FastLinkService.enabled(ctx)) FastLinkService.start(ctx);
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, ReportReceiver.class).setAction(ACTION);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, REQ, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);

            int min = intervalMinutes(ctx);
            // 首次 30 秒后就跑一次：填完地址不用干等一个周期
            am.cancel(pi);
            arm(am, pi, SystemClock.elapsedRealtime() + 30 * 1000L, min);
            Log.i(TAG, "主动上报已排期：唤醒型精确闹钟，每 " + min + " 分钟");
        } catch (Exception e) {
            Log.w(TAG, "上报排期失败", e);
        }
    }

    /**
     * 排下一次上报 —— 必须用"能唤醒设备"的闹钟。
     *
     * 以前是 setInexactRepeating(ELAPSED_REALTIME, ...)，那是**非唤醒**闹钟：
     * 手机深度睡眠时它根本不触发，只会等下次被别的事情唤醒时一起补。
     * 实测代价：夜里 00:06 之后连续 11 个小时一条上报都没有，
     * `dumpsys alarm` 里 REPORT_TICK 的 `type=3 wakeup=false` 就是铁证，
     * 家人端看到的就是"老人端失联"，其实手机和网络都好好的，只是睡着了。
     *
     * 换成 ELAPSED_REALTIME_WAKEUP + setExactAndAllowWhileIdle：
     *  - WAKEUP 会真的把设备叫醒
     *  - allow-while-idle 让它在 Doze 里也能触发（系统限制最快 9 分钟一次）
     * 精确闹钟不会自己重复，所以每次触发后重新排（见 ReportReceiver）。
     */
    private static void arm(android.app.AlarmManager am,
                            android.app.PendingIntent pi,
                            long triggerElapsed, int min) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi);
            } else {
                am.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi);
            }
        } catch (SecurityException se) {
            // 个别 ROM 不给精确闹钟，退回唤醒型不精确闹钟 —— 至少睡眠中还能被叫醒
            Log.w(TAG, "精确闹钟被拒，退回唤醒型", se);
            am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi);
        }
    }

    /** 每次触发后重新排下一次（精确闹钟不能 setRepeating） */
    public static void rescheduleAfterRun(Context ctx) {
        if (!enabled(ctx)) {
            cancel(ctx);
            return;
        }
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, ReportReceiver.class).setAction(ACTION);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, REQ, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            int min = intervalMinutes(ctx);
            arm(am, pi, SystemClock.elapsedRealtime() + min * 60 * 1000L, min);
        } catch (Exception e) {
            Log.w(TAG, "重排上报闹钟失败", e);
        }
    }

    public static void cancel(Context ctx) {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, ReportReceiver.class).setAction(ACTION);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    ctx, REQ, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
        } catch (Exception ignored) {
        }
    }

    /** 每 N 分钟的定时器 */
    public static class ReportReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!enabled(context)) return;
            // 先把下一次排上：万一这次上报抛异常/被系统掐掉，闹钟也不会就此断掉。
            // 精确闹钟是一次性的，漏了这一步就再也不会上报了。
            rescheduleAfterRun(context);
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    // 每次心跳都确认快通道还活着：华为/荣耀会把常驻服务悄悄收掉，
                    // 服务自己 START_STICKY 未必能起来，这里是最可靠的兜底点。
                    if (FastLinkService.enabled(app)) FastLinkService.start(app);
                    if (!LocationHelper.hasNetwork(app)) return;

                    // 位置太旧就现取一次。正常情况下 TrackReceiver 每 10 分钟会刷新，
                    // 这里留 2 分钟余量；只有轨迹采样被关掉时才会走到这条分支。
                    long age = System.currentTimeMillis() - Prefs.getLong(app, KEY_LAST_POS_T, 0);
                    long stale = (intervalMinutes(app) + 2) * 60 * 1000L;
                    if (age > stale) {
                        LocationHelper.Result r = LocationHelper.getOnce(app, 8000);
                        if (r != null && r.ok()) {
                            rememberPos(app, r.lat, r.lng, r.accuracy);
                        }
                    }

                    UsageStore.refresh(app);
                    uploadAsync(app, false, null);
                } catch (Exception e) {
                    Log.w(TAG, "定时上报失败", e);
                } finally {
                    pending.finish();
                }
            }, "report-tick").start();
        }
    }
}
