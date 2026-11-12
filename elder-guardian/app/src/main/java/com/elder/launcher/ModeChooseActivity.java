package com.elder.launcher;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 首次启动：这台手机怎么用？
 *
 * 放在警告页之后、初始设置之前。
 *
 * 为什么要单独一步：同一份 App 现在要服务两种完全不同的机器——
 *   1) 老人自己的主手机：要打电话、发短信、家人能查位置
 *   2) 放在家里只连 WIFI 的看视频机：不插卡，电话短信全是空转
 * 让家人在第一次就选清楚，比装完再进设置里翻开关可靠得多。
 * 选完写进 INTRANET_MODE，后面所有页面（桌面、设置、向导）都按这个分支走。
 */
public class ModeChooseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));
        root.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("这台手机怎么用？");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.textPrimary(this));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("只选一次。将来要改，进家人设置里也能改。");
        sub.setTextSize(13);
        sub.setTextColor(Ui.textSecondary(this));
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        root.addView(card("只连家里 WIFI，看视频用",
                "这台不插卡，只放在家里刷视频。\n"
                        + "· 桌面去掉「打电话 / 发短信 / 联系人」\n"
                        + "· 位置照常上报，家人能看到它在不在家\n"
                        + "· 上报时带上当前 WIFI 的名字",
                false));

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        root.addView(gap);

        root.addView(card("当老人的主手机用",
                "要打电话、收短信、家人能远程查位置。\n"
                        + "· 桌面有打电话、发短信、联系人大卡片\n"
                        + "· 家人发短信能查到位置\n"
                        + "· 响两声挂断也能查到位置",
                true));

        TextView foot = new TextView(this);
        foot.setText("选错了也没关系：装好后进「家人设置 → 内网 WIFI 模式」可以随时切换。");
        foot.setTextSize(12);
        foot.setTextColor(Ui.textSecondary(this));
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);

        setContentView(scroll);
        Ui.applyEdgeToEdge(this);
    }

    private View card(String titleText, String descText, final boolean normalMode) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setClickable(true);
        box.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surface(this));
        bg.setCornerRadius(dp(14));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList ripple =
                    android.content.res.ColorStateList.valueOf(0x22000000);
            box.setBackground(new android.graphics.drawable.RippleDrawable(ripple, bg, null));
        } else {
            box.setBackground(bg);
        }

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.textPrimary(this));
        box.addView(t);

        TextView d = new TextView(this);
        d.setText(descText);
        d.setTextSize(13);
        d.setTextColor(Ui.textSecondary(this));
        d.setPadding(0, dp(8), 0, dp(10));
        d.setLineSpacing(0, 1.25f);
        box.addView(d);

        TextView go = new TextView(this);
        go.setText("选这个 →");
        go.setTextSize(15);
        go.setTextColor(Ui.accent(this));
        go.setGravity(Gravity.END);
        box.addView(go);

        box.setOnClickListener(v -> choose(normalMode));
        return box;
    }

    private void choose(boolean normalMode) {
        IntranetMode.setOn(this, !normalMode);
        Prefs.putBool(this, Prefs.MODE_CHOSEN, true);
        startActivity(new Intent(this, FirstSetupActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        // 还没选就退出去，等于这次没配置完；允许退回上一步（警告页）而不是直接退出
        super.onBackPressed();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
