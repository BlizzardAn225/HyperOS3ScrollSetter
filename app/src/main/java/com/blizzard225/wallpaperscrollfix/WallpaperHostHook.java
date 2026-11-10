package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.graphics.Rect;
import android.util.Size;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * com.miui.miwallpaper（壁纸 APP）宿主 Hook：
 *   OS3 分支——阻止强制关闭随屏滚动 + 防压暗 + 锁屏跟随滚动；
 *   OS4 分支——随屏滚动保活 + reportSurfaceSize 去钳制 + 防压暗。
 *
 * 现代 API（102）：hook 走 {@code hook(Executable).intercept(Hooker)} 拦截器链——
 *   旧 beforeHookedMethod → proceed() 之前改参用 chain.proceed(newArgs)；
 *   旧 afterHookedMethod → proceed() 之后处理其返回值；
 *   旧 XC_MethodReplacement → 不调 proceed 直接返回；
 *   旧 XposedBridge.invokeOriginalMethod → chain.proceed()。
 */
class WallpaperHostHook {

    private static final String WALLPAPER_CTRL_CLASS =
            "com.miui.miwallpaper.manager.WallpaperServiceController";

    private WallpaperHostHook() {
    }

    // ================================================================
    // com.miui.miwallpaper：阻止 OS3 强制关闭随屏滚动
    // ================================================================
    static void hookWallpaperPkg(ClassLoader cl) {
        XposedInterface xp = MainHook.xposed();
        MainHook.log("hooked into " + MainHook.WALLPAPER_PKG);

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.utils.SystemBuildUtil", cl),
                    "isOs3AtLeast");
            xp.hook(m).intercept(chain -> false);
            MainHook.log("Hook isOs3AtLeast OK");
        } catch (Throwable t) {
            MainHook.logError("Hook isOs3AtLeast failed", t);
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.manager.MiuiWallpaperManager", cl),
                    "checkWallpaperScroll", Context.class);
            xp.hook(m).intercept(chain -> {
                MainHook.log("checkWallpaperScroll blocked");
                return null;
            });
            MainHook.log("Hook checkWallpaperScroll OK");
        } catch (Throwable t) {
            MainHook.logError("Hook checkWallpaperScroll failed", t);
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.MiWallpaperApplication", cl),
                    "onCreate");
            xp.hook(m).intercept(chain -> {
                chain.proceed();
                Context context = (Context) chain.getThisObject();
                android.provider.Settings.Secure.putInt(
                        context.getContentResolver(), MainHook.SCROLL_KEY, 1);
                MainHook.registerScrollObserver(context);
                // 读取防压暗开关初值（进程重启后恢复上次状态）
                MainHook.sAntiDarkenEnabled = android.provider.Settings.Secure.getInt(
                        context.getContentResolver(), MainHook.ANTI_DARKEN_KEY, 0) == 1;
                MainHook.registerAntiDarkenReceiver(context);
                MainHook.log("onCreate: forced scroll=1, antiDarken="
                        + MainHook.sAntiDarkenEnabled);
                return null;
            });
            MainHook.log("Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            MainHook.logError("Hook MiWallpaperApplication.onCreate failed", t);
        }

        // 壁纸压暗 hook：勾选"防压暗"时才阻止 getIsDarkWallpaperColor 返回 true
        // 使 shader 的 uDarken=0，transformColor 压暗失效，壁纸保持原色。
        try {
            Method m = XH.findMethodExact(
                    XH.findClass(WALLPAPER_CTRL_CLASS, cl),
                    "getIsDarkWallpaperColor", int.class);
            xp.hook(m).intercept(chain -> {
                if (!MainHook.sAntiDarkenEnabled) {
                    return chain.proceed();
                }
                MainHook.log("getIsDarkWallpaperColor -> false");
                return false;
            });
            MainHook.log("Hook getIsDarkWallpaperColor OK");
        } catch (Throwable t) {
            MainHook.logError("Hook getIsDarkWallpaperColor failed", t);
        }

        // 壁纸压暗 hook：勾选"防压暗"时才阻止 getWallpaperIsDark 返回 true
        try {
            Method m = XH.findMethodExact(
                    XH.findClass(WALLPAPER_CTRL_CLASS, cl),
                    "getWallpaperIsDark", int.class);
            xp.hook(m).intercept(chain -> {
                if (!MainHook.sAntiDarkenEnabled) {
                    return chain.proceed();
                }
                MainHook.log("getWallpaperIsDark -> false");
                return false;
            });
            MainHook.log("Hook getWallpaperIsDark OK");
        } catch (Throwable t) {
            MainHook.logError("Hook getWallpaperIsDark failed", t);
        }

        // 壁纸压暗 hook：AnimImageGLProgram.updateMaskLayerStatus(boolean, boolean)
        // 桌面/锁屏渲染都汇聚到这里，勾选"防压暗"时强制两参为 false（uDarken=0, uDarkMode=0），
        // 使 shader 恒走 fragColor = rgba 输出原色，一次 hook 全覆盖。
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram", cl),
                    "updateMaskLayerStatus", boolean.class, boolean.class);
            xp.hook(m).intercept(chain -> {
                if (MainHook.sAntiDarkenEnabled) {
                    MainHook.log("updateMaskLayerStatus -> false, false");
                    return chain.proceed(new Object[]{false, false});
                }
                return chain.proceed();
            });
            MainHook.log("Hook AnimImageGLProgram.updateMaskLayerStatus OK");
        } catch (Throwable t) {
            MainHook.logError("Hook AnimImageGLProgram.updateMaskLayerStatus failed", t);
        }

        // 强制桌面壁纸在锁屏上可见，使同图壁纸时锁屏跟随桌面滚动
        try {
            Method m = XH.findMethodExact(
                    XH.findClass(WALLPAPER_CTRL_CLASS, cl),
                    "setDesktopWallpaperShowWhenLocked", boolean.class);
            xp.hook(m).intercept(chain -> {
                boolean original = (Boolean) chain.getArg(0);
                if (!original) {
                    MainHook.log("setDesktopWallpaperShowWhenLocked false->true");
                    return chain.proceed(new Object[]{true});
                }
                return chain.proceed();
            });
            MainHook.log("Hook setDesktopWallpaperShowWhenLocked OK");
        } catch (Throwable t) {
            MainHook.logError("Hook setDesktopWallpaperShowWhenLocked failed", t);
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
    static void hookWallpaperPkgOs4(ClassLoader cl) {
        XposedInterface xp = MainHook.xposed();
        MainHook.log("hooked into " + MainHook.WALLPAPER_PKG + " (HyperOS4 host)");

        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.MiWallpaperApplication", cl),
                    "onCreate");
            xp.hook(m).intercept(chain -> {
                chain.proceed();
                Context context = (Context) chain.getThisObject();
                android.provider.Settings.Secure.putInt(
                        context.getContentResolver(), MainHook.SCROLL_KEY, 1);
                MainHook.registerScrollObserver(context);
                // 防压暗开关：读取持久化状态并接收 APP 广播（与 OS3 同一套机制）
                MainHook.sAntiDarkenEnabled = android.provider.Settings.Secure.getInt(
                        context.getContentResolver(), MainHook.ANTI_DARKEN_KEY, 0) == 1;
                MainHook.registerAntiDarkenReceiver(context);
                MainHook.log("[OS4]: onCreate: forced scroll=1, antiDarken="
                        + MainHook.sAntiDarkenEnabled);
                return null;
            });
            MainHook.log("[OS4]: Hook MiWallpaperApplication.onCreate OK");
        } catch (Throwable t) {
            MainHook.logError("[OS4]: Hook MiWallpaperApplication.onCreate failed", t);
        }

        // 随屏滚动保活：吞掉 SystemSettingUtils.x(0)（x(1) 等其余写入不受影响）
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.utils.SystemSettingUtils", cl),
                    "x", int.class);
            xp.hook(m).intercept(chain -> {
                if ((Integer) chain.getArg(0) == 0) {
                    MainHook.log("[OS4]: blocked SystemSettingUtils.x(0)");
                    return null;
                }
                return chain.proceed();
            });
            MainHook.log("[OS4]: Hook SystemSettingUtils.x guard OK");
        } catch (Throwable t) {
            MainHook.logError("[OS4]: Hook SystemSettingUtils.x guard failed", t);
        }

        hookOs4ReportSurfaceSize(cl,
                "com.miui.miwallpaper.container.openGL.DesktopAnimImageWallpaperRenderer", true);
        hookOs4ReportSurfaceSize(cl,
                "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer", false);
        hookOs4AntiDarken(cl);
    }

    // ================================================================
    // OS4 防压暗（按宿主壁纸引擎版本子路由）。压暗语义三代不变：
    //   uDarkMode（轻度 tint，来源旧 E(int)/新 F(int)：暗色开 && type≠DARK）
    //   uDarken（强压暗，来源旧 m(int)/新 n(int)←u(int) 缓存，依据 WallpaperColors 暗色 hint，
    //     HINT_SUPPORTS_DARK=1 → 壁纸自证暗色 → 不压暗）
    //   汇聚点 AnimImageGLProgram.updateMaskLayerStatus(boolean,boolean) 三代同签名。
    // 版本差异（wallpaperOS4_7.1.3-ALPHA 树实证）：
    //   OS4.0.4 系：m(int)/t(int)；
    //   7.1.3-ALPHA 系：m/t 消亡，E 并入 F(int)（:408 = darkMode && type≠DARK && B()），
    //     t 拆为 n(int)（:1096 读缓存）+ u(int)（:1211 算缓存），调用侧
    //     updateMaskLayerStatus(F(1), n(1))——F/n 语义相对 m/t 对调。
    // ================================================================
    private static void hookOs4AntiDarken(ClassLoader cl) {
        XposedInterface xp = MainHook.xposed();
        boolean hasFn = XH.findMethodExactIfExists(
                XH.findClass(WALLPAPER_CTRL_CLASS, cl), "F", int.class) != null
                && XH.findMethodExactIfExists(
                XH.findClass(WALLPAPER_CTRL_CLASS, cl), "n", int.class) != null;
        if (hasFn) {
            hookOs4AntiDarkenAlphaFn(cl);
        } else {
            MainHook.log("[OS4]: antiDarken: no F/n, maskLayer backup only");
        }
        hookMaskLayerStatusBackup(cl);
    }

    // 7.1.3-ALPHA 系：F(int)→uDarkMode、n(int)→uDarken，均恒 false（勾选防压暗时）
    private static void hookOs4AntiDarkenAlphaFn(ClassLoader cl) {
        XposedInterface xp = MainHook.xposed();
        try {
            Method m = XH.findMethodExact(
                    XH.findClass(WALLPAPER_CTRL_CLASS, cl), "F", int.class);
            xp.hook(m).intercept(chain -> {
                if (!MainHook.sAntiDarkenEnabled) {
                    return chain.proceed();
                }
                return false;
            });
            MainHook.log("[OS4]: Hook WallpaperServiceController.F(int) OK (7.1.3-ALPHA)");
        } catch (Throwable t) {
            MainHook.logError("[OS4]: Hook WallpaperServiceController.F(int) failed", t);
        }

        try {
            Method m = XH.findMethodExact(
                    XH.findClass(WALLPAPER_CTRL_CLASS, cl), "n", int.class);
            xp.hook(m).intercept(chain -> {
                if (!MainHook.sAntiDarkenEnabled) {
                    return chain.proceed();
                }
                return false;
            });
            MainHook.log("[OS4]: Hook WallpaperServiceController.n(int) OK (7.1.3-ALPHA)");
        } catch (Throwable t) {
            MainHook.logError("[OS4]: Hook WallpaperServiceController.n(int) failed", t);
        }
    }

    // 汇聚点保底：三代同签名，勾选防压暗时强制 uDarkMode=0、uDarken=0
    private static void hookMaskLayerStatusBackup(ClassLoader cl) {
        XposedInterface xp = MainHook.xposed();
        try {
            Method m = XH.findMethodExact(
                    XH.findClass("com.miui.miwallpaper.opengl.ordinary.AnimImageGLProgram", cl),
                    "updateMaskLayerStatus", boolean.class, boolean.class);
            xp.hook(m).intercept(chain -> {
                if (MainHook.sAntiDarkenEnabled) {
                    return chain.proceed(new Object[]{false, false});
                }
                return chain.proceed();
            });
            MainHook.log("[OS4]: Hook AnimImageGLProgram.updateMaskLayerStatus OK");
        } catch (Throwable t) {
            MainHook.logError("[OS4]: Hook AnimImageGLProgram.updateMaskLayerStatus failed", t);
        }
    }

    // 用 OS3 的无钳制公式替换 reportSurfaceSize：
    //   fMax = max(screenW/bw, screenH/bh)（不钳到 1），surface = 位图 cover 尺寸；
    //   随屏滚动下仅当位图等比宽度不足 1.1×屏宽时才扩展到 1.1×屏宽（与 OS3 行为一致）。
    // WallpaperUtils.a()==true 的分支（部分机型/形态）本身无钳制，走原逻辑。
    private static void hookOs4ReportSurfaceSize(ClassLoader cl, String className,
                                                 final boolean desktop) {
        XposedInterface xp = MainHook.xposed();
        try {
            Method m = XH.findMethodExact(XH.findClass(className, cl), "reportSurfaceSize");
            xp.hook(m).intercept(chain -> {
                Object renderer = chain.getThisObject();
                ClassLoader rendererCl = renderer.getClass().getClassLoader();
                Class<?> wallpaperUtils = XH.findClass(
                        "com.miui.miwallpaper.utils.WallpaperUtils", rendererCl);
                if (XH.callStaticMethod(wallpaperUtils, "a") == Boolean.TRUE) {
                    return chain.proceed();
                }
                Context context = (Context) XH.getObjectField(renderer, "mContext");
                Rect screen = (Rect) XH.callStaticMethod(
                        XH.findClass("com.miui.miwallpaper.utils.ScreenUtils", rendererCl),
                        "a", context);
                Object texture = XH.getObjectField(renderer, "mTexture");
                XH.callMethodExact(texture, "use",
                        new Class[]{java.util.function.Consumer.class}, new Object[]{null});
                Rect dims = (Rect) XH.callMethod(texture, "getTextureDimensions");
                Rect surface = (Rect) XH.getObjectField(renderer, "mSurfaceSize");
                surface.set(screen);
                int screenW = surface.width();
                int screenH = surface.height();
                boolean scroll = desktop && isOs4ScrollEnabled(renderer, rendererCl);
                // OS3 公式：fMax 不钳制
                float scale = Math.max(screenW / (float) dims.width(),
                        screenH / (float) dims.height());
                int width2 = (int) ((dims.width() * scale) + 0.5f);
                int height2 = (int) ((dims.height() * scale) + 0.5f);
                int targetW = (!scroll || ((double) width2) >= ((double) screenW) * 1.1d)
                        ? width2 : (int) (screenW * 1.1f);
                surface.set(0, 0, targetW, height2);
                MainHook.log("[OS4]: reportSurfaceSize(unclamped) bitmap="
                        + dims.width() + "x" + dims.height() + " screen=" + screenW
                        + "x" + screenH + " scroll=" + scroll + " -> " + targetW + "x" + height2);
                return new Size(targetW, height2);
            });
            MainHook.log("[OS4]: Hook reportSurfaceSize OK: " + className);
        } catch (Throwable t) {
            MainHook.logError("[OS4]: Hook reportSurfaceSize failed (" + className + ")", t);
        }
    }

    // 桌面渲染器的滚动开关：优先读引擎自身的标志位（混淆后运行时字段名为 "d"），失败则回落设置值
    private static boolean isOs4ScrollEnabled(Object renderer, ClassLoader classLoader) {
        try {
            return XH.getBooleanField(renderer, "d");
        } catch (Throwable t) {
            try {
                return XH.callStaticMethod(
                        XH.findClass("com.miui.miwallpaper.utils.SystemSettingUtils",
                                classLoader), "p") == Boolean.TRUE;
            } catch (Throwable t2) {
                return false;
            }
        }
    }
}
