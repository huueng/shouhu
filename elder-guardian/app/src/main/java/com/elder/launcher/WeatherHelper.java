package com.elder.launcher;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 天气获取（Open-Meteo 免费接口，无需 Key）
 * 定位优先用系统最后已知位置，失败则用 IP 粗略定位
 */
public final class WeatherHelper {

    private static final String TAG = "WeatherHelper";
    private static final int TIMEOUT = 8000;

    public interface Callback {
        void onResult(String text);
    }

    /** 主界面头部用：main = 「⛅ 19°」，desc = 「多云」 */
    public interface HeaderCallback {
        void onResult(String main, String desc);
    }

    /** 城市名解析结果 */
    public interface PlaceCallback {
        void onResult(String label, double lat, double lon, String error);
    }

    private WeatherHelper() {
    }

    // ===== 手动指定天气地点 =====

    /** 当前设定的地点名；未设定返回空串（表示跟随定位） */
    public static String placeName(Context ctx) {
        return ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                .getString("weather_place_name", "");
    }

    public static void setPlace(Context ctx, String label, double lat, double lon) {
        ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                .putString("weather_place_name", label)
                .putString("weather_place_ll", lat + "," + lon)
                .apply();
    }

    public static void clearPlace(Context ctx) {
        ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                .remove("weather_place_name")
                .remove("weather_place_ll")
                .apply();
    }

    private static double[] fixedPlace(Context ctx) {
        String ll = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                .getString("weather_place_ll", "");
        if (ll == null || ll.isEmpty()) return null;
        String[] parts = ll.split(",");
        if (parts.length != 2) return null;
        try {
            return new double[]{Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用 Open-Meteo 的免费地理编码接口把城市名转成经纬度（不需要 Key）
     * 支持"北京""杭州""Guangzhou"这类输入
     */
    public static void searchPlaceAsync(final Context ctx, final String query,
                                        final PlaceCallback cb) {
        new Thread(() -> {
            String label = null;
            double lat = 0, lon = 0;
            String error = null;
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="
                        + java.net.URLEncoder.encode(query, "UTF-8");
                JSONObject o = new JSONObject(httpGet(url));
                if (!o.has("results") || o.getJSONArray("results").length() == 0) {
                    error = "没找到这个地名，换个说法试试（例如「杭州」而不是「杭州市西湖区」）";
                } else {
                    JSONObject r = o.getJSONArray("results").getJSONObject(0);
                    lat = r.getDouble("latitude");
                    lon = r.getDouble("longitude");
                    String name = r.optString("name", query);
                    String admin = r.optString("admin1", "");
                    label = admin.isEmpty() || admin.equals(name) ? name : admin + " " + name;
                }
            } catch (Exception e) {
                Log.e(TAG, "地名解析失败", e);
                error = "网络不通，稍后再试";
            }
            final String fLabel = label, fError = error;
            final double fLat = lat, fLon = lon;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(fLabel, fLat, fLon, fError));
        }).start();
    }

    /** 异步获取一句可播报的天气文本，失败回调 null */
    public static void fetchAsync(final Context ctx, final Callback cb) {
        new Thread(() -> {
            String result = null;
            try {
                double[] ll = locate(ctx);
                if (ll != null) {
                    result = fetchWeather(ll[0], ll[1]);
                }
            } catch (Exception e) {
                Log.e(TAG, "获取天气失败", e);
            }
            final String r = result;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(r));
        }).start();
    }

