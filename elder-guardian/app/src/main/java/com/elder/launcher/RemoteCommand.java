package com.elder.launcher;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * 远程命令执行器
 *
 * 通道：老人端每 N 分钟主动上报一次 → 中继在 HTTP 响应里塞一条待执行命令
 *      → 这里执行 → 结果写 prefs → **下一次上报**把结果带回去。
 *
 * 为什么不用长连接/推送：老人机在内网、没有公网入口，反向连不进去；
 * 而上报本来就在跑，借它的响应捎一条命令是最省事、最抗断网的做法——
 * 断网期间命令留在中继队列里，网络恢复后第一次上报就能取到。
 *
 * 支持的命令：
 *   health          体检（返回 score/total/缺哪几项）
 *   restore         恢复配置（arg=快照 id；空/first=首次基线，manual=旧手动快照）
 *   save            存一份快照（arg=名字，空则用"手工 MM-dd HH:mm"）
 *   snapdel         删掉一份具名快照（arg=快照 id）
 *   restart         把守护桌面拉回前台
 *   unlock          解除锁定任务（不动 Device Owner）
 *   locate          立刻定一次位（家人按「查询位置」时用）
 *   volume          调声音：up / down / mute / unmute / set:0-100，
 *                   加 ring: 前缀则调铃声（ring:up / ring:set:60）
 *   fastlink        秒级通道开关：on / off（关掉后命令要等下次上报）
 *   snapshow        看某份快照里的配置摘要（只读，不改任何东西）
 */
public final class RemoteCommand {

    private static final String TAG = "ElderRemoteCmd";

    public static final String KEY_PENDING = "remote_cmd_pending";
    public static final String KEY_RESULT = "remote_cmd_result";

    private RemoteCommand() {
    }

    /** 收到中继下发的命令，先存下来（同一时刻只留一条，新的覆盖旧的） */
    public static void accept(Context ctx, JSONObject cmd) {
        if (cmd == null) return;
        Prefs.putString(ctx, KEY_PENDING, cmd.toString());
        Log.i(TAG, "收到远程命令：" + cmd.optString("cmd"));
    }

