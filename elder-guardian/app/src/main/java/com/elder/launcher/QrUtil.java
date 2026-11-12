package com.elder.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成与解码（zxing core，无界面依赖）
 *
 * 生成：定位服务配置、网页传包地址 —— 家人扫一下就不用一个字一个字敲 IPv6。
 * 解码：手机拍的照片。
 *
 * 为什么解码要写得这么啰嗦（实测结论）：
 * 拿中继同款二维码做了 9 种退化 —— 旋转、模糊、缩小、深底反色、JPEG q30、
 * 贴在带噪点的大画布中间。单轮解码（Hybrid + TRY_HARDER）只在"二维码铺满画面、
 * 光照明亮"时成功，真实拍照里最常见的三种情况全挂：
 *   · 手机离得远 → 码只占画面一小块（分辨率不够）
 *   · 对着屏幕拍 → 摩尔纹 + 反光，局部阈值失手
 *   · 手一抖 → 轻微模糊，边缘糊在一起
 * 所以现在按"区域 × 二值化 × 前置处理"三重循环地试，任一轮成功立刻返回。
 */
public final class QrUtil {

    private static final String TAG = "ElderQr";

    /** 整趟解码的时间上限：老人机慢，别让界面一直转圈 */
    private static final long BUDGET_MS = 4500;

    private QrUtil() {
    }

