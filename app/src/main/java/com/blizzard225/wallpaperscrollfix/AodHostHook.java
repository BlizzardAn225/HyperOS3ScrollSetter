package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.graphics.Bitmap;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

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
    static void hookAodApp(ClassLoader cl) {
        MainHook.log("hooked into " + MainHook.AOD_PKG);
        hookAodEditorApplication(cl);
        hookAodTemplateApiCrop(cl);
        hookAodApplyWallpaper(cl);
    }

    private static void hookAodEditorApplication(ClassLoader cl) {
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.kg.editor.EditorApplication", cl),
                    "onCreate");
            MainHook.xposed().hook(m).intercept(chain -> {
                chain.proceed();
                Context context = (Context) chain.getThisObject();
                MainHook.registerScrollObserver(context);
                android.provider.Settings.Secure.putInt(
                        context.getContentResolver(), MainHook.SCROLL_KEY, 1);
                MainHook.log("[AOD]: EditorApplication.onCreate, forced scroll=1");
                return null;
            });
            MainHook.log("[AOD]: Hook EditorApplication.onCreate OK");
        } catch (Throwable t) {
            MainHook.logError("[AOD]: Hook EditorApplication.onCreate failed", t);
        }
    }

    private static void hookAodApplyWallpaper(ClassLoader cl) {
        Class<?> wallpaperInfoClass;
        Class<?> wallpaperApplyParamClass;
        try {
            wallpaperInfoClass = cl.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperInfo");
            wallpaperApplyParamClass = cl.loadClass(
                    "com.miui.keyguard.editor.data.template.WallpaperApplyParam");
        } catch (Throwable t) {
            MainHook.logError("[AOD]: Failed to load classes for applyWallpaper hooks", t);
            return;
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.keyguard.editor.data.template.TemplateApiImpl", cl),
                    "applyImageWallpaper",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class, boolean.class,
                    Bitmap.class, wallpaperApplyParamClass, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean isLock = (Boolean) chain.getArg(3);
                MainHook.sIsLockscreenWallpaper.set(isLock);
                MainHook.log("[AOD]: applyImageWallpaper isLock=" + isLock);
                try {
                    return chain.proceed();
                } finally {
                    MainHook.sIsLockscreenWallpaper.remove();
                }
            });
            MainHook.log("[AOD]: Hook applyImageWallpaper OK");
        } catch (Throwable t) {
            MainHook.logError("[AOD]: Hook applyImageWallpaper failed", t);
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.keyguard.editor.data.template.TemplateApiImpl", cl),
                    "applyGalleryWallpaper",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean isLock = (Boolean) chain.getArg(2);
                MainHook.sIsLockscreenWallpaper.set(isLock);
                MainHook.log("[AOD]: applyGalleryWallpaper isLock=" + isLock);
                try {
                    return chain.proceed();
                } finally {
                    MainHook.sIsLockscreenWallpaper.remove();
                }
            });
            MainHook.log("[AOD]: Hook applyGalleryWallpaper OK");
        } catch (Throwable t) {
            MainHook.logError("[AOD]: Hook applyGalleryWallpaper failed", t);
        }
    }

    // ================================================================
    // aodOS3: Hook TemplateApiImpl.cropBitmap() 跳过裁切
    // 仅对桌面壁纸跳过裁切，锁屏壁纸允许正常裁切
    // ================================================================
    private static void hookAodTemplateApiCrop(ClassLoader cl) {
        try {
            Class<?> wallpaperPositionInfo = cl.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperPositionInfo");
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.keyguard.editor.data.template.TemplateApiImpl", cl),
                    "cropBitmap",
                    Bitmap.class, wallpaperPositionInfo, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                Boolean isLock = MainHook.sIsLockscreenWallpaper.get();
                if (isLock != null && isLock) {
                    MainHook.log("[AOD]: cropBitmap allowing normal crop for lockscreen wallpaper");
                    return chain.proceed();
                }
                Bitmap original = (Bitmap) chain.getArg(0);
                if (original != null) {
                    MainHook.log("[AOD]: cropBitmap intercepted for home wallpaper, "
                            + "returning original " + original.getWidth() + "x" + original.getHeight());
                    return original;
                }
                return chain.proceed();
            });
            MainHook.log("[AOD]: Hook TemplateApiImpl.cropBitmap OK");
        } catch (Throwable t) {
            MainHook.logError("[AOD]: Hook TemplateApiImpl.cropBitmap failed", t);
        }
    }
}
