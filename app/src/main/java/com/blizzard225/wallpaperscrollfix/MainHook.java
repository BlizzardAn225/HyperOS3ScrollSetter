package com.blizzard225.wallpaperscrollfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

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

    private static final ThreadLocal<Boolean> sIsLockscreenWallpaper = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (WALLPAPER_PKG.equals(lpparam.packageName)) {
            hookWallpaperPkg(lpparam);
        } else if (THEME_PKG.equals(lpparam.packageName)) {
            hookThemePkg(lpparam);
        } else if (AOD_PKG.equals(lpparam.packageName)) {
            hookAodApp(lpparam);
        } else if (SETTER_PKG.equals(lpparam.packageName)) {
            hookSetterApp(lpparam);
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
                            XposedBridge.log(TAG + ": onCreate: forced scroll=1, observer registered");
                        }
                    }
            );
            XposedBridge.log(TAG + ": Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook MiWallpaperApplication.onCreate failed: " + t);
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