    /**
     * @param text   内容
     * @param sizePx 输出边长（像素），会留一圈白边保证能扫
     */
    public static Bitmap encode(String text, int sizePx) {
        if (text == null || text.isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // 静区按标准给 4 个模块：只给 1 个时，二维码贴在深色页面上会"没有白边"，
            // 相机很难定位（实测：同样内容，静区不足的那张在深色背景上解不出来）
            hints.put(EncodeHintType.MARGIN, 4);
            BitMatrix matrix = new MultiFormatWriter().encode(
                    text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 家人端 App 扫码用的配置串（自定义 scheme，一眼能看出是干什么的）
     * elderguard://loc?host=2408:xxxx&port=8899&key=abcd1234
     */
    public static String locateConfigUri(String host, int port, String key) {
        return "elderguard://loc?host=" + android.net.Uri.encode(host)
                + "&port=" + port
                + "&key=" + android.net.Uri.encode(key);
    }

    /**
     * 从照片里解二维码，失败返回 null。
     *
     * 调用方应先把长边缩到 1500–2400 之间再传进来：太小解不出细节，
     * 太大（4000×3000）转 int[] 要 48MB，老人机会 OOM。
     */
    public static String decode(Bitmap bmp) {
        if (bmp == null) return null;
        long t0 = System.currentTimeMillis();
        long deadline = t0 + BUDGET_MS;

        // 1) 整图 → 中心 70% → 中心 45%
        String r = tryAll(bmp, deadline);
        if (r != null) return done(r, "整图/中心", t0);

        // 2) 四象限：码被拍在画面边角时（手抖、取景偏），中心裁剪一定漏
        r = tryQuadrants(bmp, deadline);
        if (r != null) return done(r, "分块", t0);

        // 3) 对比度拉伸：对着屏幕拍、或者室内偏暗，整体发灰
        Bitmap boosted = boostContrast(bmp);
        if (boosted != null) {
            r = tryAll(boosted, deadline);
            boosted.recycle();
            if (r != null) return done(r, "对比度拉伸", t0);
        }

        // 4) 放大 1.8 倍：站得远、码只占一小块
        Bitmap up = scale(bmp, 1.8f);
        if (up != null) {
            r = tryAll(up, deadline);
            up.recycle();
            if (r != null) return done(r, "放大", t0);
        }

        // 5) 锐化：手一抖照片就发虚，边缘糊在一起时反卷积拉一把
        Bitmap sharp = sharpen(bmp);
        if (sharp != null) {
            r = tryAll(sharp, deadline);
            sharp.recycle();
            if (r != null) return done(r, "锐化", t0);
        }

        android.util.Log.w(TAG, "解码失败，试了 " + (System.currentTimeMillis() - t0) + "ms");
        return null;
    }

    /**
     * 扫码失败时把照片存一份。
     *
     * 只靠"没识别出二维码"这句话，永远不知道是拍糊了、还是码太小、还是解码没跟上。
     * 存到应用自己的外部目录（不用申请存储权限，文件管理器能翻到），
     * 路径同时写进日志和对话框，出了问题直接把图发过来就能定位。
     */
    public static String keepFailedPhoto(android.content.Context ctx,
                                         android.graphics.Bitmap bmp, String tag) {
        if (bmp == null || ctx == null) return null;
        try {
            java.io.File dir = new java.io.File(ctx.getExternalFilesDir(null), "扫码失败");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File f = new java.io.File(dir, tag + "-" + stamp + ".jpg");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            out.close();
            android.util.Log.i(TAG, "扫码照片已留一份：" + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String done(String text, String how, long t0) {
        android.util.Log.i(TAG, "解码成功（" + how + "，" + (System.currentTimeMillis() - t0) + "ms）");
        return text;
    }

    /** 一个图：整图 + 两档中心裁剪 */
    private static String tryAll(Bitmap bmp, long deadline) {
        String r = tryDecode(bmp);
        if (r != null) return r;

        float[] ratios = {0.7f, 0.45f};
        for (float ratio : ratios) {
            if (System.currentTimeMillis() > deadline) return null;
            Bitmap c = cropCenter(bmp, ratio);
            if (c == null) continue;
            r = tryDecode(c);
            c.recycle();
            if (r != null) return r;
        }
        return null;
    }

    /** 把画面切成 2×2 四块（各带 10% 重叠），逐块试 */
    private static String tryQuadrants(Bitmap bmp, long deadline) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 200 || h < 200) return null;

        float ratio = 0.6f;
        int cw = Math.round(w * ratio);
        int ch = Math.round(h * ratio);
        int[] xs = {0, w - cw};
        int[] ys = {0, h - ch};

        for (int y : ys) {
            for (int x : xs) {
                if (System.currentTimeMillis() > deadline) return null;
                Bitmap q;
                try {
                    q = Bitmap.createBitmap(bmp, Math.max(0, x), Math.max(0, y), cw, ch);
                } catch (Exception e) {
                    continue;
                }
                String r = tryDecode(q);
                q.recycle();
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 一张图试三种二值化：
     *   Hybrid（默认，光照均匀时最好）
     *   GlobalHistogram（整体偏暗/偏亮）
     *   Otsu（屏幕摩尔纹、局部反光，自己算全局阈值）
     * 都开 TRY_HARDER 与 ALSO_INVERTED（深底浅码）。
     */
    private static String tryDecode(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        String r = onePass(px, w, h, 0, hints);
        if (r != null) return r;
        r = onePass(px, w, h, 1, hints);
        if (r != null) return r;
        return onePass(px, w, h, 2, hints);
    }

    /** @param mode 0=Hybrid 1=GlobalHistogram 2=Otsu（先自己二值化再 Hybrid） */
    private static String onePass(int[] px, int w, int h, int mode,
                                  Map<DecodeHintType, Object> hints) {
        try {
            int[] data = px;
            if (mode == 2) {
                data = otsu(px);
                if (data == null) return null;
            }
            LuminanceSource src = new RGBLuminanceSource(w, h, data);
            BinaryBitmap bb = new BinaryBitmap(mode == 1
                    ? new GlobalHistogramBinarizer(src) : new HybridBinarizer(src));
            com.google.zxing.Result rr = new MultiFormatReader().decode(bb, hints);
            return rr == null ? null : rr.getText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Otsu 全局阈值：算出一张纯黑白的图。
     * 屏幕反光会把画面切成几块亮度不同的区域，局部阈值容易在交界处断笔，
     * 全局阈值反而更稳。
     */
    private static int[] otsu(int[] px) {
        int[] hist = new int[256];
        int[] gray = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int g = ((c >> 16 & 0xFF) * 299 + (c >> 8 & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            gray[i] = g;
            hist[g]++;
        }
        int total = px.length;
        long sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * hist[i];
        long sumB = 0;
        int wB = 0;
        double maxVar = -1;
        int threshold = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sum - sumB) / wF;
            double var = (double) wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) {
                maxVar = var;
                threshold = t;
            }
        }
        int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int v = gray[i] > threshold ? 255 : 0;
            out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return out;
    }

    /** 取中间 ratio（0–1）区域；失败返回 null */
    private static Bitmap cropCenter(Bitmap src, float ratio) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int cw = Math.max(1, Math.round(w * ratio));
            int ch = Math.max(1, Math.round(h * ratio));
            return Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap src, float factor) {
        try {
            int w = Math.round(src.getWidth() * factor);
            int h = Math.round(src.getHeight() * factor);
            // 放大后别超过 2600 像素，否则 int[] 又要几十兆
            int max = Math.max(w, h);
            if (max > 2600) {
                float k = 2600f / max;
                w = Math.round(w * k);
                h = Math.round(h * k);
            }
            if (w <= 0 || h <= 0) return null;
            return Bitmap.createScaledBitmap(src, w, h, true);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 灰度 + 直方图拉伸到 0–255，增强黑白边缘。
     * 只处理亮度，输出仍是 ARGB（RGBLuminanceSource 只取亮度，但保持接口一致）。
     */
    /**
     * 反锐化掩模（unsharp mask）：gray + (gray - blur)。
     * 手机拍照手一抖，二维码的黑白边就互相渗色 —— 直接二值化会连成一片。
     * 先用 3x3 均值模糊求"应有的底色"，再把差值加回去，边缘就立起来了。
     */
    private static Bitmap sharpen(Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);

            int[] gray = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                gray[i] = ((c >> 16 & 0xFF) * 299 + (c >> 8 & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            }
            int[] blur = boxBlur(gray, w, h);
            for (int i = 0; i < px.length; i++) {
                int v = gray[i] + (gray[i] - blur[i]);
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 3x3 均值模糊（可分离，两趟） */
    private static int[] boxBlur(int[] g, int w, int h) {
        int[] tmp = new int[g.length];
        int[] out = new int[g.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int s = 0, n = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    s += g[row + xx];
                    n++;
                }
                tmp[row + x] = s / n;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    s += tmp[yy * w + x];
                    n++;
                }
                out[y * w + x] = s / n;
            }
        }
        return out;
    }

    private static Bitmap boostContrast(Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);

            int min = 255, max = 0;
            int[] gray = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int g = ((c >> 16 & 0xFF) * 299 + (c >> 8 & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
                gray[i] = g;
                if (g < min) min = g;
                if (g > max) max = g;
            }
            int span = max - min;
            if (span < 16) return null;   // 几乎是纯色，别浪费内存
            for (int i = 0; i < px.length; i++) {
                int v = (gray[i] - min) * 255 / span;
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
