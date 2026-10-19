package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.graphics.Bitmap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * com.miui.aod（AOD/锁屏编辑器）宿主 Hook：
 * 强制随屏滚动开关 + 桌面壁纸跳过裁切。
 */
class AodHostHook {

    private AodHostHook() {
    }

    // ================================================================
    // aodOS3 Hook (com.miui.aod)
    // ================================================================
    static void hookAodApp(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(MainHook.TAG + ": hooked into " + MainHook.AOD_PKG);
        hookAodEditorApplication(lpparam);
        hookAodTemplateApiCrop(lpparam);
        hookAodApplyWallpaper(lpparam);
    }

    private static void hookAodEditorApplication(XC_LoadPackage.LoadPackageParam lpparam) {
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
                            MainHook.registerScrollObserver(context);
                            android.provider.Settings.Secure.putInt(
                                    context.getContentResolver(), MainHook.SCROLL_KEY, 1);
                            XposedBridge.log(MainHook.TAG + "[AOD]: EditorApplication.onCreate, forced scroll=1");
                            return null;
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook EditorApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook EditorApplication.onCreate failed: " + t);
        }
    }

    private static void hookAodApplyWallpaper(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wallpaperInfoClass;
        Class<?> wallpaperApplyParamClass;
        try {
            wallpaperInfoClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperInfo");
            wallpaperApplyParamClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.template.WallpaperApplyParam");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[AOD]: Failed to load classes for applyWallpaper hooks: " + t);
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
                            MainHook.sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(MainHook.TAG + "[AOD]: applyImageWallpaper isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            MainHook.sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook applyImageWallpaper OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook applyImageWallpaper failed: " + t);
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
                            MainHook.sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(MainHook.TAG + "[AOD]: applyGalleryWallpaper isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            MainHook.sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook applyGalleryWallpaper OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook applyGalleryWallpaper failed: " + t);
        }
    }

    // ================================================================
    // aodOS3: Hook TemplateApiImpl.cropBitmap() 跳过裁切
    // 仅对桌面壁纸跳过裁切，锁屏壁纸允许正常裁切
    // ================================================================
    private static void hookAodTemplateApiCrop(XC_LoadPackage.LoadPackageParam lpparam) {
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
                            Boolean isLock = MainHook.sIsLockscreenWallpaper.get();
                            if (isLock != null && isLock) {
                                XposedBridge.log(MainHook.TAG + "[AOD]: cropBitmap allowing normal crop for lockscreen wallpaper");
                                return;
                            }
                            Bitmap original = (Bitmap) param.args[0];
                            if (original != null) {
                                XposedBridge.log(MainHook.TAG + "[AOD]: cropBitmap intercepted for home wallpaper, "
                                        + "returning original " + original.getWidth() + "x" + original.getHeight());
                                param.setResult(original);
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook TemplateApiImpl.cropBitmap OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[AOD]: Hook TemplateApiImpl.cropBitmap failed: " + t);
        }
    }
}
