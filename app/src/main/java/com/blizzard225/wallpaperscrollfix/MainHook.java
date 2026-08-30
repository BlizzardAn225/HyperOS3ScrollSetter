package com.blizzard225.wallpaperscrollfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Size;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String WALLPAPER_PKG = "com.miui.miwallpaper";
    private static final String THEME_PKG = "com.android.thememanager";
    private static final String AOD_PKG = "com.miui.aod";
    private static final String SETTER_PKG = "com.blizzard225.wallpapersetter";
    private static final String TAG = "WallpaperScrollFix";
    private static final String SCROLL_KEY = "pref_key_wallpaper_screen_scrolled_span";
    private static final String CHANNEL_ID = "wallpaper_scroll_fix";

    // 防压暗开关：APP 勾选状态经广播同步到此进程，并持久化到 Settings.Secure
    private static final String ACTION_UPDATE_ANTI_DARKEN =
            "com.blizzard225.wallpapersetter.ACTION_UPDATE_ANTI_DARKEN";
    private static final String ANTI_DARKEN_KEY = "wallpaper_anti_darken_enabled";
    private static volatile boolean sAntiDarkenEnabled = false;

    private static final ThreadLocal<Boolean> sIsLockscreenWallpaper = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (WALLPAPER_PKG.equals(lpparam.packageName)) {
            if (isOs4Host(lpparam.classLoader)) {
                hookWallpaperPkgOs4(lpparam);
            } else {
                hookWallpaperPkg(lpparam);
            }
        } else if (THEME_PKG.equals(lpparam.packageName)) {
            hookThemePkg(lpparam);
        } else if (AOD_PKG.equals(lpparam.packageName)) {
            hookAodApp(lpparam);
        } else if (SETTER_PKG.equals(lpparam.packageName)) {
            hookSetterApp(lpparam);
        }
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
    // aodOS3 Hook (com.miui.aod)
    // ================================================================
    private void hookAodApp(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": hooked into " + AOD_PKG);
        hookAodEditorApplication(lpparam);
        hookAodTemplateApiCrop(lpparam);
        hookAodApplyWallpaper(lpparam);
    }

    private void hookAodEditorApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.kg.editor.EditorApplication",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(null);
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            Context context = (Context) param.thisObject;
                            registerScrollObserver(context);
                            Settings.Secure.putInt(
                                    context.getContentResolver(), SCROLL_KEY, 1);
                            XposedBridge.log(TAG + "[AOD]: EditorApplication.onCreate, forced scroll=1");
                            return null;
                        }
                    }
            );
            XposedBridge.log(TAG + "[AOD]: Hook EditorApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[AOD]: Hook EditorApplication.onCreate failed: " + t);
        }
    }

    private void hookAodApplyWallpaper(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wallpaperInfoClass;
        Class<?> wallpaperApplyParamClass;
        try {
            wallpaperInfoClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperInfo");
            wallpaperApplyParamClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.template.WallpaperApplyParam");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[AOD]: Failed to load classes for applyWallpaper hooks: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                    lpparam.classLoader,
                    "applyImageWallpaper",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class, boolean.class,
                    Bitmap.class, wallpaperApplyParamClass, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isLock = (boolean) param.args[3];
                            sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(TAG + "[AOD]: applyImageWallpaper isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(TAG + "[AOD]: Hook applyImageWallpaper OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[AOD]: Hook applyImageWallpaper failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                    lpparam.classLoader,
                    "applyGalleryWallpaper",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isLock = (boolean) param.args[2];
                            sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(TAG + "[AOD]: applyGalleryWallpaper isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(TAG + "[AOD]: Hook applyGalleryWallpaper OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[AOD]: Hook applyGalleryWallpaper failed: " + t);
        }
    }

    // ================================================================
    // com.miui.miwallpaper：阻止 OS3 强制关闭随屏滚动
    // ================================================================
    private void hookWallpaperPkg(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": hooked into " + WALLPAPER_PKG);

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.utils.SystemBuildUtil",
                    lpparam.classLoader,
                    "isOs3AtLeast",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return false;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook isOs3AtLeast OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook isOs3AtLeast failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.MiuiWallpaperManager",
                    lpparam.classLoader,
                    "checkWallpaperScroll",
                    Context.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": checkWallpaperScroll blocked");
                            return null;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook checkWallpaperScroll OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook checkWallpaperScroll failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.MiWallpaperApplication",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            Settings.Secure.putInt(
                                    context.getContentResolver(), SCROLL_KEY, 1);
                            registerScrollObserver(context);
                            // 读取防压暗开关初值（进程重启后恢复上次状态）
                            sAntiDarkenEnabled = Settings.Secure.getInt(
                                    context.getContentResolver(), ANTI_DARKEN_KEY, 0) == 1;
                            registerAntiDarkenReceiver(context);
                            XposedBridge.log(TAG + ": onCreate: forced scroll=1, antiDarken=" + sAntiDarkenEnabled);
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook MiWallpaperApplication.onCreate failed: " + t);
        }

        // 壁纸压暗 hook：勾选"防压暗"时才阻止 getIsDarkWallpaperColor 返回 true
        // 使 shader 的 uDarken=0，transformColor 压暗失效，壁纸保持原色。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.WallpaperServiceController",
                    lpparam.classLoader,
                    "getIsDarkWallpaperColor",
                    int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (!sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            XposedBridge.log(TAG + ": getIsDarkWallpaperColor -> false");
                            return false;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook getIsDarkWallpaperColor OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook getIsDarkWallpaperColor failed: " + t);
        }

        // 壁纸压暗 hook：勾选"防压暗"时才阻止 getWallpaperIsDark 返回 true
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.WallpaperServiceController",
                    lpparam.classLoader,
                    "getWallpaperIsDark",
                    int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (!sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            XposedBridge.log(TAG + ": getWallpaperIsDark -> false");
                            return false;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook getWallpaperIsDark OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook getWallpaperIsDark failed: " + t);
        }

        // 壁纸压暗 hook：AnimImageGLProgram.updateMaskLayerStatus(boolean, boolean)
        // 桌面/锁屏渲染都汇聚到这里，勾选"防压暗"时强制两参为 false（uDarken=0, uDarkMode=0），
        // 使 shader 恒走 fragColor = rgba 输出原色，一次 hook 全覆盖。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram",
                    lpparam.classLoader,
                    "updateMaskLayerStatus",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (sAntiDarkenEnabled) {
                                XposedBridge.log(TAG + ": updateMaskLayerStatus -> false, false");
                                param.args[0] = false;
                                param.args[1] = false;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook AnimImageGLProgram.updateMaskLayerStatus OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook AnimImageGLProgram.updateMaskLayerStatus failed: " + t);
        }

        // 强制桌面壁纸在锁屏上可见，使同图壁纸时锁屏跟随桌面滚动
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.WallpaperServiceController",
                    lpparam.classLoader,
                    "setDesktopWallpaperShowWhenLocked",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                param.args[0] = true;
                                XposedBridge.log(TAG + ": setDesktopWallpaperShowWhenLocked false->true");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook setDesktopWallpaperShowWhenLocked OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook setDesktopWallpaperShowWhenLocked failed: " + t);
        }
    }

    // ================================================================
    // 防压暗开关：接收 setter app 的勾选状态，更新内存开关并持久化
    // ================================================================
    private void registerAntiDarkenReceiver(final Context context) {
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

    private void registerScrollObserver(final Context context) {
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

    private void sendNotification(Context context) {
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

    // ================================================================
    // wallpaperOS4 Hook（宿主设备=HyperOS4）
    // OS3 的部分 hook 目标在 OS4 中失效（isOs3AtLeast / checkWallpaperScroll /
    // setDesktopWallpaperShowWhenLocked），整套替换为：
    //   1. MiWallpaperApplication.onCreate：强制 scroll=1 + ContentObserver 写回，
    //      保证随屏滚动开关常开；
    //   2. SystemSettingUtils.x(int)：吞掉写 0，阻止随屏滚动被关闭
    //      （含 onWallpaperFileChanged 的强制重置路径）；
    //   3. reportSurfaceSize（桌面+锁屏渲染器）：去除 OS4 新增的 fMax≤1 钳制。
    //      该钳制使位图不被放大覆盖屏幕，随屏滚动下 surface = (1.1×屏宽, 位图原始高)，
    //      宽高比偏离位图 → GL cover 矩阵将壁纸纵向居中裁到 位图宽/(1.1×屏宽)；
    //   4. 防压暗（见 hookOs4AntiDarken）。
    // ================================================================
    private void hookWallpaperPkgOs4(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": hooked into " + WALLPAPER_PKG + " (HyperOS4 host)");

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.MiWallpaperApplication",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            Settings.Secure.putInt(
                                    context.getContentResolver(), SCROLL_KEY, 1);
                            registerScrollObserver(context);
                            // 防压暗开关：读取持久化状态并接收 APP 广播（与 OS3 同一套机制）
                            sAntiDarkenEnabled = Settings.Secure.getInt(
                                    context.getContentResolver(), ANTI_DARKEN_KEY, 0) == 1;
                            registerAntiDarkenReceiver(context);
                            XposedBridge.log(TAG + "[OS4]: onCreate: forced scroll=1, antiDarken="
                                    + sAntiDarkenEnabled);
                        }
                    }
            );
            XposedBridge.log(TAG + "[OS4]: Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[OS4]: Hook MiWallpaperApplication.onCreate failed: " + t);
        }

        // 随屏滚动保活：吞掉 SystemSettingUtils.x(0)（x(1) 等其余写入不受影响）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.utils.SystemSettingUtils",
                    lpparam.classLoader,
                    "x",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ((int) param.args[0] == 0) {
                                XposedBridge.log(TAG + "[OS4]: blocked SystemSettingUtils.x(0)");
                                param.setResult(null);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "[OS4]: Hook SystemSettingUtils.x guard OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[OS4]: Hook SystemSettingUtils.x guard failed: " + t);
        }

        hookOs4ReportSurfaceSize(lpparam,
                "com.miui.miwallpaper.container.openGL.DesktopAnimImageWallpaperRenderer", true);
        hookOs4ReportSurfaceSize(lpparam,
                "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer", false);
        hookOs4AntiDarken(lpparam);
    }

    // ================================================================
    // OS4 防压暗。两代 anim_fragment_shader.glsl 逐字节一致，压暗语义不变：
    //   uDarkMode（遮罩，来源 WallpaperServiceController.E(int)）在 OS4 被
    //     B() = f2269c && !SystemBuildUtil.f() 上游恒短路（f()=HyperOS≥1 恒真），无需 hook；
    //   uDarken（压暗主因，来源 WallpaperServiceController.m(int)，其缓存由 t(int) 计算，
    //     依据 WallpaperColors 的暗色 hint）在 OS4 仍可能为 1，需要 hook。
    // OS3 → OS4 目标对应关系：
    //   getIsDarkWallpaperColor(int) → m(int)
    //   getWallpaperIsDark(int)      → t(int)
    //   AnimImageGLProgram.updateMaskLayerStatus(boolean,boolean) → 同名同签名，保留
    // ================================================================
    private void hookOs4AntiDarken(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.WallpaperServiceController",
                    lpparam.classLoader,
                    "m",
                    int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (!sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            return false;
                        }
                    }
            );
            XposedBridge.log(TAG + "[OS4]: Hook WallpaperServiceController.m(int) OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[OS4]: Hook WallpaperServiceController.m(int) failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.WallpaperServiceController",
                    lpparam.classLoader,
                    "t",
                    int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (!sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            return Boolean.FALSE;
                        }
                    }
            );
            XposedBridge.log(TAG + "[OS4]: Hook WallpaperServiceController.t(int) OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[OS4]: Hook WallpaperServiceController.t(int) failed: " + t);
        }

        // 汇聚点：桌面/锁屏渲染统一入口，勾选防压暗时强制 uDarkMode=0、uDarken=0（与 OS3 相同）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram",
                    lpparam.classLoader,
                    "updateMaskLayerStatus",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sAntiDarkenEnabled) {
                                XposedBridge.log(TAG + "[OS4]: updateMaskLayerStatus -> false, false");
                                param.args[0] = false;
                                param.args[1] = false;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "[OS4]: Hook AnimImageGLProgram.updateMaskLayerStatus OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[OS4]: Hook AnimImageGLProgram.updateMaskLayerStatus failed: " + t);
        }
    }

    // 用 OS3 的无钳制公式替换 reportSurfaceSize：
    //   fMax = max(screenW/bw, screenH/bh)（不钳到 1），surface = 位图 cover 尺寸；
    //   随屏滚动下仅当位图等比宽度不足 1.1×屏宽时才扩展到 1.1×屏宽（与 OS3 行为一致）。
    // WallpaperUtils.a()==true 的分支（部分机型/形态）本身无钳制，走原逻辑。
    private void hookOs4ReportSurfaceSize(XC_LoadPackage.LoadPackageParam lpparam,
                                          String className, final boolean desktop) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader,
                    "reportSurfaceSize", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            Object renderer = param.thisObject;
                            ClassLoader cl = renderer.getClass().getClassLoader();
                            Class<?> wallpaperUtils = XposedHelpers.findClass(
                                    "com.miui.miwallpaper.utils.WallpaperUtils", cl);
                            if (XposedHelpers.callStaticMethod(wallpaperUtils, "a") == Boolean.TRUE) {
                                return XposedBridge.invokeOriginalMethod(param.method, renderer, param.args);
                            }
                            Context context = (Context) XposedHelpers.getObjectField(renderer, "mContext");
                            Rect screen = (Rect) XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("com.miui.miwallpaper.utils.ScreenUtils", cl),
                                    "a", context);
                            Object texture = XposedHelpers.getObjectField(renderer, "mTexture");
                            XposedHelpers.callMethod(texture, "use",
                                    new Class[]{java.util.function.Consumer.class}, new Object[]{null});
                            Rect dims = (Rect) XposedHelpers.callMethod(texture, "getTextureDimensions");
                            Rect surface = (Rect) XposedHelpers.getObjectField(renderer, "mSurfaceSize");
                            surface.set(screen);
                            int screenW = surface.width();
                            int screenH = surface.height();
                            boolean scroll = desktop && isOs4ScrollEnabled(renderer, cl);
                            // OS3 公式：fMax 不钳制
                            float scale = Math.max(screenW / (float) dims.width(),
                                    screenH / (float) dims.height());
                            int width2 = (int) ((dims.width() * scale) + 0.5f);
                            int height2 = (int) ((dims.height() * scale) + 0.5f);
                            int targetW = (!scroll || ((double) width2) >= ((double) screenW) * 1.1d)
                                    ? width2 : (int) (screenW * 1.1f);
                            surface.set(0, 0, targetW, height2);
                            XposedBridge.log(TAG + "[OS4]: reportSurfaceSize(unclamped) bitmap="
                                    + dims.width() + "x" + dims.height() + " screen=" + screenW
                                    + "x" + screenH + " scroll=" + scroll + " -> " + targetW + "x" + height2);
                            return new Size(targetW, height2);
                        }
                    });
            XposedBridge.log(TAG + "[OS4]: Hook reportSurfaceSize OK: " + className);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[OS4]: Hook reportSurfaceSize failed (" + className + "): " + t);
        }
    }

    // 桌面渲染器的滚动开关：优先读引擎自身的标志位（混淆后运行时字段名为 "d"），失败则回落设置值
    private boolean isOs4ScrollEnabled(Object renderer, ClassLoader classLoader) {
        try {
            return XposedHelpers.getBooleanField(renderer, "d");
        } catch (Throwable t) {
            try {
                return XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.miui.miwallpaper.utils.SystemSettingUtils",
                                classLoader), "p") == Boolean.TRUE;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    // ================================================================
    // com.android.thememanager
    // ================================================================
    private void hookThemePkg(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": hooked into " + THEME_PKG);

        hookThemeOcQ(lpparam);
        hookThemeOcN(lpparam);
        hookThemeN5r1(lpparam);
        hookThemeL(lpparam);
        hookThemeLrht(lpparam);
        hookThemeGyi(lpparam);
        hookThemeNi7(lpparam);
        hookThemeTemplateApiCrop(lpparam);
        hookThemeApplyWallpaper(lpparam);
    }

    private void hookThemeOcQ(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.oc",
                    lpparam.classLoader,
                    "q",
                    int.class, int.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean original = (boolean) param.args[2];
                            if (!original) {
                                XposedBridge.log(TAG + "[Theme]: oc.q isScrollable false->true "
                                        + " img=" + param.args[0] + "x" + param.args[1]);
                                param.args[2] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook oc.q OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook oc.q failed: " + t);
        }
    }

    private void hookThemeOcN(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.oc",
                    lpparam.classLoader,
                    "n",
                    boolean.class, int.class, int.class, float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                XposedBridge.log(TAG + "[Theme]: oc.n isScrollable false->true");
                                param.args[0] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook oc.n OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook oc.n failed: " + t);
        }
    }

    private void hookThemeN5r1(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> screenTypeClass = XposedHelpers.findClass(
                    "com.android.thememanager.util.WindowScreenUtils$ScreenType",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "n5r1",
                    boolean.class, screenTypeClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                XposedBridge.log(TAG + "[Theme]: n5r1 isScrollable false->true");
                                param.args[0] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook lh.n5r1 OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook lh.n5r1 failed: " + t);
        }
    }

    private void hookThemeL(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "l",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                XposedBridge.log(TAG + "[Theme]: lh.l isScrollable false->true");
                                param.args[0] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook lh.l OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook lh.l failed: " + t);
        }
    }

    private void hookThemeLrht(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "lrht",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return true;
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook lh.lrht -> true OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook lh.lrht failed: " + t);
        }
    }

    private void hookThemeGyi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "gyi",
                    Context.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean original = (boolean) param.args[1];
                            if (!original) {
                                XposedBridge.log(TAG + "[Theme]: gyi false->true");
                                param.args[1] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook lh.gyi OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook lh.gyi failed: " + t);
        }
    }

    private void hookThemeNi7(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ngyClass = XposedHelpers.findClass(
                    "com.android.thememanager.util.ngy", lpparam.classLoader);
            Class<?> applyInfosClass = XposedHelpers.findClass(
                    "com.android.thememanager.model.WallpaperApplyInfos", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "ni7",
                    Context.class,
                    String.class,
                    android.graphics.Bitmap.class,
                    android.graphics.Matrix.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    android.graphics.Bitmap.class,
                    ngyClass,
                    applyInfosClass,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean isLockscreen = (boolean) param.args[5];
                            boolean isScrollable = (boolean) param.args[6];
                            if (!isLockscreen && !isScrollable) {
                                param.args[6] = true;
                                XposedBridge.log(TAG + "[Theme]: ni7 isScrollable false->true");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook lh.ni7 OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook lh.ni7 failed: " + t);
        }
    }

    private void hookThemeApplyWallpaper(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wallpaperInfoClass;
        Class<?> wallpaperApplyParamClass;
        try {
            wallpaperInfoClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperInfo");
            wallpaperApplyParamClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.template.c");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[Theme]: Failed to load classes for applyWallpaper hooks: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                    lpparam.classLoader,
                    "qkj8",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class, boolean.class,
                    Bitmap.class, wallpaperApplyParamClass, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isLock = (boolean) param.args[3];
                            sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(TAG + "[Theme]: qkj8(applyImageWallpaper) isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(TAG + "[Theme]: Hook qkj8(applyImageWallpaper) OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[Theme]: Hook qkj8(applyImageWallpaper) failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                    lpparam.classLoader,
                    "d",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isLock = (boolean) param.args[2];
                            sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(TAG + "[Theme]: d(applyGalleryWallpaper) isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(TAG + "[Theme]: Hook d(applyGalleryWallpaper) OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[Theme]: Hook d(applyGalleryWallpaper) failed: " + t);
        }
    }

    // ================================================================
    // aodOS3: Hook TemplateApiImpl.cropBitmap() 跳过裁切
    // 仅对桌面壁纸跳过裁切，锁屏壁纸允许正常裁切
    // ================================================================
    private void hookAodTemplateApiCrop(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> wallpaperPositionInfo = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperPositionInfo");
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                    lpparam.classLoader,
                    "cropBitmap",
                    Bitmap.class, wallpaperPositionInfo, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Boolean isLock = sIsLockscreenWallpaper.get();
                            if (isLock != null && isLock) {
                                XposedBridge.log(TAG + "[AOD]: cropBitmap allowing normal crop for lockscreen wallpaper");
                                return;
                            }
                            Bitmap original = (Bitmap) param.args[0];
                            if (original != null) {
                                XposedBridge.log(TAG + "[AOD]: cropBitmap intercepted for home wallpaper, "
                                        + "returning original " + original.getWidth() + "x" + original.getHeight());
                                param.setResult(original);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "[AOD]: Hook TemplateApiImpl.cropBitmap OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[AOD]: Hook TemplateApiImpl.cropBitmap failed: " + t);
        }
    }

    // ================================================================
    // thememanagerOS3: Hook TemplateApiImpl.s() 跳过裁切
    // 仅对桌面壁纸跳过裁切，锁屏壁纸允许正常裁切
    // ================================================================
    private void hookThemeTemplateApiCrop(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> wallpaperPositionInfo = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperPositionInfo");
            XposedHelpers.findAndHookMethod(
                    "com.miui.keyguard.editor.data.template.TemplateApiImpl",
                    lpparam.classLoader,
                    "s",
                    Bitmap.class, wallpaperPositionInfo, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Boolean isLock = sIsLockscreenWallpaper.get();
                            if (isLock != null && isLock) {
                                XposedBridge.log(TAG + "[Theme]: s(cropBitmap) allowing normal crop for lockscreen wallpaper");
                                return;
                            }
                            Bitmap original = (Bitmap) param.args[0];
                            if (original != null) {
                                XposedBridge.log(TAG + "[Theme]: s(cropBitmap) intercepted for home wallpaper, "
                                        + "returning original " + original.getWidth() + "x" + original.getHeight());
                                param.setResult(original);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "[Theme]: Hook TemplateApiImpl.s OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[Theme]: Hook TemplateApiImpl.s failed: " + t);
        }
    }
}
