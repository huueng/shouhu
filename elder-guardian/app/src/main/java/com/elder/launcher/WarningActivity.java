package com.elder.launcher;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 首次启动警告屏 —— App 启动的第一屏
 * 显示 "手机将无法正常使用" 的明确警告
 * 用户必须二次确认才能继续，否则退出 App
 */
@SuppressLint("ApplySharedPref")
public class WarningActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏 + 无状态栏
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_warning);

        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE);

        // 已确认过就直接进主界面。
        // 老版本还要求 Device Owner 已激活，导致家人一旦解除 DO（或换机重装），
        // 每次开机都会卡在警告页循环，这里改为只认「已确认」。
        if (prefs.getBoolean(Prefs.WARNING_CONFIRMED, false)) {
            goToMain();
            return;
        }

        TextView title = findViewById(R.id.warn_title);
        TextView body = findViewById(R.id.warn_body);
        Button confirm = findViewById(R.id.warn_confirm);
        Button cancel = findViewById(R.id.warn_cancel);

        title.setText(R.string.warning_title);
        body.setText(R.string.warning_body);
        confirm.setText(R.string.warning_confirm);
        cancel.setText(R.string.warning_cancel);

        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSecondConfirm();
            }
        });

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitApp();
            }
        });
    }

    /**
     * 二次确认对话框 —— 防止误触
     */
    private void showSecondConfirm() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.warning_confirm2)
                .setMessage(R.string.warning_body2)
                .setPositiveButton("确认继续", (dialog, which) -> {
                    prefs.edit().putBoolean("warning_confirmed", true).apply();
                    goToSetup();
                })
                .setNegativeButton("取消退出", (dialog, which) -> exitApp())
                .setCancelable(false)
                .show();
    }

    private void goToSetup() {
        // 还没选过使用形态 → 先让家人选：不插卡的看视频机 / 老人的主手机
        Intent intent;
        if (!Prefs.getBool(this, Prefs.MODE_CHOSEN, false)) {
            intent = new Intent(this, ModeChooseActivity.class);
        } else {
            intent = new Intent(this, FirstSetupActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainLauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void exitApp() {
        Toast.makeText(this, "已取消启动老人守护", Toast.LENGTH_LONG).show();
        finishAffinity();
        System.exit(0);
    }

    // 拦截返回键，避免误触退出警告屏
    @Override
    public void onBackPressed() {
        exitApp();
    }
}
