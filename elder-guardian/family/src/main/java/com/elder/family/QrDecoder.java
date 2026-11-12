package com.elder.family;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码解码（家人端）。
 *
 * 之前这里只有一轮 Hybrid + TRY_HARDER，跟老人端当初一模一样 ——
 * 结果就是"怎么扫都扫不上"：单轮解码只对"码铺满画面、光照均匀"的照片有效。
 * 真实拍照里最常见的是码偏小、屏幕反光、手抖发虚，这三种它全挂。
 *
 * 现在的策略：区域（整图 / 中心 70% / 中心 45% / 四象限）
 *            × 二值化（Hybrid / GlobalHistogram / Otsu）
 *            × 前置处理（原图 / 对比度拉伸 / 放大 1.8 倍）
 * 任一轮成功就返回，整体限时 4.5 秒。
 */
final class QrDecoder {

    private static final String TAG = "FamilyQr";
    private static final long BUDGET_MS = 4500;

    private QrDecoder() {
    }

    static String decode(Bitmap bmp) {
        if (bmp == null) return null;
        long t0 = System.currentTimeMillis();
        long deadline = t0 + BUDGET_MS;

        String r = tryAll(bmp, deadline);
        if (r != null) return done(r, "整图/中心", t0);

        r = tryQuadrants(bmp, deadline);
        if (r != null) return done(r, "分块", t0);

        Bitmap boosted = boostContrast(bmp);
        if (boosted != null) {
            r = tryAll(boosted, deadline);
            boosted.recycle();
            if (r != null) return done(r, "对比度拉伸", t0);
        }

        Bitmap up = scale(bmp, 1.8f);
        if (up != null) {
            r = tryAll(up, deadline);
            up.recycle();
            if (r != null) return done(r, "放大", t0);
        }

        // 手一抖照片就发虚，边缘糊在一起时用反锐化拉一把
        Bitmap sharp = sharpen(bmp);
        if (sharp != null) {
            r = tryAll(sharp, deadline);
            sharp.recycle();
            if (r != null) return done(r, "锐化", t0);
        }

        android.util.Log.w(TAG, "解码失败，试了 " + (System.currentTimeMillis() - t0) + "ms");
        return null;
    }

    private static String done(String text, String how, long t0) {
        android.util.Log.i(TAG, "解码成功（" + how + "，" + (System.currentTimeMillis() - t0) + "ms）");
        return text;
    }

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

    /** 反锐化掩模：gray + (gray - blur)，把糊掉的二维码边缘拉回来 */
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
            if (span < 16) return null;
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
