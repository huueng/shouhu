package com.elder.family;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 家人端：管理多位老人的位置
 *
 * 界面全部用本地 HTML（assets/index.html），原生只负责四件事：
 * 1. 拍照扫码（调系统相机拍一张，再用 zxing 解）
 * 2. 从相册选图扫码（家人把二维码截图发过来也能用）
 * 3. 打开地图链接
 * 4. Toast
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ElderFamily";
    private static final int REQ_CAMERA = 5201;
    private static final int REQ_GALLERY = 5202;
    private static final int REQ_AVATAR = 5203;

    private WebView web;
    private File pendingPhoto;
    /** 正在给哪位老人换头像（选图回来时要对得上） */
    private String pendingAvatarId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Leaflet JS 与高德瓦片都走 https，混合内容要放开
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        // 页面里用了 prompt/confirm 做输入（改名、围栏半径、删除确认），
        // 不挂 WebChromeClient 这些弹窗会被 WebView 直接吞掉。
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("好", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("确定", (d, w) -> r.confirm())
                        .setNegativeButton("取消", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String defaultValue,
                                      final JsPromptResult r) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                input.setSelection(input.getText().length());
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(msg)
                        .setView(input)
                        .setPositiveButton("确定", (d, w) -> r.confirm(input.getText().toString()))
                        .setNegativeButton("取消", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        // 页面是 file:// 加载的，默认不许它跨域请求中继（http/https），
        // 结果所有中继请求都 "Failed to fetch"。这两行必须开。
        try {
            android.webkit.WebSettings ws = web.getSettings();
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
        } catch (Exception ignored) {
        }
        web.loadUrl("file:///android_asset/index.html");

        setContentView(web);

        // 进来就把常驻拉起来（开关关着的话 ensureRunning 会被 Bridge 挡住）
        if (alertsOn()) {
            AlertService.ensureRunning(this);
        }
    }

    /** 给网页调用的原生能力 */
    public class Bridge {

        /** 相机扫码 */
        @JavascriptInterface
        public void scan() {
            runOnUiThread(MainActivity.this::startCapture);
        }

        /** 从相册选图扫码 */
        @JavascriptInterface
        public void pickImage() {
            runOnUiThread(MainActivity.this::pickFromGallery);
        }

        /** 给某位老人换头像（选相册里的图，压小后以 data URL 回传页面） */
        @JavascriptInterface
        public void pickAvatar(String id) {
            runOnUiThread(() -> {
                pendingAvatarId = id;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_AVATAR);
                } catch (Exception e) {
                    showToast("打不开相册：" + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                showToast("打不开链接：" + e.getMessage());
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> showToast(msg));
        }

        @JavascriptInterface
        public boolean hasCamera() {
            return getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_CAMERA_ANY);
        }

        /** 网页把老人列表同步给原生，常驻服务靠它做后台检查 */
        @JavascriptInterface
        public void setAlerts(String json) {
            try {
                getSharedPreferences("alerts", MODE_PRIVATE)
                        .edit().putString("data", json == null ? "[]" : json).apply();
                if (alertsOn()) {
                    AlertService.ensureRunning(MainActivity.this);
                }
            } catch (Exception ignored) {
            }
        }

        /** 后台提醒总开关 */
        @JavascriptInterface
        public void setAlertsEnabled(boolean on) {
            try {
                getSharedPreferences("alerts", MODE_PRIVATE)
                        .edit().putBoolean("enabled", on).apply();
                if (on) {
                    AlertService.ensureRunning(MainActivity.this);
                } else {
                    AlertService.stopService(MainActivity.this);
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public boolean alertsEnabled() {
            return alertsOn();
        }
    }

    private boolean alertsOn() {
        return getSharedPreferences("alerts", MODE_PRIVATE).getBoolean("enabled", true);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void startCapture() {
        try {
            pendingPhoto = new File(getCacheDir(), "scan.jpg");
            if (pendingPhoto.exists() && !pendingPhoto.delete()) {
                Log.w(TAG, "旧临时文件删不掉，继续覆盖");
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingPhoto);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            showToast("打不开相机：" + e.getMessage());
        }
    }

    private void pickFromGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_GALLERY);
        } catch (Exception e) {
            showToast("打不开相册：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAMERA) {
            if (resultCode != RESULT_OK) {
                showToast("已取消");
                return;
            }
            String camText = decode(pendingPhoto);
            if (camText == null) keepFailed(pendingPhoto, "扫码失败");
            deliver(camText);
            return;
        }

        if (requestCode == REQ_AVATAR) {
            String id = pendingAvatarId;
            pendingAvatarId = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || id == null) {
                return;
            }
            File copy = copyToCache(data.getData());
            String url = avatarDataUrl(copy);
            if (url == null) {
                showToast("这张图读不出来，换一张试试");
                return;
            }
            web.evaluateJavascript("window.onAvatarPicked(" + JSONObject.quote(id) + ","
                    + JSONObject.quote(url) + ")", null);
            return;
        }

        if (requestCode == REQ_GALLERY) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                showToast("已取消");
                return;
            }
            File copy = copyToCache(data.getData());
            if (copy == null) {
                showToast("读不到这张图");
                return;
            }
            String galText = decode(copy);
            if (galText == null) keepFailed(copy, "相册选图");
            deliver(galText);
        }
    }

    /**
     * 解不出来时把图留一份。
     * 没有这张图，就只能看着"没识别到二维码"猜原因；有了它，
     * 下次直接看是拍糊了、码太小、还是对着屏幕反光。
     */
    private void keepFailed(java.io.File src, String tag) {
        if (src == null || !src.exists()) return;
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "扫码失败");
            if (!dir.exists() && !dir.mkdirs()) return;
            String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File dst = new java.io.File(dir, tag + "-" + stamp + ".jpg");
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            Log.i(TAG, "扫码失败照片已留档：" + dst.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "留档失败", e);
        }
    }

    private void deliver(String text) {
        if (text == null) {
            showToast("没识别到二维码，换个角度或换张图再试");
            return;
        }
        Log.i(TAG, "扫码结果：" + text);
        web.evaluateJavascript("window.onScanResult(" + JSONObject.quote(text) + ")", null);
    }

    /**
     * 头像统一压到 320px、JPEG 82，再转成 data URL 存进 localStorage。
     * 为什么要压：原图动辄 3-5MB，WebView 的 localStorage 只有 5MB 左右，
     * 存两张原图就爆了，而且每次渲染都要解码大图，老人机列表会卡。
     */
    private String avatarDataUrl(File file) {
        if (file == null || !file.exists()) return null;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) > 320) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) return null;

            // 居中裁成正方形，卡片里不会变形
            int side = Math.min(bmp.getWidth(), bmp.getHeight());
            Bitmap square = Bitmap.createBitmap(bmp,
                    (bmp.getWidth() - side) / 2, (bmp.getHeight() - side) / 2, side, side);
            if (square != bmp) bmp.recycle();
            bmp = square;

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            String b64 = android.util.Base64.encodeToString(
                    out.toByteArray(), android.util.Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } catch (Exception e) {
            Log.w(TAG, "头像处理失败", e);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** 相册的 content:// 先落到缓存文件，解码逻辑就能复用 */
    private File copyToCache(Uri uri) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File f = new File(getCacheDir(), "picked.jpg");
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return f;
        } catch (Exception e) {
            Log.w(TAG, "复制图片失败", e);
            return null;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 用 zxing 解一张静态图 */
    private String decode(File file) {
        if (file == null || !file.exists()) return null;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) > 2200) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) return null;

            return QrDecoder.decode(bmp);
        } catch (Exception e) {
            Log.w(TAG, "解码失败", e);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }
}
