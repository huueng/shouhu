package com.elder.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;

/**
 * DDNS 更新器（Dynv6 / Cloudflare / 自定义 Webhook）
 *
 * 为什么需要它：老人机用移动数据时公网 IP 会变，家人端记不住一长串地址。
 * 把变化的地址同步到固定域名，家人端只填域名就行。
 *
 * 三种服务商的差别：
 * - Dynv6：一条 GET 带 token 参数，最省事
 * - Cloudflare：Bearer Token + 先查 zone id 再查 record id，最后 PATCH
 * - 自定义：由用户自己填 URL 模板（{ip} / {ip4} / {hostname} 会被替换），
 *   任何小众服务商或自建脚本都能接进来
 *
 * ⚠️ 用 Cloudflare 时记录必须是「灰云（DNS only）」。
 * 橙色云只转发 80/443/8443 等固定端口，我们用的 8899 会被掐断，
 * 而且橙云会把 AAAA 换成 CF 自己的地址，家人端根本连不到老人机。
 */
public final class DdnsUpdater {

    private static final String TAG = "ElderDdns";
    private static final int TIMEOUT = 12000;

    public static final String PROVIDER_DYNV6 = "dynv6";
    public static final String PROVIDER_CLOUDFLARE = "cloudflare";
    public static final String PROVIDER_CUSTOM = "custom";

    private static final String DYNV6_API = "https://dynv6.com/api/update";
    private static final String CF_API = "https://api.cloudflare.com/client/v4";

    /**
     * 默认值：故意留空。
     * 程序里不内置任何个人信息（域名、token、密钥），全部由家人在设置页手填或扫码带出。
     * 这样做的好处：谁拿到 APK 都不会顺手把老人机指向别人的域名。
     */
    public static final String DEFAULT_HOST = "";
    public static final String DEFAULT_TOKEN = "";

    public interface Callback {
        void onResult(boolean ok, String ip, String message);
    }

    private DdnsUpdater() {
    }

    // ===== 配置读写 =====

    public static String provider(Context ctx) {
        String v = Prefs.getString(ctx, "ddns_provider", PROVIDER_DYNV6);
        return v == null || v.isEmpty() ? PROVIDER_DYNV6 : v;
    }

    public static void setProvider(Context ctx, String provider) {
        Prefs.putString(ctx, "ddns_provider", provider);
    }

    public static String hostname(Context ctx) {
        String v = Prefs.getString(ctx, "ddns_host", DEFAULT_HOST);
        return v == null || v.trim().isEmpty() ? DEFAULT_HOST : v.trim();
    }

    public static String token(Context ctx) {
        String v = Prefs.getString(ctx, "ddns_token", DEFAULT_TOKEN);
        return v == null || v.trim().isEmpty() ? DEFAULT_TOKEN : v.trim();
    }

    /** Cloudflare 的区域名（可为空，空则按域名后缀自动探测） */
    public static String zone(Context ctx) {
        String v = Prefs.getString(ctx, "ddns_zone", "");
        return v == null ? "" : v.trim();
    }

    /** 自定义 Webhook 的 URL 模板，支持 {ip} {ip4} {hostname} */
    public static String customUrl(Context ctx) {
        String v = Prefs.getString(ctx, "ddns_custom_url", "");
        return v == null ? "" : v.trim();
    }

    /** 自定义请求头，形如 "Authorization: Bearer xxx"（可空） */
    public static String customHeader(Context ctx) {
        String v = Prefs.getString(ctx, "ddns_custom_header", "");
        return v == null ? "" : v.trim();
    }

    /** 是否同时同步 IPv4（A 记录） */
    public static boolean syncIpv4(Context ctx) {
        return Prefs.getBool(ctx, "ddns_ipv4", false);
    }

    public static boolean enabled(Context ctx) {
        return Prefs.getBool(ctx, "ddns_enabled", false);
    }

    public static String lastIp(Context ctx) {
        return Prefs.getString(ctx, "ddns_last_ip", "");
    }

    public static String lastIp4(Context ctx) {
        return Prefs.getString(ctx, "ddns_last_ip4", "");
    }

    public static String lastMessage(Context ctx) {
        return Prefs.getString(ctx, "ddns_last_msg", "");
    }

    public static long lastTime(Context ctx) {
        return Prefs.getLong(ctx, "ddns_last_time", 0L);
    }

