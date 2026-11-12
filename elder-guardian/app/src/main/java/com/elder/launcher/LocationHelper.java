package com.elder.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * 单次定位工具（阻塞式，必须放在后台线程调用）
 *
 * 策略：
 * 1. GPS + 网络 + PASSIVE 三个 provider 同时要一个点，谁先给点用谁，精度够（≤30 米）就提前结束
 * 2. 超时后仍没有点 → 回退最后一次已知位置
 * 3. 有网时用 Geocoder 反查中文地址，失败就只给经纬度
 * 4. 高德链接使用 GCJ-02 坐标（系统定位给的是 WGS-84，必须转换）
 */
public class LocationHelper {

    private static final String TAG = "ElderLocation";
    private static final long GEOCODE_TIMEOUT_MS = 5000;
    private static final float GOOD_ACCURACY = 30f;
    /** 没有网络时给 GPS 的额外等待：冷启动要自己从卫星收星历，25 秒常常不够 */
    private static final long OFFLINE_GPS_EXTRA_MS = 35000L;

    private LocationHelper() {
    }

    /** 定位结果 */
    public static class Result {
        public double lat;
        public double lng;
        public float accuracy;
        public String address;
        public boolean fromLastKnown;
        public String error;

        public boolean ok() {
            return error == null;
        }

        public static Result fail(String msg) {
            Result r = new Result();
            r.error = msg;
            return r;
        }
    }

    /**
     * 当前是否有可用数据网络（WiFi 或移动数据）。
     * 只用来做两件事：没网时跳过反查地址、给 GPS 多等一会儿。
     */
    public static boolean hasNetwork(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasPermission(Context ctx) {
        try {
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static Result getOnce(Context ctx, long timeoutMs) {
        if (!hasPermission(ctx)) {
            return Result.fail("缺少定位权限");
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return Result.fail("定位服务不可用");
        }

        final Object lock = new Object();
        final Location[] best = {null};

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                synchronized (lock) {
                    if (location == null) return;
                    // 同一次请求里取精度最好的点
                    if (best[0] == null || location.getAccuracy() < best[0].getAccuracy()) {
                        best[0] = location;
                    }
                    lock.notifyAll();
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        try {
            for (String p : providers) {
                try {
                    if (!lm.isProviderEnabled(p)) continue;
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper());
                } catch (Exception ignored) {
                    // 单个 provider 不可用不影响其它
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "注册定位监听失败", e);
        }

        final boolean online = hasNetwork(ctx);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        // 没网时多等一会儿：这时只能靠纯 GPS，冷启动要自己从卫星收星历，
        // 25 秒经常一个点都拿不到；家人这边已经响了电话挂了，多等 35 秒是值得的。
        long hardDeadline = online ? deadline : deadline + OFFLINE_GPS_EXTRA_MS;
        synchronized (lock) {
            while (true) {
                if (best[0] != null && best[0].getAccuracy() <= GOOD_ACCURACY) break;
                long now = SystemClock.elapsedRealtime();
                long remain = deadline - now;
                if (remain <= 0) {
                    // 已经有点了（只是不够准）就不再等；一个点都没有才继续等 GPS
                    if (best[0] != null || now >= hardDeadline) break;
                    remain = hardDeadline - now;
                }
                try {
                    // 分段等待，保证 timeout 到点一定能返回
                    lock.wait(Math.min(remain, 500L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        try {
            lm.removeUpdates(listener);
        } catch (Exception ignored) {
        }

        Location loc = best[0];
        boolean lastKnown = false;
        if (loc == null) {
            // 超时兜底：用系统缓存的最后位置（可能是几分钟前的点）
            loc = lastKnownLocation(lm);
            lastKnown = loc != null;
        }
        if (loc == null) {
            return Result.fail("没有取到位置，请确认已开启定位");
        }

        Result r = new Result();
        r.lat = loc.getLatitude();
        r.lng = loc.getLongitude();
        r.accuracy = loc.getAccuracy();
        r.fromLastKnown = lastKnown;
        // 没网时 Geocoder 必然失败，还会白等 5 秒超时，直接跳过
        r.address = online ? reverseGeocode(ctx, r.lat, r.lng)
                : (lastKnown ? "（无网络，这是上次缓存的位置）" : null);
        return r;
    }

    private static Location lastKnownLocation(LocationManager lm) {
        Location best = null;
        String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String p : providers) {
            try {
                if (!lm.isProviderEnabled(p)) continue;
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                // 取最新且精度可接受的点
                if (best == null || l.getTime() > best.getTime()) {
                    best = l;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    /** 反查中文地址；Geocoder 可能卡住，最多等 5 秒 */
    private static String reverseGeocode(Context ctx, double lat, double lng) {
        final String[] out = {null};
        Thread t = new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(ctx, Locale.CHINA);
                List<Address> list = geocoder.getFromLocation(lat, lng, 1);
                if (list == null || list.isEmpty()) return;
                Address a = list.get(0);
                StringBuilder sb = new StringBuilder();
                int max = a.getMaxAddressLineIndex();
                for (int i = 0; i <= max; i++) {
                    String line = a.getAddressLine(i);
                    if (line == null || line.trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(line.trim());
                }
                if (sb.length() == 0) {
                    // 有些机型没有 addressLines，用省市区兜底
                    if (a.getAdminArea() != null) sb.append(a.getAdminArea());
                    if (a.getLocality() != null) sb.append(a.getLocality());
                    if (a.getSubLocality() != null) sb.append(a.getSubLocality());
                    if (a.getThoroughfare() != null) sb.append(a.getThoroughfare());
                }
                out[0] = sb.toString();
            } catch (Exception e) {
                Log.w(TAG, "反查地址失败", e);
            }
        }, "elder-geocode");
        t.start();
        try {
            t.join(GEOCODE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    /** 组合成短信内容：地址 + 高德地图链接 */
    public static String describe(Result r) {
        StringBuilder sb = new StringBuilder("当前位置：");
        if (r.address != null && !r.address.isEmpty()) {
            sb.append(r.address);
        } else {
            sb.append(String.format(Locale.US, "%.5f,%.5f", r.lat, r.lng));
        }
        if (r.fromLastKnown) {
            sb.append("（最后一次已知位置）");
        } else if (r.accuracy > 120) {
            sb.append("（精度约 ±").append(Math.round(r.accuracy)).append(" 米，可能不在室外）");
        }
        sb.append("\n地图：").append(buildAmapLink(r.lat, r.lng));
        return sb.toString();
    }

    /**
     * 高德地图标注链接（家人点开直接看，装了高德会拉起 App）
     * 注意：position 顺序是「经度,纬度」，且必须是 GCJ-02 坐标
     */
    public static String buildAmapLink(double lat, double lng) {
        double[] gcj = wgs84ToGcj02(lat, lng);
        return "https://uri.amap.com/marker?position="
                + String.format(Locale.US, "%.6f,%.6f", gcj[1], gcj[0])
                + "&name=" + java.net.URLEncoder.encode("老人位置")
                + "&coordinate=gaode&callnative=1";
    }

    // ===== WGS-84 → GCJ-02 坐标转换（国内地图必须） =====

    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
