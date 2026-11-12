package com.elder.launcher;

/**
 * 主界面图标卡片数据模型
 *
 * 手机版只保留四类动作：启动应用、拨号、短信、无线网络、联系人。
 * 电视直播 / 云盘 / 内置抖音 三种卡片随对应模块一起下线。
 */
public class TileItem {

    public static final int ACTION_PACKAGE = 1;      // 启动指定包名 App
    public static final int ACTION_DIAL = 2;         // 打开系统拨号盘
    public static final int ACTION_SMS = 3;          // 打开短信收件箱
    public static final int ACTION_WIFI = 6;         // 防误触 WiFi 界面
    public static final int ACTION_CONTACT = 8;      // 一键拨打联系人
    public static final int ACTION_CALLWALL = 11;    // 电话墙（大头像联系人卡片）
    public static final int ACTION_SETTINGS = 12;    // 打开设置（调试用，可在设置里关掉卡片）

    public String label;
    public int iconRes;
    /** 目标应用自己的图标。跨包拿到的资源 ID 在本进程里无效，必须直接持有 Drawable */
    public android.graphics.drawable.Drawable iconDrawable;
    public String packageName;
    public String phone;
    public String imagePath;
    public int action;

    public TileItem(String label, int iconRes, int action, String packageName) {
        this.label = label;
        this.iconRes = iconRes;
        this.action = action;
        this.packageName = packageName;
    }
}
