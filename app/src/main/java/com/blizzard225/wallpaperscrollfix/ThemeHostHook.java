package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.graphics.Bitmap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
    static void hookThemePkg(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(MainHook.TAG + ": hooked into " + MainHook.THEME_PKG);

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

    private static void hookThemeOcQ(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.oc",
                    lpparam.classLoader,
                    "q",
                    int.class, int.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean original = (boolean) param.args[2];
                            if (!original) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: oc.q isScrollable false->true "
                                        + " img=" + param.args[0] + "x" + param.args[1]);
                                param.args[2] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook oc.q OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook oc.q failed: " + t);
        }
    }

    private static void hookThemeOcN(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.oc",
                    lpparam.classLoader,
                    "n",
                    boolean.class, int.class, int.class, float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: oc.n isScrollable false->true");
                                param.args[0] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook oc.n OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook oc.n failed: " + t);
        }
    }

    private static void hookThemeN5r1(XC_LoadPackage.LoadPackageParam lpparam) {
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: n5r1 isScrollable false->true");
                                param.args[0] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook lh.n5r1 OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook lh.n5r1 failed: " + t);
        }
    }

    private static void hookThemeL(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "l",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean original = (boolean) param.args[0];
                            if (!original) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: lh.l isScrollable false->true");
                                param.args[0] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook lh.l OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook lh.l failed: " + t);
        }
    }

    private static void hookThemeLrht(XC_LoadPackage.LoadPackageParam lpparam) {
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
            XposedBridge.log(MainHook.TAG + ": Hook lh.lrht -> true OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook lh.lrht failed: " + t);
        }
    }

    private static void hookThemeGyi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.thememanager.util.lh",
                    lpparam.classLoader,
                    "gyi",
                    Context.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean original = (boolean) param.args[1];
                            if (!original) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: gyi false->true");
                                param.args[1] = true;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook lh.gyi OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook lh.gyi failed: " + t);
        }
    }

    private static void hookThemeNi7(XC_LoadPackage.LoadPackageParam lpparam) {
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
                    Bitmap.class,
                    android.graphics.Matrix.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    Bitmap.class,
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
                                XposedBridge.log(MainHook.TAG + "[Theme]: ni7 isScrollable false->true");
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook lh.ni7 OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook lh.ni7 failed: " + t);
        }
    }

    private static void hookThemeApplyWallpaper(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wallpaperInfoClass;
        Class<?> wallpaperApplyParamClass;
        try {
            wallpaperInfoClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.bean.WallpaperInfo");
            wallpaperApplyParamClass = lpparam.classLoader.loadClass(
                    "com.miui.keyguard.editor.data.template.c");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[Theme]: Failed to load classes for applyWallpaper hooks: " + t);
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean isLock = (boolean) param.args[3];
                            MainHook.sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(MainHook.TAG + "[Theme]: qkj8(applyImageWallpaper) isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            MainHook.sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[Theme]: Hook qkj8(applyImageWallpaper) OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[Theme]: Hook qkj8(applyImageWallpaper) failed: " + t);
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean isLock = (boolean) param.args[2];
                            MainHook.sIsLockscreenWallpaper.set(isLock);
                            XposedBridge.log(MainHook.TAG + "[Theme]: d(applyGalleryWallpaper) isLock=" + isLock);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            MainHook.sIsLockscreenWallpaper.remove();
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[Theme]: Hook d(applyGalleryWallpaper) OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[Theme]: Hook d(applyGalleryWallpaper) failed: " + t);
        }
    }

    // ================================================================
    // thememanagerOS3: Hook TemplateApiImpl.s() 跳过裁切
    // 仅对桌面壁纸跳过裁切，锁屏壁纸允许正常裁切
    // ================================================================
    private static void hookThemeTemplateApiCrop(XC_LoadPackage.LoadPackageParam lpparam) {
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
                            Boolean isLock = MainHook.sIsLockscreenWallpaper.get();
                            if (isLock != null && isLock) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: s(cropBitmap) allowing normal crop for lockscreen wallpaper");
                                return;
                            }
                            Bitmap original = (Bitmap) param.args[0];
                            if (original != null) {
                                XposedBridge.log(MainHook.TAG + "[Theme]: s(cropBitmap) intercepted for home wallpaper, "
                                        + "returning original " + original.getWidth() + "x" + original.getHeight());
                                param.setResult(original);
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[Theme]: Hook TemplateApiImpl.s OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[Theme]: Hook TemplateApiImpl.s failed: " + t);
        }
    }
}
