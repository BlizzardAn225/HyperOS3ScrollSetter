package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.graphics.Bitmap;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * com.android.thememanager（主题）宿主 Hook：
 * 强开壁纸 isScrollable 相关参数 + 桌面壁纸跳过裁切。
 */
class ThemeHostHook {

    private ThemeHostHook() {
    }

    // ================================================================
    // com.android.thememanager
    // ================================================================
    static void hookThemePkg(ClassLoader cl) {
        MainHook.log("hooked into " + MainHook.THEME_PKG);

        hookThemeOcQ(cl);
        hookThemeOcN(cl);
        hookThemeN5r1(cl);
        hookThemeL(cl);
        hookThemeLrht(cl);
        hookThemeGyi(cl);
        hookThemeNi7(cl);
        hookThemeTemplateApiCrop(cl);
        hookThemeApplyWallpaper(cl);
    }

    private static void hookThemeOcQ(ClassLoader cl) {
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.oc", cl),
                    "q", int.class, int.class, boolean.class, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean original = (Boolean) chain.getArg(2);
                if (!original) {
                    MainHook.log("[Theme]: oc.q isScrollable false->true "
                            + " img=" + chain.getArg(0) + "x" + chain.getArg(1));
                    return chain.proceed(new Object[]{
                            chain.getArg(0), chain.getArg(1), true, chain.getArg(3)});
                }
                return chain.proceed();
            });
            MainHook.log("Hook oc.q OK");
        } catch (Throwable t) {
            MainHook.logError("Hook oc.q failed", t);
        }
    }

    private static void hookThemeOcN(ClassLoader cl) {
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.oc", cl),
                    "n", boolean.class, int.class, int.class, float.class, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean original = (Boolean) chain.getArg(0);
                if (!original) {
                    MainHook.log("[Theme]: oc.n isScrollable false->true");
                    return chain.proceed(new Object[]{
                            true, chain.getArg(1), chain.getArg(2), chain.getArg(3), chain.getArg(4)});
                }
                return chain.proceed();
            });
            MainHook.log("Hook oc.n OK");
        } catch (Throwable t) {
            MainHook.logError("Hook oc.n failed", t);
        }
    }

    private static void hookThemeN5r1(ClassLoader cl) {
        try {
            Class<?> screenTypeClass = XH.findClass(
                    "com.android.thememanager.util.WindowScreenUtils$ScreenType", cl);
            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.lh", cl),
                    "n5r1", boolean.class, screenTypeClass);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean original = (Boolean) chain.getArg(0);
                if (!original) {
                    MainHook.log("[Theme]: n5r1 isScrollable false->true");
                    return chain.proceed(new Object[]{true, chain.getArg(1)});
                }
                return chain.proceed();
            });
            MainHook.log("Hook lh.n5r1 OK");
        } catch (Throwable t) {
            MainHook.logError("Hook lh.n5r1 failed", t);
        }
    }

    private static void hookThemeL(ClassLoader cl) {
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.lh", cl),
                    "l", boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean original = (Boolean) chain.getArg(0);
                if (!original) {
                    MainHook.log("[Theme]: lh.l isScrollable false->true");
                    return chain.proceed(new Object[]{true});
                }
                return chain.proceed();
            });
            MainHook.log("Hook lh.l OK");
        } catch (Throwable t) {
            MainHook.logError("Hook lh.l failed", t);
        }
    }

    private static void hookThemeLrht(ClassLoader cl) {
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.lh", cl),
                    "lrht");
            MainHook.xposed().hook(m).intercept(chain -> true);
            MainHook.log("Hook lh.lrht -> true OK");
        } catch (Throwable t) {
            MainHook.logError("Hook lh.lrht failed", t);
        }
    }

    private static void hookThemeGyi(ClassLoader cl) {
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.lh", cl),
                    "gyi", Context.class, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean original = (Boolean) chain.getArg(1);
                if (!original) {
                    MainHook.log("[Theme]: gyi false->true");
                    return chain.proceed(new Object[]{chain.getArg(0), true});
                }
                return chain.proceed();
            });
            MainHook.log("Hook lh.gyi OK");
        } catch (Throwable t) {
            MainHook.logError("Hook lh.gyi failed", t);
        }
    }

    private static void hookThemeNi7(ClassLoader cl) {
        try {
            Class<?> ngyClass = XH.findClass("com.android.thememanager.util.ngy", cl);
            Class<?> applyInfosClass = XH.findClass(
                    "com.android.thememanager.model.WallpaperApplyInfos", cl);

            Method m = XH.findMethodExact(
                    XH.findClass("com.android.thememanager.util.lh", cl),
                    "ni7",
                    Context.class,
                    String.class,
                    Bitmap.class,
                    android.graphics.Matrix.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    Bitmap.class,
                    ngyClass,
                    applyInfosClass,
                    boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean isLockscreen = (Boolean) args[5];
                boolean isScrollable = (Boolean) args[6];
                if (!isLockscreen && !isScrollable) {
                    args[6] = true;
                    MainHook.log("[Theme]: ni7 isScrollable false->true");
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            MainHook.log("Hook lh.ni7 OK");
        } catch (Throwable t) {
            MainHook.logError("Hook lh.ni7 failed", t);
        }
    }

    private static void hookThemeApplyWallpaper(ClassLoader cl) {
        Class<?> wallpaperInfoClass;
        Class<?> wallpaperApplyParamClass;
        try {
            wallpaperInfoClass = cl.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperInfo");
            wallpaperApplyParamClass = cl.loadClass(
                    "com.miui.keyguard.editor.data.template.c");
        } catch (Throwable t) {
            MainHook.logError("[Theme]: Failed to load classes for applyWallpaper hooks", t);
            return;
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.keyguard.editor.data.template.TemplateApiImpl", cl),
                    "qkj8",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class, boolean.class,
                    Bitmap.class, wallpaperApplyParamClass, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean isLock = (Boolean) chain.getArg(3);
                MainHook.sIsLockscreenWallpaper.set(isLock);
                MainHook.log("[Theme]: qkj8(applyImageWallpaper) isLock=" + isLock);
                try {
                    return chain.proceed();
                } finally {
                    MainHook.sIsLockscreenWallpaper.remove();
                }
            });
            MainHook.log("[Theme]: Hook qkj8(applyImageWallpaper) OK");
        } catch (Throwable t) {
            MainHook.logError("[Theme]: Hook qkj8(applyImageWallpaper) failed", t);
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.keyguard.editor.data.template.TemplateApiImpl", cl),
                    "d",
                    wallpaperInfoClass, String.class,
                    boolean.class, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                boolean isLock = (Boolean) chain.getArg(2);
                MainHook.sIsLockscreenWallpaper.set(isLock);
                MainHook.log("[Theme]: d(applyGalleryWallpaper) isLock=" + isLock);
                try {
                    return chain.proceed();
                } finally {
                    MainHook.sIsLockscreenWallpaper.remove();
                }
            });
            MainHook.log("[Theme]: Hook d(applyGalleryWallpaper) OK");
        } catch (Throwable t) {
            MainHook.logError("[Theme]: Hook d(applyGalleryWallpaper) failed", t);
        }
    }

    // ================================================================
    // thememanagerOS3: Hook TemplateApiImpl.s() 跳过裁切
    // 仅对桌面壁纸跳过裁切，锁屏壁纸允许正常裁切
    // ================================================================
    private static void hookThemeTemplateApiCrop(ClassLoader cl) {
        try {
            Class<?> wallpaperPositionInfo = cl.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperPositionInfo");
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.keyguard.editor.data.template.TemplateApiImpl", cl),
                    "s",
                    Bitmap.class, wallpaperPositionInfo, boolean.class);
            MainHook.xposed().hook(m).intercept(chain -> {
                Boolean isLock = MainHook.sIsLockscreenWallpaper.get();
                if (isLock != null && isLock) {
                    MainHook.log("[Theme]: s(cropBitmap) allowing normal crop for lockscreen wallpaper");
                    return chain.proceed();
                }
                Bitmap original = (Bitmap) chain.getArg(0);
                if (original != null) {
                    MainHook.log("[Theme]: s(cropBitmap) intercepted for home wallpaper, "
                            + "returning original " + original.getWidth() + "x" + original.getHeight());
                    return original;
                }
                return chain.proceed();
            });
            MainHook.log("[Theme]: Hook TemplateApiImpl.s OK");
        } catch (Throwable t) {
            MainHook.logError("[Theme]: Hook TemplateApiImpl.s failed", t);
        }
    }
}
