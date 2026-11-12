package com.elder.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

/**
 * 主题色 + 底部三键导航栏透明
 *
 * 所有界面统一从这里取色，深色模式只需要改 values-night/colors.xml。
 */
public final class Ui {

    private Ui() {
    }

    public static boolean isDark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int color(Context ctx, int res) {
        return androidx.core.content.ContextCompat.getColor(ctx, res);
    }

    public static int bg(Context ctx) {
        return color(ctx, R.color.elder_background);
    }

    public static int surface(Context ctx) {
        return color(ctx, R.color.elder_card_bg);
    }

    public static int textPrimary(Context ctx) {
        return color(ctx, R.color.elder_text_primary);
    }

    public static int textSecondary(Context ctx) {
        return color(ctx, R.color.elder_text_secondary);
    }

    public static int accent(Context ctx) {
        return color(ctx, R.color.colorPrimary);
    }

    public static int divider(Context ctx) {
        return color(ctx, R.color.elder_divider);
    }

    /**
     * 底部三键导航栏背景永久透明
     *
     * 只把导航栏颜色设成透明（内容仍按系统布局避开导航栏），
     * 这样导航栏区域露出的是窗口背景色，和页面背景一致，
     * 既没有黑/白遮罩，也不会把页面内容顶到状态栏下面。
     */
    public static void applyEdgeToEdge(final Activity act) {
        if (act == null) return;
        Window w = act.getWindow();
        try {
            // 与页面背景同色，看起来就是「透明」，又不会露出系统黑/白底
            w.setNavigationBarColor(bg(act));
            if (Build.VERSION.SDK_INT >= 28) {
                w.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
        } catch (Exception ignored) {
        }

        // 浅色模式下三键图标用深色，否则会看不见
        if (Build.VERSION.SDK_INT >= 26) {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (isDark(act)) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }
}
