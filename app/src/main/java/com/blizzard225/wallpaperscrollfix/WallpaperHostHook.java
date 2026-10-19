package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.graphics.Rect;
import android.util.Size;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * com.miui.miwallpaper（壁纸 APP）宿主 Hook：
 *   OS3 分支——阻止强制关闭随屏滚动 + 防压暗 + 锁屏跟随滚动；
 *   OS4 分支——随屏滚动保活 + reportSurfaceSize 去钳制 + 防压暗。
 */
class WallpaperHostHook {

    private WallpaperHostHook() {
    }

    // ================================================================
    // com.miui.miwallpaper：阻止 OS3 强制关闭随屏滚动
    // ================================================================
    static void hookWallpaperPkg(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(MainHook.TAG + ": hooked into " + MainHook.WALLPAPER_PKG);

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
            XposedBridge.log(MainHook.TAG + ": Hook isOs3AtLeast OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook isOs3AtLeast failed: " + t);
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
                            XposedBridge.log(MainHook.TAG + ": checkWallpaperScroll blocked");
                            return null;
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook checkWallpaperScroll OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook checkWallpaperScroll failed: " + t);
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
                            android.provider.Settings.Secure.putInt(
                                    context.getContentResolver(), MainHook.SCROLL_KEY, 1);
                            MainHook.registerScrollObserver(context);
                            // 读取防压暗开关初值（进程重启后恢复上次状态）
                            MainHook.sAntiDarkenEnabled = android.provider.Settings.Secure.getInt(
                                    context.getContentResolver(), MainHook.ANTI_DARKEN_KEY, 0) == 1;
                            MainHook.registerAntiDarkenReceiver(context);
                            XposedBridge.log(MainHook.TAG + ": onCreate: forced scroll=1, antiDarken="
                                    + MainHook.sAntiDarkenEnabled);
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook MiWallpaperApplication.onCreate failed: " + t);
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
                            if (!MainHook.sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            XposedBridge.log(MainHook.TAG + ": getIsDarkWallpaperColor -> false");
                            return false;
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook getIsDarkWallpaperColor OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook getIsDarkWallpaperColor failed: " + t);
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
                            if (!MainHook.sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            XposedBridge.log(MainHook.TAG + ": getWallpaperIsDark -> false");
                            return false;
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook getWallpaperIsDark OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook getWallpaperIsDark failed: " + t);
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (MainHook.sAntiDarkenEnabled) {
                                XposedBridge.log(MainHook.TAG + ": updateMaskLayerStatus -> false, false");
                                param.args[0] = false;
                                param.args[1] = false;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook AnimImageGLProgram.updateMaskLayerStatus OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook AnimImageGLProgram.updateMaskLayerStatus failed: " + t);
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
                                XposedBridge.log(MainHook.TAG + ": setDesktopWallpaperShowWhenLocked false->true");
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + ": Hook setDesktopWallpaperShowWhenLocked OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Hook setDesktopWallpaperShowWhenLocked failed: " + t);
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
    static void hookWallpaperPkgOs4(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(MainHook.TAG + ": hooked into " + MainHook.WALLPAPER_PKG + " (HyperOS4 host)");

        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.MiWallpaperApplication",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.thisObject;
                            android.provider.Settings.Secure.putInt(
                                    context.getContentResolver(), MainHook.SCROLL_KEY, 1);
                            MainHook.registerScrollObserver(context);
                            // 防压暗开关：读取持久化状态并接收 APP 广播（与 OS3 同一套机制）
                            MainHook.sAntiDarkenEnabled = android.provider.Settings.Secure.getInt(
                                    context.getContentResolver(), MainHook.ANTI_DARKEN_KEY, 0) == 1;
                            MainHook.registerAntiDarkenReceiver(context);
                            XposedBridge.log(MainHook.TAG + "[OS4]: onCreate: forced scroll=1, antiDarken="
                                    + MainHook.sAntiDarkenEnabled);
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook MiWallpaperApplication.onCreate failed: " + t);
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
                                XposedBridge.log(MainHook.TAG + "[OS4]: blocked SystemSettingUtils.x(0)");
                                param.setResult(null);
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook SystemSettingUtils.x guard OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook SystemSettingUtils.x guard failed: " + t);
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
    private static void hookOs4AntiDarken(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.miwallpaper.manager.WallpaperServiceController",
                    lpparam.classLoader,
                    "m",
                    int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            if (!MainHook.sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            return false;
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook WallpaperServiceController.m(int) OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook WallpaperServiceController.m(int) failed: " + t);
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
                            if (!MainHook.sAntiDarkenEnabled) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                            return Boolean.FALSE;
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook WallpaperServiceController.t(int) OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook WallpaperServiceController.t(int) failed: " + t);
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
                            if (MainHook.sAntiDarkenEnabled) {
                                XposedBridge.log(MainHook.TAG + "[OS4]: updateMaskLayerStatus -> false, false");
                                param.args[0] = false;
                                param.args[1] = false;
                            }
                        }
                    }
            );
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook AnimImageGLProgram.updateMaskLayerStatus OK");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook AnimImageGLProgram.updateMaskLayerStatus failed: " + t);
        }
    }

    // 用 OS3 的无钳制公式替换 reportSurfaceSize：
    //   fMax = max(screenW/bw, screenH/bh)（不钳到 1），surface = 位图 cover 尺寸；
    //   随屏滚动下仅当位图等比宽度不足 1.1×屏宽时才扩展到 1.1×屏宽（与 OS3 行为一致）。
    // WallpaperUtils.a()==true 的分支（部分机型/形态）本身无钳制，走原逻辑。
    private static void hookOs4ReportSurfaceSize(XC_LoadPackage.LoadPackageParam lpparam,
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
                            XposedBridge.log(MainHook.TAG + "[OS4]: reportSurfaceSize(unclamped) bitmap="
                                    + dims.width() + "x" + dims.height() + " screen=" + screenW
                                    + "x" + screenH + " scroll=" + scroll + " -> " + targetW + "x" + height2);
                            return new Size(targetW, height2);
                        }
                    });
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook reportSurfaceSize OK: " + className);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + "[OS4]: Hook reportSurfaceSize failed (" + className + "): " + t);
        }
    }

    // 桌面渲染器的滚动开关：优先读引擎自身的标志位（混淆后运行时字段名为 "d"），失败则回落设置值
    private static boolean isOs4ScrollEnabled(Object renderer, ClassLoader classLoader) {
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
}
