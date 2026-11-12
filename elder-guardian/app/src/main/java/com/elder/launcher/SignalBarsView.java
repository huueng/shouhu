package com.elder.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 手机信号四格条（天气下面）
 *
 * 用 4 根从矮到高的柱子表示信号强度（0–4 格），比"▂▄▆"这种字符稳定——
 * 字符在不同字体里大小不一，还会被 TTS 念出来。
 */
public class SignalBarsView extends View {

    private static final int BARS = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 0–4 格；-1 表示没卡/读不到 */
    private int level = -1;

    public SignalBarsView(Context ctx) {
        this(ctx, null);
    }

    public SignalBarsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setLevel(int level) {
        this.level = Math.max(-1, Math.min(BARS, level));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float gap = dp(2);
        float barW = (w - gap * (BARS - 1)) / BARS;
        int color = colorFor(level);

        for (int i = 0; i < BARS; i++) {
            // 高度从 40% 递增到 100%
            float bh = h * (0.40f + 0.20f * i);
            float left = i * (barW + gap);
            rect.set(left, h - bh, left + barW, h);
            // 没用到的格子画淡一点，这样"几格"一眼能数出来
            paint.setColor(i < level ? color : 0x33888888);
            float r = Math.min(barW / 2f, dp(2));
            c.drawRoundRect(rect, r, r, paint);
        }
    }

    private int colorFor(int level) {
        if (level <= 0) return 0xFFC62828;   // 没信号：红
        if (level <= 2) return 0xFFF57C00;   // 偏弱：橙
        return 0xFF2E7D32;                    // 正常：绿
    }

    public int getLevel() {
        return level;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
