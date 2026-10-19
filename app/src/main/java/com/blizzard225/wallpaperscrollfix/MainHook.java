package com.blizzard225.wallpaperscrollfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口：按宿主包名分发到各宿主 Hook——
 *   com.miui.miwallpaper   → {@link WallpaperHostHook}（OS3/OS4 分支）
 *   com.android.thememanager → {@link ThemeHostHook}
 *   com.miui.aod           → {@link AodHostHook}
 *   android（system_server）→ {@link WallpaperZoomFix}（方案A 抖动修复）
 * 本类同时持有跨宿主共享的状态与工具方法。
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String WALLPAPER_PKG = "com.miui.miwallpaper";
    static final String THEME_PKG = "com.android.thememanager";
    static final String AOD_PKG = "com.miui.aod";
    private static final String SETTER_PKG = "com.blizzard225.wallpapersetter";
    static final String TAG = "WallpaperScrollFix";
    static final String SCROLL_KEY = "pref_key_wallpaper_screen_scrolled_span";
    private static final String CHANNEL_ID = "wallpaper_scroll_fix";

    // 防压暗开关：APP 勾选状态经广播同步到此进程，并持久化到 Settings.Secure
    private static final String ACTION_UPDATE_ANTI_DARKEN =
            "com.blizzard225.wallpapersetter.ACTION_UPDATE_ANTI_DARKEN";
    static final String ANTI_DARKEN_KEY = "wallpaper_anti_darken_enabled";
    static volatile boolean sAntiDarkenEnabled = false;

    static final ThreadLocal<Boolean> sIsLockscreenWallpaper = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (WALLPAPER_PKG.equals(lpparam.packageName)) {
            if (isOs4Host(lpparam.classLoader)) {
                WallpaperHostHook.hookWallpaperPkgOs4(lpparam);
            } else {
                WallpaperHostHook.hookWallpaperPkg(lpparam);
            }
        } else if (THEME_PKG.equals(lpparam.packageName)) {
            ThemeHostHook.hookThemePkg(lpparam);
        } else if (AOD_PKG.equals(lpparam.packageName)) {
            AodHostHook.hookAodApp(lpparam);
        } else if (SETTER_PKG.equals(lpparam.packageName)) {
            hookSetterApp(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        }
    }

    // ================================================================
    // system_server（LSPosed 作用域"系统框架"，lpparam.packageName 为 "android"）：
    // 随屏滚动 × 壁纸缩放动画抖动修复（方案A），原理见 WallpaperZoomFix 类注释
    // ================================================================
    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": hooked into system_server");
        WallpaperZoomFix.install(lpparam.classLoader);
    }

    // ================================================================
    // 宿主系统识别：HyperOS4 的 WallpaperUtils 含 a()，HyperOS3 只有
    // needResponseConfigChange()，以此区分两套壁纸 APP 的执行逻辑
    // ================================================================
    private boolean isOs4Host(ClassLoader classLoader) {
        try {
            Class<?> wallpaperUtils = XposedHelpers.findClass(
                    "com.miui.miwallpaper.utils.WallpaperUtils", classLoader);
            wallpaperUtils.getDeclaredMethod("a");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================
    // com.blizzard225.wallpapersetter：注入激活标志
    // ================================================================
    private void hookSetterApp(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clazz = XposedHelpers.findClass(
                    "com.blizzard225.wallpapersetter.ModuleStatus",
                    lpparam.classLoader);
            XposedHelpers.setStaticBooleanField(clazz, "isActive", true);
            XposedBridge.log(TAG + ": ModuleStatus.isActive set to true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookSetterApp failed: " + t);
        }
    }

    // ================================================================
    // 共享工具：随屏滚动开关看门狗 / 防压暗开关广播同步
    // ================================================================

    // 防压暗开关：接收 setter app 的勾选状态，更新内存开关并持久化
    static void registerAntiDarkenReceiver(final Context context) {
        try {
            IntentFilter filter = new IntentFilter(ACTION_UPDATE_ANTI_DARKEN);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    boolean enabled = intent.getBooleanExtra("enabled", false);
                    sAntiDarkenEnabled = enabled;
                    Settings.Secure.putInt(
                            context.getContentResolver(), ANTI_DARKEN_KEY, enabled ? 1 : 0);
                    XposedBridge.log(TAG + ": antiDarken updated -> " + enabled);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedBridge.log(TAG + ": registerAntiDarkenReceiver OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerAntiDarkenReceiver failed: " + t);
        }
    }

    static void registerScrollObserver(final Context context) {
        Handler handler = new Handler(Looper.getMainLooper());
        Uri uri = Settings.Secure.getUriFor(SCROLL_KEY);
        context.getContentResolver().registerContentObserver(uri, false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        int val = Settings.Secure.getInt(
                                context.getContentResolver(), SCROLL_KEY, -1);
                        XposedBridge.log(TAG + ": scroll changed to " + val);
                        if (val != 1) {
                            Settings.Secure.putInt(
                                    context.getContentResolver(), SCROLL_KEY, 1);
                            int check = Settings.Secure.getInt(
                                    context.getContentResolver(), SCROLL_KEY, -1);
                            XposedBridge.log(TAG + ": after fix, value=" + check);
                            if (check != 1) {
                                sendNotification(context);
                            }
                        }
                    }
                });
    }

    private static void sendNotification(Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "壁纸滚动修复", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("随屏滚动已关闭")
                    .setContentText("自动修复失败，请手动开启随屏滚动")
                    .setAutoCancel(true)
                    .build();
            nm.notify(1001, notification);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": notification failed: " + t);
        }
    }
}
