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
import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 现代 libxposed API（102）模块入口：按宿主包名分发到各宿主 Hook——
 *   com.miui.miwallpaper   → {@link WallpaperHostHook}（OS3/OS4 分支）
 *   com.android.thememanager → {@link ThemeHostHook}
 *   com.miui.aod           → {@link AodHostHook}
 *   system_server（scope 含 android）→ {@link WallpaperZoomFix}（方案A 抖动修复）
 * 本类同时持有跨宿主共享的状态与工具方法。
 *
 * 与旧版（de.robv IXposedHookLoadPackage）的差异：
 *   - 入口类继承 {@link XposedModule}，框架自动 attachFramework，
 *     需保留 public 无参构造器；onModuleLoaded 之前不做任何初始化；
 *   - handleLoadPackage 拆为 {@link #onPackageLoaded}（app 宿主，取
 *     PackageLoadedParam.getDefaultClassLoader）与 {@link #onSystemServerStarting}
 *     （system_server 专用回调，替代 packageName=="android" 分支）；
 *   - 模块 app 自身不再被注入（现代 API 行为变更），激活检测改由 app 侧
 *     libxposed/service 的 XposedServiceHelper 完成；
 *   - 日志走框架 {@link XposedInterface#log(int, String, String)}，仍落在
 *     /data/adb/lspd/log/ 的 modules.log，TAG 不变。
 */
public class MainHook extends XposedModule {

    static final String WALLPAPER_PKG = "com.miui.miwallpaper";
    static final String THEME_PKG = "com.android.thememanager";
    static final String AOD_PKG = "com.miui.aod";
    static final String TAG = "WallpaperScrollFix";
    static final String SCROLL_KEY = "pref_key_wallpaper_screen_scrolled_span";
    private static final String CHANNEL_ID = "wallpaper_scroll_fix";

    // 防压暗开关：APP 勾选状态经广播同步到此进程，并持久化到 Settings.Secure
    private static final String ACTION_UPDATE_ANTI_DARKEN =
            "com.blizzard225.wallpapersetter.ACTION_UPDATE_ANTI_DARKEN";
    // 分模式壁纸写入：APP 经 FileProvider 交图，本进程直调 MIUI 内部 API 落地
    private static final String ACTION_APPLY_WALLPAPER_MODE =
            "com.blizzard225.wallpapersetter.ACTION_APPLY_WALLPAPER_MODE";
    static final String ANTI_DARKEN_KEY = "wallpaper_anti_darken_enabled";
    static volatile boolean sAntiDarkenEnabled = false;

    static final ThreadLocal<Boolean> sIsLockscreenWallpaper = new ThreadLocal<>();

    private static volatile MainHook sInstance;

    public MainHook() {
        sInstance = this;
    }

    // ================================================================
    // 静态日志/接口入口：宿主 Hook 工具类均无实例，统一经此转发到框架日志
    // ================================================================
    static void log(String msg) {
        MainHook h = sInstance;
        if (h != null) {
            h.log(Log.INFO, TAG, msg);
        }
    }

    static void logError(String msg, Throwable t) {
        MainHook h = sInstance;
        if (h != null) {
            h.log(Log.ERROR, TAG, msg, t);
        }
    }

    static XposedInterface xposed() {
        MainHook h = sInstance;
        if (h == null) {
            throw new IllegalStateException("MainHook not attached");
        }
        return h;
    }

    // ================================================================
    // 生命周期：载入目标进程（每个 scope 进程各一次）
    // ================================================================
    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log("loaded into " + param.getProcessName()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion()
                + " capSystem=" + hasProp(PROP_CAP_SYSTEM)
                + " capRemote=" + hasProp(PROP_CAP_REMOTE));
    }

    private boolean hasProp(long prop) {
        return (getFrameworkProperties() & prop) != 0;
    }

    // ================================================================
    // app 宿主分发：包载入时默认 ClassLoader 已就绪、Application 未创建，
    // 与旧 handleLoadPackage 时机等价（MiWallpaperApplication.onCreate 等
    // 早期 hook 依赖此先后序）
    // ================================================================
    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getDefaultClassLoader();
        if (WALLPAPER_PKG.equals(pkg)) {
            if (isOs4Host(cl)) {
                WallpaperHostHook.hookWallpaperPkgOs4(cl);
            } else {
                WallpaperHostHook.hookWallpaperPkg(cl);
            }
        } else if (THEME_PKG.equals(pkg)) {
            ThemeHostHook.hookThemePkg(cl);
        } else if (AOD_PKG.equals(pkg)) {
            AodHostHook.hookAodApp(cl);
        }
    }

    // ================================================================
    // system_server（LSPosed 作用域"系统框架"）：随屏滚动 × 壁纸缩放动画
    // 抖动修复（方案A），原理见 WallpaperZoomFix 类注释。
    // 框架无 PROP_CAP_SYSTEM 能力时不会进此回调，onModuleLoaded 的
    // capSystem 日志可用于排查。
    // ================================================================
    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log("hooked into system_server");
        WallpaperZoomFix.install(param.getClassLoader());
    }

    // ================================================================
    // 宿主系统识别：HyperOS4 的 WallpaperUtils 含 a()，HyperOS3 只有
    // needResponseConfigChange()，以此区分两套壁纸 APP 的执行逻辑
    // ================================================================
    private boolean isOs4Host(ClassLoader classLoader) {
        try {
            Class<?> wallpaperUtils = XH.findClass(
                    "com.miui.miwallpaper.utils.WallpaperUtils", classLoader);
            wallpaperUtils.getDeclaredMethod("a");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================
    // 共享工具：随屏滚动开关看门狗 / 防压暗开关广播同步
    // ================================================================

    // 防压暗开关 + 分模式壁纸写入：接收 setter app 的广播
    static void registerAntiDarkenReceiver(final Context context) {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_UPDATE_ANTI_DARKEN);
            filter.addAction(ACTION_APPLY_WALLPAPER_MODE);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_APPLY_WALLPAPER_MODE.equals(action)) {
                        WallpaperDarkApplier.apply(context, intent);
                        return;
                    }
                    boolean enabled = intent.getBooleanExtra("enabled", false);
                    sAntiDarkenEnabled = enabled;
                    Settings.Secure.putInt(
                            context.getContentResolver(), ANTI_DARKEN_KEY, enabled ? 1 : 0);
                    log("antiDarken updated -> " + enabled);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            log("registerAntiDarkenReceiver OK");
        } catch (Throwable t) {
            logError("registerAntiDarkenReceiver failed", t);
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
                        log("scroll changed to " + val);
                        if (val != 1) {
                            Settings.Secure.putInt(
                                    context.getContentResolver(), SCROLL_KEY, 1);
                            int check = Settings.Secure.getInt(
                                    context.getContentResolver(), SCROLL_KEY, -1);
                            log("after fix, value=" + check);
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
            logError("notification failed", t);
        }
    }
}