    /** 图标 + 温度的短天气，例如「多云 23°」，失败回调 null */
    public static void fetchShortAsync(final Context ctx, final Callback cb) {
        new Thread(() -> {
            String result = null;
            try {
                double[] ll = locate(ctx);
                if (ll != null) {
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + ll[0]
                            + "&longitude=" + ll[1]
                            + "&current=temperature_2m,weather_code&timezone=auto";
                    JSONObject current = new JSONObject(httpGet(url)).getJSONObject("current");
                    int code = current.getInt("weather_code");
                    double temp = current.getDouble("temperature_2m");
                    result = weatherEmoji(code) + " " + Math.round(temp) + "°";
                }
                if (result != null) {
                    ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE)
                            .edit().putString("last_weather_short", result).apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "获取短天气失败", e);
            }
            final String r = result;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(r));
        }).start();
    }

    private static String weatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code >= 85 && code <= 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌤️";
    }

    /**
     * 主界面头部天气：一次拿两行（温度 + 天气词）。
     * 和 fetchShortAsync 同一个接口，只是把 weather_code 也翻成中文，省一次网络请求。
     */
    public static void fetchHeaderAsync(final Context ctx, final HeaderCallback cb) {
        new Thread(() -> {
            String main = null, desc = null;
            try {
                double[] ll = locate(ctx);
                if (ll != null) {
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + ll[0]
                            + "&longitude=" + ll[1]
                            + "&current=temperature_2m,weather_code&timezone=auto";
                    JSONObject current = new JSONObject(httpGet(url)).getJSONObject("current");
                    int code = current.getInt("weather_code");
                    double temp = current.getDouble("temperature_2m");
                    main = weatherEmoji(code) + " " + Math.round(temp) + "°";
                    desc = weatherName(code);
                }
                if (main != null) {
                    ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                            .putString(Prefs.LAST_WEATHER_SHORT, main)
                            .putString(Prefs.LAST_WEATHER_DESC, desc)
                            .apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "获取头部天气失败", e);
            }
            final String fm = main, fd = desc;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(fm, fd));
        }).start();
    }

    /** 返回 {lat, lon}，失败返回 null */
    private static double[] locate(Context ctx) {
        // 0. 家人手动指定的地点优先（老人机定位常常飘、或在异地）
        double[] fixed = fixedPlace(ctx);
        if (fixed != null) return fixed;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location loc = null;
                try {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } catch (Exception ignored) {
                }
                if (loc == null) {
                    try {
                        loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    } catch (Exception ignored) {
                    }
                }
                if (loc != null) {
                    return new double[]{loc.getLatitude(), loc.getLongitude()};
                }
            }
        } catch (Exception ignored) {
        }
        // 兜底：IP 粗略定位（依次尝试多个服务，任一成功即可）
        String[] providers = {
                "https://ipwho.is/",
                "https://ipapi.co/json/",
                "https://ipinfo.io/json"
        };
        for (String provider : providers) {
            try {
                String json = httpGet(provider);
                JSONObject o = new JSONObject(json);
                if (o.has("latitude") && o.has("longitude")) {
                    return new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
                }
                // ipinfo 的经纬度是 "lat,lon" 字符串
                if (o.has("loc")) {
                    String[] parts = o.getString("loc").split(",");
                    if (parts.length == 2) {
                        return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "IP 定位失败: " + provider, e);
            }
        }
        return null;
    }

    private static String fetchWeather(double lat, double lon) throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,weather_code&timezone=auto";
        String json = httpGet(url);
        JSONObject current = new JSONObject(json).getJSONObject("current");
        int code = current.getInt("weather_code");
        double temp = current.getDouble("temperature_2m");
        return "天气" + weatherName(code) + "，气温 " + Math.round(temp) + " 摄氏度";
    }

    private static String weatherName(int code) {
        if (code == 0) return "晴朗";
        if (code <= 2) return "多云";
        if (code == 3) return "阴天";
        if (code == 45 || code == 48) return "有雾";
        if (code >= 51 && code <= 57) return "有小雨";
        if (code >= 61 && code <= 67) return "有雨";
        if (code >= 71 && code <= 77) return "下雪";
        if (code >= 80 && code <= 82) return "有阵雨";
        if (code >= 85 && code <= 86) return "有阵雪";
        if (code >= 95) return "有雷雨";
        return "一般";
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("GET");
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
