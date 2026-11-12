package com.elder.launcher;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面图标管理（家人使用，入口在设置页）
 *
 * 点一下加入 / 移出主界面，结果直接写进 Prefs.TILES。
 * 老版本这里写的是 tv_tiles，与主界面的 tile_packages 对不上，
 * 手机端等于永远改不动桌面，本次已统一。
 */
public class AddAppActivity extends AppCompatActivity {

    private final Set<String> selected = new LinkedHashSet<>();
    private TextView countLabel;

    private static class AppEntry {
        String pkg;
        String label;
        android.graphics.drawable.Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSelected();
        buildUi();
        Ui.applyEdgeToEdge(this);
    }

    private void loadSelected() {
        selected.clear();
        for (String s : Prefs.tilesCsv(this).split(",")) {
            s = s.trim();
            if (!s.isEmpty()) selected.add(s);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        back.setTextColor(Ui.accent(this));
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("桌面图标");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Ui.textPrimary(this));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(header);

        countLabel = new TextView(this);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        countLabel.setTextColor(Ui.textSecondary(this));
        countLabel.setPadding(dp(24), 0, dp(24), dp(12));
        root.addView(countLabel);
        updateCount();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (AppEntry e : loadApps()) {
            list.addView(buildRow(e));
        }

        setContentView(root);
    }

    private void updateCount() {
        if (countLabel != null) {
            countLabel.setText("点一下加入 / 再点一下移出，当前已放 " + selected.size() + " 个");
        }
    }

    private List<AppEntry> loadApps() {
        List<AppEntry> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            if (seen.contains(pkg)) continue;
            seen.add(pkg);

            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = ri.loadLabel(pm).toString();
            e.icon = ri.loadIcon(pm);
            out.add(e);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private View buildRow(final AppEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(10), dp(24), dp(10));
        row.setBackgroundResource(selectableItemBackground());

        ImageView icon = new ImageView(this);
        if (e.icon != null) icon.setImageDrawable(e.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        name.setTextColor(Ui.textPrimary(this));
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(16);
        row.addView(name, nlp);

        final TextView state = new TextView(this);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        state.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.addView(state);

        final Runnable paint = () -> {
            boolean on = selected.contains(e.pkg);
            state.setText(on ? "已添加" : "＋ 添加");
            state.setTextColor(on ? Ui.color(this, R.color.elder_ok) : Ui.accent(this));
        };
        paint.run();

        row.setOnClickListener(v -> {
            if (selected.contains(e.pkg)) {
                selected.remove(e.pkg);
                Toast.makeText(this, "已移出主界面", Toast.LENGTH_SHORT).show();
            } else {
                selected.add(e.pkg);
                Toast.makeText(this, "已加入主界面", Toast.LENGTH_SHORT).show();
            }
            save();
            paint.run();
            updateCount();
        });

        return row;
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (String s : selected) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        Prefs.putTilesCsv(this, sb.toString());
        // 锁定任务白名单必须跟着卡片走，否则新加的应用在锁定状态下点不开
        ElderDeviceAdminReceiver.enableAllRestrictions(this);
    }

    private int selectableItemBackground() {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
