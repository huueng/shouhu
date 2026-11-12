package com.elder.launcher;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;

/**
 * 给电脑端（ADB）用的管理通道
 *
 * 为什么需要它：Device Owner 一旦下发 DISALLOW_INSTALL_UNKNOWN_SOURCES，
 * `adb install` 会被系统直接拒掉（INSTALL_FAILED_USER_RESTRICTED），
 * 一键部署就卡在"装不上"这一步。而这条限制**只能由 Device Owner 自己清除**，
 * 外部 adb 没有对应命令。所以留一个只有 adb 方便调用的广播口子：
 *
 *   adb shell am broadcast -a com.elder.launcher.ADMIN --es cmd allow_install \
 *       --es k elder-adb --include-stopped-packages
 *
 * 同时把对应的 Prefs 开关关掉 —— 否则界面 onResume 时 enableAllRestrictions()
 * 又会把限制加回去，装完立刻又被锁。
 *
 * 安全说明：这个 receiver 只能"放宽/收紧本应用自己设的限制"，不能读位置、不能发短信。
 * 而且限制开着时本来就装不进新应用，能发这个广播的只有已经拿到 adb 的人。
 * 仍然加了一个 k 校验，避免别的应用随手乱发。
 */
public class AdminCommandReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.elder.launcher.ADMIN";
    public static final String TAG = "ElderAdb";

    /** 口令：只为防误触，不是密钥（adb 能读到 APK 里的一切） */
    private static final String KEY = "elder-adb";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        String k = intent.getStringExtra("k");
        String cmd = intent.getStringExtra("cmd");
        if (!KEY.equals(k)) {
            Log.w(TAG, "口令不对，忽略：" + cmd);
            reply("bad-key");
            return;
        }
        if (cmd == null) cmd = "";
        String arg = intent.getStringExtra("arg");
        if (arg == null) arg = "";

        SharedPreferences p = Prefs.get(ctx);
        DevicePolicyManager dpm = ElderDeviceAdminReceiver.getDPM(ctx);
        boolean isDo = ElderDeviceAdminReceiver.isDeviceOwner(ctx);
        android.content.ComponentName admin = ElderDeviceAdminReceiver.getComponentName(ctx);

        try {
            switch (cmd) {
                case "allow_install": {
                    // 临时放开"装应用"，装完由电脑端调 restore_install 收回
                    p.edit().putBoolean(Prefs.BLOCK_INSTALL, false).apply();
                    if (isDo && dpm != null) {
                        dpm.clearUserRestriction(admin,
                                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
                    }
                    Log.i(TAG, "已放开安装限制（BLOCK_INSTALL=false）");
                    reply("install-allowed");
                    break;
                }
                case "restore_install": {
                    p.edit().putBoolean(Prefs.BLOCK_INSTALL, true).apply();
                    if (isDo && dpm != null) {
                        dpm.addUserRestriction(admin,
                                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
                    }
                    Log.i(TAG, "已恢复安装限制");
                    reply("install-restored");
                    break;
                }
                case "allow_uninstall": {
                    p.edit().putBoolean(Prefs.BLOCK_UNINSTALL, false).apply();
                    if (isDo && dpm != null) {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
                        try {
                            dpm.setUninstallBlocked(admin, ctx.getPackageName(), false);
                        } catch (Exception ignored) {
                        }
                    }
                    Log.i(TAG, "已放开卸载限制（含本应用防卸载）");
                    reply("uninstall-allowed");
                    break;
                }
                case "restore_uninstall": {
                    p.edit().putBoolean(Prefs.BLOCK_UNINSTALL, true).apply();
                    if (isDo && dpm != null) {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
                        try {
                            dpm.setUninstallBlocked(admin, ctx.getPackageName(), true);
                        } catch (Exception ignored) {
                        }
                    }
                    Log.i(TAG, "已恢复卸载限制");
                    reply("uninstall-restored");
                    break;
                }
                case "unlock_all": {
                    // 卸载前用：放开安装/卸载、取消状态栏屏蔽，但**不动 Device Owner**
                    // （一旦移除 DO 就必须恢复出厂才能再激活，不能由一条 adb 命令随手做掉）
                    p.edit()
                            .putBoolean(Prefs.BLOCK_INSTALL, false)
                            .putBoolean(Prefs.BLOCK_UNINSTALL, false)
                            .putBoolean(Prefs.LOCK_TASK, false)
                            .apply();
                    if (isDo && dpm != null) {
                        dpm.clearUserRestriction(admin,
                                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
                        try {
                            dpm.setUninstallBlocked(admin, ctx.getPackageName(), false);
                        } catch (Exception ignored) {
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            try {
                                dpm.setStatusBarDisabled(admin, false);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    Log.i(TAG, "已解除安装/卸载/状态栏限制（Device Owner 保留）");
                    reply("unlocked");
                    break;
                }
                case "clear_owner": {
                    // 只有 Device Owner 自己能清掉自己。
                    // 外部 adb 的 `dpm remove-active-admin` 在华为/荣耀上经常直接失败
                    // （提示 admin is device owner / 需要用户确认），这也是"解锁解不开"的一个原因。
                    if (!isDo) {
                        reply("not-owner");
                        break;
                    }
                    dpm.clearDeviceOwnerApp(ctx.getPackageName());
                    Log.i(TAG, "Device Owner 已清除");
                    reply("owner-cleared");
                    break;
                }
                case "status": {
                    String s = "do=" + isDo
                            + " blockInstall=" + p.getBoolean(Prefs.BLOCK_INSTALL, true)
                            + " blockUninstall=" + p.getBoolean(Prefs.BLOCK_UNINSTALL, true)
                            + " lockTask=" + p.getBoolean(Prefs.LOCK_TASK, false)
                            + " pending=" + ReportUploader.pendingCount(ctx)
                            + " lastReport=" + oneLine(Prefs.getString(ctx, "report_last", ""));
                    Log.i(TAG, "状态 " + s);
                    reply(s);
                    break;
                }
                case "fastlink": {
                    boolean on = !"off".equalsIgnoreCase(arg.trim());
                    FastLinkService.setEnabled(ctx, on);
                    reply("fastlink=" + (on ? "on" : "off"));
                    break;
                }
                case "intranet": {
                    // 远程切模式：换机、改用途时不用跑去点开关
                    boolean on = !"off".equalsIgnoreCase(arg.trim());
                    IntranetMode.setOn(ctx, on);
                    reply("intranet=" + (on ? "on" : "off"));
                    break;
                }
                case "setup": {
                    // 重跑一次初始设置（警告页 → 选形态 → 向导）。
                    // 换用途（老人主手机 ↔ 只看视频机）时比教人点半天开关靠谱。
                    p.edit().putBoolean(Prefs.MODE_CHOSEN, false).apply();
                    // 直接起 ModeChooseActivity：WarningActivity 是图标入口且 noHistory，
                    // 从广播拉起会被 HOME（本应用就是桌面）抢回焦点。选完照样进向导。
                    Intent i = new Intent(ctx, ModeChooseActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    ctx.startActivity(i);
                    reply("setup-started");
                    break;
                }
                case "qrdecode": {
                    // 给排障用：把二维码截图/照片传到手机上，直接问能不能解出来。
                    // 相机能不能打开、解码器还能不能用，一条命令分开验证。
                    reply("qr:" + qrDecodeFile(arg.trim()));
                    break;
                }
                case "info": {
                    // 装机排障一次看全：上报地址、开关、间隔、待补发
                    reply("info url=" + oneLine(Prefs.getString(ctx, Prefs.REPORT_URL, ""))
                            + " enabled=" + Prefs.getBool(ctx, Prefs.REPORT_ENABLED, false)
                            + " every=" + ReportUploader.intervalMinutes(ctx) + "min"
                            + " pending=" + ReportUploader.pendingCount(ctx)
                            + " intranet=" + IntranetMode.isOn(ctx)
                            + " fastlink=" + FastLinkService.enabled(ctx)
                            + " wifi=" + oneLine(IntranetMode.currentSsid(ctx)));
                    break;
                }
                case "seturl": {
                    // 电脑端一键部署直接写上报地址：IPv6 字面量太长，让老人/家人手打不现实
                    String u = arg.trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        reply("bad-url");
                        break;
                    }
                    Prefs.get(ctx).edit()
                            .putString(Prefs.REPORT_URL, u)
                            .putBoolean(Prefs.REPORT_ENABLED, true)
                            .apply();
                    ReportUploader.schedule(ctx);
                    Log.i(TAG, "上报地址已设置为 " + u);
                    reply("url-set " + oneLine(u));
                    break;
                }
                case "report": {
                    // 立刻上报一次并等结果：装机后验证「网络到底通不通」，一个命令就够，
                    // 不用连点进设置翻网络体检
                    reply("report:" + uploadNowBlocking(ctx));
                    break;
                }
                default:
                    Log.w(TAG, "未知命令：" + cmd);
                    reply("unknown");
            }
        } catch (Exception e) {
            Log.e(TAG, "执行 " + cmd + " 失败", e);
            reply("error:" + e.getClass().getSimpleName());
        }
    }

    /** 解一张图片里的二维码，成功回内容，失败回短原因（一行，别撑爆 broadcast） */
    private String qrDecodeFile(String path) {
        if (path == null || path.isEmpty()) return "no-path";
        android.graphics.Bitmap bmp = null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return "not-image";
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1600) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            bmp = android.graphics.BitmapFactory.decodeFile(path, o2);
            if (bmp == null) return "decode-null";
            String text = QrUtil.decode(bmp);
            return text == null || text.trim().isEmpty() ? "no-qr" : oneLine(text);
        } catch (Exception e) {
            return "err:" + e.getClass().getSimpleName();
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** 立即上报一次，最多等 20 秒拿结果 */
    private String uploadNowBlocking(final Context ctx) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] out = {"timeout"};
        ReportUploader.uploadAsync(ctx, true, (ok, msg) -> {
            out[0] = (ok ? "ok " : "fail ") + (msg == null ? "" : msg);
            latch.countDown();
        });
        try {
            if (!latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                return "timeout 20s（多半卡在连不上）";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        return out[0];
    }

    /** 回话走 broadcast 的 data 字段，换行会把 am 的输出切碎，统一压成一行 */
    private String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').replace('"', '\'').trim();
    }

    /** 让 am broadcast 的返回值里带一句话，电脑端好判断成败 */
    private void reply(String data) {
        try {
            setResultCode(1);
            setResultData(data);
        } catch (Exception ignored) {
        }
    }
}