    public static String providerLabel(Context ctx) {
        String p = provider(ctx);
        if (PROVIDER_CLOUDFLARE.equals(p)) return "Cloudflare";
        if (PROVIDER_CUSTOM.equals(p)) return "自定义 Webhook";
        return "Dynv6";
    }

    /** 上一次是否真的成功了（决定二维码里用域名还是 IP） */
    public static boolean lastOk(Context ctx) {
        String msg = lastMessage(ctx);
        return msg != null && msg.startsWith("成功");
    }

    // ===== 地址选择 =====

    /**
     * 取本机当前可用于外网访问的 IPv6。
     * 手机一般有两个公网地址：临时的（隐私扩展，几小时换一次）和基于 MAC 的稳定地址。
     * DDNS 必须记稳定的那个，否则域名会跟着临时地址反复漂。
     */
    public static String pickIpv6() {
        String best = null;
        int bestScore = -1;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                if (skipIf(nif.getName())) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet6Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String host = clean(addr.getHostAddress());
                    if (host.startsWith("fd") || host.startsWith("fc")) continue; // ULA 内网

                    char c = host.charAt(0);
                    boolean publicRange = (c == '2' || c == '3');
                    boolean stable = host.contains("ff:fe");
                    int score = (publicRange ? 10 : 0) + (stable ? 5 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = host;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "枚举网卡失败", e);
        }
        return best;
    }

    /**
     * 取本机 IPv4 —— 只有真的是公网地址才有意义。
     * 手机绝大多数情况是 192.168 / 10. / 172.16-31 / 100.64（运营商 NAT），
     * 这些同步上去家人也用不了，所以直接返回 null 并说明原因。
     */
    public static String pickPublicIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                if (skipIf(nif.getName())) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String host = clean(addr.getHostAddress());
                    if (isPrivateIpv4(host)) continue;
                    return host;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "枚举 IPv4 失败", e);
        }
        return null;
    }

    /** 只跳过 VPN 虚拟网卡；rmnet（移动数据）和 wlan 都要保留，公网 IPv6 就在它们上面 */
    private static boolean skipIf(String name) {
        return name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp");
    }

    private static String clean(String host) {
        int pct = host.indexOf('%');
        return pct > 0 ? host.substring(0, pct) : host;
    }

    private static boolean isPrivateIpv4(String ip) {
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")) return true;
        if (ip.startsWith("169.254.")) return true;
        if (ip.startsWith("100.")) return true;                    // 运营商级 NAT
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    // ===== 更新 =====

    /** 同步执行一次更新（调用方自己开线程）。force=true 时即使 IP 没变也发请求 */
    public static boolean updateNow(Context ctx, boolean force, Callback cb) {
        String ip6 = pickIpv6();
        String ip4 = syncIpv4(ctx) ? pickPublicIpv4() : null;
        if ((ip6 == null || ip6.isEmpty()) && ip4 == null) {
            String msg = syncIpv4(ctx)
                    ? "没有可用的公网地址（IPv6 未就绪，IPv4 也是内网/NAT 地址）"
                    : "没有公网 IPv6（可能没开移动数据，或当前 WiFi 不下发 IPv6）";
            save(ctx, "", "", msg);
            notify(cb, false, "", msg);
            return false;
        }
        boolean sameV6 = ip6 != null && ip6.equals(lastIp(ctx));
        boolean sameV4 = !syncIpv4(ctx) || (ip4 != null && ip4.equals(lastIp4(ctx)));
        if (!force && sameV6 && sameV4 && lastOk(ctx)) {
            notify(cb, true, ip6 == null ? ip4 : ip6, "地址未变，跳过请求");
            return true;
        }

        String p = provider(ctx);
        // 程序里不再内置任何个人信息，没填就直接说清楚，别抛一堆服务商报错
        if (!PROVIDER_CUSTOM.equals(p)) {
            if (hostname(ctx).isEmpty()) {
                String msg = "还没填域名：设置 → 远程访问 → 配置域名同步";
                save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
                notify(cb, false, ip6, msg);
                return false;
            }
            if (token(ctx).isEmpty()) {
                String msg = "还没填 Token：设置 → 远程访问 → 配置域名同步";
                save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
                notify(cb, false, ip6, msg);
                return false;
            }
        }
        try {
            if (PROVIDER_CLOUDFLARE.equals(p)) return updateCloudflare(ctx, ip6, ip4, cb);
            if (PROVIDER_CUSTOM.equals(p)) return updateCustom(ctx, ip6, ip4, cb);
            return updateDynv6(ctx, ip6, ip4, cb);
        } catch (Exception e) {
            String msg = "失败：" + e.getMessage();
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            Log.w(TAG, "更新异常", e);
            notify(cb, false, ip6, msg);
            return false;
        }
    }

    private static boolean updateDynv6(Context ctx, String ip6, String ip4, Callback cb) {
        HttpURLConnection conn = null;
        try {
            StringBuilder url = new StringBuilder(DYNV6_API)
                    .append("?hostname=").append(URLEncoder.encode(hostname(ctx), "UTF-8"))
                    .append("&token=").append(URLEncoder.encode(token(ctx), "UTF-8"));
            if (ip6 != null) url.append("&ipv6=").append(URLEncoder.encode(ip6, "UTF-8"));
            if (ip4 != null) url.append("&ipv4=").append(URLEncoder.encode(ip4, "UTF-8"));

            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String body = readAll(conn, code);
            boolean ok = body.toLowerCase().contains("addresses updated")
                    || body.toLowerCase().contains("unchanged");
            String msg = (ok ? "成功：" : "失败：") + (body.isEmpty() ? ("HTTP " + code) : body);
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            Log.i(TAG, "Dynv6 " + hostname(ctx) + " -> " + ip6 + "/" + ip4 + "：" + msg);
            notify(cb, ok, ip6, msg);
            return ok;
        } catch (Exception e) {
            String msg = "失败：" + e.getMessage();
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            notify(cb, false, ip6, msg);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 自定义 Webhook：URL 模板替换 {ip} {ip4} {hostname} 后发 GET。
     * 想用 POST 就在模板后面不行——这里统一 GET，够覆盖绝大多数动态 DNS 服务的接口。
     */
    private static boolean updateCustom(Context ctx, String ip6, String ip4, Callback cb) {
        String tpl = customUrl(ctx);
        if (tpl.isEmpty()) {
            String msg = "失败：还没填自定义 URL";
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            notify(cb, false, ip6, msg);
            return false;
        }
        HttpURLConnection conn = null;
        try {
            String url = tpl
                    .replace("{ip}", ip6 == null ? "" : ip6)
                    .replace("{ip4}", ip4 == null ? "" : ip4)
                    .replace("{hostname}", hostname(ctx));
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("GET");
            String header = customHeader(ctx);
            if (!header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    conn.setRequestProperty(header.substring(0, colon).trim(),
                            header.substring(colon + 1).trim());
                }
            }
            int code = conn.getResponseCode();
            String body = readAll(conn, code);
            boolean ok = code >= 200 && code < 300;
            String shortBody = body.length() > 120 ? body.substring(0, 120) + "…" : body;
            String msg = (ok ? "成功：HTTP " + code : "失败：HTTP " + code)
                    + (shortBody.isEmpty() ? "" : " " + shortBody);
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            Log.i(TAG, "自定义 " + url + " -> " + msg);
            notify(cb, ok, ip6, msg);
            return ok;
        } catch (Exception e) {
            String msg = "失败：" + e.getMessage();
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            notify(cb, false, ip6, msg);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Cloudflare：查 zone → 查记录 → 有就 PATCH、没有就新建。
     * 一律写死 proxied=false（灰云），否则 8899 端口会被 CF 代理掐掉。
     */
    private static boolean updateCloudflare(Context ctx, String ip6, String ip4, Callback cb) {
        String host = hostname(ctx);
        String tokenValue = token(ctx);
        try {
            String zoneId = findZoneId(ctx, host, tokenValue);
            if (zoneId == null) {
                String msg = "失败：找不到 Cloudflare 区域，请在配置里填写区域名（如 example.us.kg）";
                save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
                notify(cb, false, ip6, msg);
                return false;
            }

            StringBuilder detail = new StringBuilder();
            boolean allOk = true;

            if (ip6 != null) {
                boolean ok = upsertRecord(zoneId, host, "AAAA", ip6, tokenValue);
                allOk &= ok;
                detail.append(ok ? "AAAA 已更新" : "AAAA 失败");
            }
            if (ip4 != null) {
                boolean ok = upsertRecord(zoneId, host, "A", ip4, tokenValue);
                allOk &= ok;
                detail.append(detail.length() > 0 ? "；" : "").append(ok ? "A 已更新" : "A 失败");
            }

            String msg = (allOk ? "成功：Cloudflare " : "部分失败：") + detail + "（灰云）";
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            Log.i(TAG, "Cloudflare " + host + " -> " + msg);
            notify(cb, allOk, ip6, msg);
            return allOk;
        } catch (Exception e) {
            String msg = "失败：" + e.getMessage();
            save(ctx, ip6 == null ? "" : ip6, ip4 == null ? "" : ip4, msg);
            Log.w(TAG, "Cloudflare 更新异常", e);
            notify(cb, false, ip6, msg);
            return false;
        }
    }

    private static boolean upsertRecord(String zoneId, String host, String type, String content,
                                        String tokenValue) throws Exception {
        String recordId = findRecordId(zoneId, host, type, tokenValue);
        JSONObject body = new JSONObject();
        body.put("content", content);
        body.put("proxied", false);
        String resp;
        if (recordId == null) {
            body.put("type", type);
            body.put("name", host);
            body.put("ttl", 120);
            resp = request(CF_API + "/zones/" + zoneId + "/dns_records", "POST", tokenValue,
                    body.toString());
        } else {
            resp = request(CF_API + "/zones/" + zoneId + "/dns_records/" + recordId, "PATCH",
                    tokenValue, body.toString());
        }
        return new JSONObject(resp).optBoolean("success", false);
    }

    /** 依次用「设置的区域名 → 域名的各级后缀」去问 Cloudflare，第一个命中的就是 zone */
    private static String findZoneId(Context ctx, String host, String tokenValue) throws Exception {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        String configured = zone(ctx);
        if (!configured.isEmpty()) candidates.add(configured);

        String[] parts = host.split("\\.");
        for (int i = 0; i <= parts.length - 2; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < parts.length; j++) {
                if (sb.length() > 0) sb.append('.');
                sb.append(parts[j]);
            }
            candidates.add(sb.toString());
        }

        for (String zoneName : candidates) {
            String resp = request(CF_API + "/zones?name=" + URLEncoder.encode(zoneName, "UTF-8"),
                    "GET", tokenValue, null);
            JSONObject json = new JSONObject(resp);
            if (!json.optBoolean("success", false)) {
                String err = cfError(json);
                if (err.contains("Invalid") || err.contains("Unauthorized")
                        || err.contains("permission") || err.contains("format")) {
                    throw new IllegalStateException("Cloudflare 鉴权失败：" + err);
                }
                continue;
            }
            JSONArray arr = json.optJSONArray("result");
            if (arr != null && arr.length() > 0) {
                return arr.getJSONObject(0).optString("id", null);
            }
        }
        return null;
    }

    private static String findRecordId(String zoneId, String host, String type, String tokenValue)
            throws Exception {
        String url = CF_API + "/zones/" + zoneId + "/dns_records?type=" + type + "&name="
                + URLEncoder.encode(host, "UTF-8");
        JSONObject json = new JSONObject(request(url, "GET", tokenValue, null));
        if (!json.optBoolean("success", false)) return null;
        JSONArray arr = json.optJSONArray("result");
        if (arr == null || arr.length() == 0) return null;
        return arr.getJSONObject(0).optString("id", null);
    }

    private static String cfError(JSONObject json) {
        JSONArray errors = json.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject e = errors.optJSONObject(0);
            if (e != null) return e.optString("message", "未知错误");
        }
        return "未知错误";
    }

    private static String request(String url, String method, String tokenValue, String body)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + tokenValue);
            conn.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            return readAll(conn, code);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(HttpURLConnection conn, int code) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString().trim();
    }

    /** 异步版本，方便界面调用 */
    public static void updateAsync(final Context ctx, final boolean force, final Callback cb) {
        new Thread(() -> updateNow(ctx, force, cb)).start();
    }

    private static void save(Context ctx, String ip, String ip4, String msg) {
        Prefs.get(ctx).edit()
                .putString("ddns_last_ip", ip == null ? "" : ip)
                .putString("ddns_last_ip4", ip4 == null ? "" : ip4)
                .putString("ddns_last_msg", msg == null ? "" : msg)
                .putLong("ddns_last_time", System.currentTimeMillis())
                .apply();
    }

    private static void notify(Callback cb, boolean ok, String ip, String msg) {
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> cb.onResult(ok, ip, msg));
    }
}
