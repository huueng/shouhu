package com.elder.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 竖着的电量条（主界面正中，时间和天气之间）
 *
 * 为什么自绘而不是用 ProgressBar：
 * ProgressBar 的样式在各家 ROM 上被改得五花八门，颜色也难控；
 * 这里要的就是"一眼看出还有多少电"，形状做成电池（上有一个小凸头），
 * 填充从下往上长，低于 20% 变红——老人不需要看懂百分比，看颜色和长度就够。
 */
public class BatteryBarView extends View {

    /** 低于这个就算"该充电了" */
    public static final int LOW_PCT = 30;
    /** 高于这个才算"够用" */
    public static final int HIGH_PCT = 60;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 三档颜色，构造时按当前主题取（深色模式自动换成浅一档的） */
    private final int colorLow;
    private final int colorMid;
    private final int colorHigh;

    /** 0–100，-1 表示还没取到 */
    private int pct = -1;
    private boolean charging;

    public BatteryBarView(Context ctx) {
        this(ctx, null);
    }

    public BatteryBarView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2));
        outline.setColor(0x66888888);
        fill.setStyle(Paint.Style.FILL);

        // 颜色只表达"还剩多少"，充电状态由百分比旁边的 ⚡ 表示。
        // 以前充电时整条变成青色，30% 和 90% 看着一样，老人根本判断不出该不该充。
        // 用饱和色而不是主题里的 text 色：这是实心色块，夜间也要看得清（夜间浅红会变粉）。
        colorLow = Ui.color(ctx, R.color.battery_low);
        colorMid = Ui.color(ctx, R.color.battery_mid);
        colorHigh = Ui.color(ctx, R.color.battery_high);
        fill.setColor(colorHigh);
    }

    public void setStatus(int pct, boolean charging) {
        this.pct = Math.max(-1, Math.min(100, pct));
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float capH = dp(5);
        float capW = Math.max(dp(8), w * 0.44f);
        float top = capH + dp(3);

        // 正极小凸头
        rect.set((w - capW) / 2f, 0, (w + capW) / 2f, capH);
        c.drawRoundRect(rect, capH / 2f, capH / 2f, outline);

        // 电池外壳
        float r = dp(7);
        rect.set(dp(1), top, w - dp(1), h - dp(1));
        c.drawRoundRect(rect, r, r, outline);

        if (pct <= 0) return;   // 未知或没电就不画填充

        // 内部填充：从底部往上长
        float inset = dp(4);
        float innerTop = top + inset;
        float innerBottom = h - dp(1) - inset;
        float innerH = innerBottom - innerTop;
        if (innerH <= 0) return;

        float fillH = innerH * pct / 100f;
        fill.setColor(colorFor(pct));
        rect.set(dp(1) + inset, innerBottom - fillH, w - dp(1) - inset, innerBottom);
        float fr = Math.min(r - dp(2), fillH / 2f);
        c.drawRoundRect(rect, fr, fr, fill);
    }

    /**
     * 电量越高越绿，越少越红：
     *   0–29%   红（该充电了）
     *   30–59%  橙（还能用，但别拖着）
     *   60–100% 绿（够用）
     */
    private int colorFor(int pct) {
        if (pct < LOW_PCT) return colorLow;
        if (pct < HIGH_PCT) return colorMid;
        return colorHigh;
    }

    /** 给设置页/自检用 */
    public int getPct() {
        return pct;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