    public static boolean hasPending(Context ctx) {
        String s = Prefs.getString(ctx, KEY_PENDING, "");
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 执行待处理命令，返回结果 JSON 文本（失败返回 null）。
     * 执行完清掉 pending，结果留在 KEY_RESULT 等下一次上报带走。
     */
    public static String runPending(Context ctx) {
        String raw = Prefs.getString(ctx, KEY_PENDING, "");
        if (raw == null || raw.trim().isEmpty()) return null;

        JSONObject cmd;
        try {
            cmd = new JSONObject(raw);
        } catch (Exception e) {
            Prefs.putString(ctx, KEY_PENDING, "");
            return null;
        }

        String id = cmd.optString("id", "");
        String name = cmd.optString("cmd", "");
        String arg = cmd.optString("arg", "");

        Res res = exec(ctx, name, arg);
        boolean ok = res.ok;
        String msg = res.msg;

        Prefs.putString(ctx, KEY_PENDING, "");

        String result;
        try {
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("cmd", name);
            r.put("ok", ok);
            r.put("msg", msg);
            r.put("at", System.currentTimeMillis());
            result = r.toString();
            Prefs.putString(ctx, KEY_RESULT, result);
            Log.i(TAG, "命令完成 " + name + " ok=" + ok + "：" + msg);
        } catch (Exception e) {
            return null;
        }
        return result;
    }

    /** 上一次执行结果（给上报用），没有返回 null */
    public static JSONObject lastResult(Context ctx) {
        String raw = Prefs.getString(ctx, KEY_RESULT, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 结果是否已经成功送出去了（送出去后下次不再重复带，避免家人端一直看到同一条） */
    public static void markResultSent(Context ctx) {
        Prefs.putString(ctx, KEY_RESULT, "");
    }

    /** 一条命令的执行结果 */
    public static final class Res {
        public final boolean ok;
        public final String msg;

        Res(boolean ok, String msg) {
            this.ok = ok;
            this.msg = msg;
        }
    }

    /**
     * 执行一条命令，不碰 pending / result 存储。
     *
     * 两个入口都走这里：中继下发（runPending）和直连 HTTP（LocateServer 的 /cmd）。
     * 直连模式以前只能看点位，音量、快照都得跑去老人机上操作；把执行逻辑抽出来，
     * 两条通道就天然一致，不会出现"中继能调音量、直连不能"这种半截功能。
     */
    public static Res exec(Context ctx, String name, String arg) {
        boolean ok = false;
        String msg;
        try {
            switch (name) {
                case "health": {
                    JSONObject h = HealthCheck.build(ctx);
                    ok = h.optBoolean("ok", false);
                    msg = h.optString("summary", "");
                    break;
                }
                case "restore": {
                    String snapId = arg == null ? "" : arg.trim();
                    // 上报地址不能跟着历史快照回退：快照是几周前存的，
                    // 里面的令牌早换了，恢复完手机当场失联，家人还以为"恢复坏了"。
                    String url = Prefs.getString(ctx, Prefs.REPORT_URL, "");
                    boolean repOn = Prefs.getBool(ctx, Prefs.REPORT_ENABLED, false);
                    int n = SnapshotStore.restoreById(ctx, snapId);
                    if (n > 0) {
                        if (url != null && !url.trim().isEmpty()) {
                            Prefs.get(ctx).edit()
                                    .putString(Prefs.REPORT_URL, url)
                                    .putBoolean(Prefs.REPORT_ENABLED, repOn)
                                    .apply();
                            ReportUploader.schedule(ctx);
                        }
                        ok = true;
                        msg = "已恢复到「" + SnapshotStore.nameOf(ctx, snapId) + "」，"
                                + n + " 项配置（上报地址保持不变）";
                        // 恢复后把桌面重启一下，让新配置立刻生效
                        restartLauncher(ctx);
                    } else {
                        msg = "没有这份快照（可能已被删掉）";
                    }
                    break;
                }
                case "save": {
                    String nm = arg == null ? "" : arg.trim();
                    String snapId = SnapshotStore.saveNamed(ctx, nm);
                    ok = snapId != null;
                    msg = ok ? ("已存快照「" + SnapshotStore.nameOf(ctx, snapId) + "」")
                            : "保存失败（存储不可写？）";
                    break;
                }
                case "snapdel": {
                    String snapId = arg == null ? "" : arg.trim();
                    boolean done = SnapshotStore.remove(ctx, snapId);
                    ok = done;
                    msg = done ? "已删除这份快照" : "删不掉（首次基线不给删，或者已经没了）";
                    break;
                }
                case "restart": {
                    ok = restartLauncher(ctx);
                    msg = ok ? "已把守护桌面拉回前台" : "拉起失败（系统拦了后台启动）";
                    break;
                }
                case "locate": {
                    // 家人按「查询位置」：马上定一次，结果由调用方立刻回传
                    LocationHelper.Result lr = LocationHelper.getOnce(ctx, 15000);
                    if (lr != null && (lr.lat != 0 || lr.lng != 0)) {
                        ReportUploader.rememberPos(ctx, lr.lat, lr.lng, lr.accuracy);
                        ok = true;
                        msg = "刚定的新位置，精度 " + Math.round(lr.accuracy) + " 米";
                    } else {
                        msg = "这次没定到（室内/GPS 关着），先报上次的位置";
                    }
                    break;
                }
                case "volume": {
                    String r = applyVolume(ctx, arg);
                    ok = r.startsWith("已");
                    msg = r;
                    break;
                }
                case "fastlink": {
                    boolean on = !"off".equalsIgnoreCase(arg.trim());
                    FastLinkService.setEnabled(ctx, on);
                    ok = true;
                    msg = on
                            ? "秒级通道已开启（家人点命令几秒内就到）"
                            : "秒级通道已关闭（命令要等下次上报才执行）";
                    break;
                }
                case "snapshow": {
                    String text = SnapshotStore.summaryText(ctx, arg);
                    ok = text != null;
                    msg = ok ? text : "没有这份快照（可能已经被删了）";
                    break;
                }
                case "unlock": {
                    Prefs.putBool(ctx, Prefs.LOCK_TASK, false);
                    ok = restartLauncher(ctx);
                    msg = ok ? "已关闭锁定任务并重启桌面（Device Owner 保留）"
                            : "已关闭锁定任务，但桌面没拉起来，等下次手动打开";
                    break;
                }
                default:
                    msg = "不认识的命令：" + name;
            }
        } catch (Exception e) {
            Log.w(TAG, "执行命令失败：" + name, e);
            msg = "执行失败：" + e.getClass().getSimpleName();
        }
        return new Res(ok, msg);
    }

    /**
     * 远程调音量。
     *
     * 老人最常遇到的两件事：看视频听不见（媒体音量被按到 0）、接不到电话（铃声音量 0）。
     * 所以默认调媒体音量，加 ring: 前缀调铃声。全部走系统的 setStreamVolume，
     * 不碰勿扰白名单，也不去改系统设置——权限不够时明确告诉家人，不假装成功。
     */
    private static String applyVolume(Context ctx, String rawArg) {
        android.media.AudioManager am = (android.media.AudioManager)
                ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "拿不到音频服务，改不了音量";

        String a = rawArg == null ? "" : rawArg.trim().toLowerCase(java.util.Locale.ROOT);
        int stream = android.media.AudioManager.STREAM_MUSIC;
        if (a.startsWith("ring:")) {
            stream = android.media.AudioManager.STREAM_RING;
            a = a.substring(5).trim();
        }
        String label = (stream == android.media.AudioManager.STREAM_RING) ? "铃声" : "媒体";

        try {
            int max = am.getStreamMaxVolume(stream);
            int cur = am.getStreamVolume(stream);
            int to;
            if (a.equals("mute") || a.equals("off") || a.equals("0")) {
                to = 0;
            } else if (a.equals("unmute") || a.equals("on")) {
                to = Math.max(1, max / 2);
            } else if (a.startsWith("set:")) {
                int pct = Integer.parseInt(a.substring(4).trim());
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
                to = Math.round(max * pct / 100f);
            } else if (a.equals("up") || a.equals("down") || a.isEmpty()) {
                int step = Math.max(1, Math.round(max / 15f));
                to = a.equals("down") ? cur - step : cur + step;
            } else {
                return "不认识的音量参数：" + a;
            }
            if (to < 0) to = 0;
            if (to > max) to = max;
            am.setStreamVolume(stream, to, 0);
            String extra = (to == 0) ? "（静音了）" : "";
            return "已设置" + label + "音量 " + cur + " → " + to + "/" + max + extra;
        } catch (SecurityException se) {
            return "系统不让改音量（缺权限或被系统策略限制）";
        } catch (Exception e) {
            return "改音量失败：" + e.getClass().getSimpleName();
        }
    }

    private static boolean restartLauncher(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainLauncherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "拉起桌面失败", e);
            return false;
        }
    }
}
