package com.blizzard225.wallpaperscrollfix;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;

import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * 随屏滚动 × 壁纸缩放动画抖动修复（system_server 侧，HyperOS3/4）。
 *
 * 机制（4.0.2/4.0.5 反编译逐行核对 + piano OS4.0.4 实测，详见 交接文档 §9/§12）。
 * 壁纸 Surface 的位置存在三个写入者：
 *   A. MiuiWallpaperSurfaceAnimation：动画每帧 setMatrix(Matrix,float[9]) 直写
 *      完整矩阵，平移 = z·t_anim+(1-z)·P；矩阵烘焙的 z 在 SF 中持久，中断即冻结。
 *      t_anim 来自 WallpaperAnimationTarget——WallpaperController.getWallpaperSurface
 *      按设备分叉：平板/折叠(:880)传 mXOffset/mYOffset；手机(:875)传
 *      mPortraitXOffset/mPortraitYOffset（stub 在"任意竖屏 && dy>=0"时逐字直拷
 *      mXOffset，横屏发散：WindowStateStubImpl:652-659）。
 *   B. WindowState.updateSurfacePosition：mWallpaperScale==1 时写裸 offset；
 *      !=1 时 zoom 分支（:4355-4362）写 frame+round(z_w·offset+(1-z_w)·pivot_w)。
 *   C. zoom 通道 mWallpaperScale：唯一写点 = 动画 onComplete，进应用后驻留 ≠1，
 *      直到 setShouldZoomOutWallpaper(false) 或下一次 scaleTo=1 的 complete。
 *
 * 冲突本质：动画进行中/中断残留期，矩阵烘焙 z ≠ 通道 z_w——B 按 z_w 写、A 按
 * 矩阵 z 写，逐帧互殴（v1/v2 的 z² 序列即其产物）。本模块在 B 的写入口把位置
 * 统一到矩阵实际烘焙的 z。
 *
 * v3.2（外部评审修订，2026-11-09）：
 *   1. 干预判据 |z-z_w|>=ε 取代 |z-1|>=ε：冲突的定义是动画 z 与通道 z_w 不一致。
 *      修掉"尾帧窗口"——z_anim 弹簧收敛入 ε 而通道仍 1.18 时，框架若写位置按
 *      1.18 算（偏差 (z_w-1)|t-P|，t=-519 时约 381px），v3 判据在该窗口退场漏补；
 *      稳态（z=z_w）也不再空转。
 *   2. 失效 belt hook WindowState.updateScaleIfNeeded()（无参私有，:4272-4285）：
 *      mLastHScale/VScale 前后变化 ⟺ 框架重写了矩阵线性（mHScale/globalScale/
 *      mWallpaperScale 任一变化）⇒ 旧烘焙 z 作废 ⇒ 记录 z := mWallpaperScale
 *      （新烘焙值。不是归 1：归 1 会让 |z-z_w| 判据在"进应用驻留态"误放行，
 *      把裸 t 写在 z_w 矩阵上）。另保留 setWallpaperOffset(int,int,float) 轻量
 *      hook 在通道变化当时同步记录——否则 belt 要等下一次 traversal 才跑，
 *      中间一帧会按旧 z 错写。
 *   3. hook2 门控：mLastSurfacePosition（WindowContainer:91，final Point）前后
 *      不变（框架早退/等值/leash 路径，:4369 守卫）不干预；frame/insets 尺寸
 *      变化（走 applyWithNextDraw 延后写，:4390-4391）不干预。t 直接取与动画
 *      同源的 offset 字段——提前 return 帧里 mSurfacePosition 与 mXOffset 新旧
 *      混用的误差源消除，v3 的 Matrix/round(D)/pivot_w 复刻整块删除。
 *   4. 记录身份 layerId 化：SurfaceControl.getLayerId()（framework 内部在用，
 *      如 WMS:2470）。hook1 记动画拷贝的层 id，hook2 按窗口 SC 层 id 取记录；
 *      层重建（relayout）后 id 换代 ⇒ 旧记录失配不干预，等新动画写入匹配记录
 *      ——杜绝 v3 的"hash 继承复活"漏洞；toString() 解析 key 一并退役。
 *   5. 诊断日志（cap）：尾帧窗口（z≈1 且 z_w≠1 且框架真写了位置）；offset
 *      稳定但动画 t_anim 与所选 offset 字段发散 >1px（手机横屏 mPortrait* 冻结
 *      类问题，mi17u 验证用）。
 *
 * 现代 API（102）改写说明：旧 before/after 双段 hook 的 setObjectExtra 快照，
 * 改为拦截器内 chain.proceed() 两侧的局部变量；proceed 抛出的原方法异常照常
 * 向调用方传播（与旧框架 rethrow 行为一致），"after" 逻辑仅在正常完成时执行。
 */
public class WallpaperZoomFix {

    private static final String TAG = "WallpaperScrollFix";
    // |z-z_w| 小于此值视同与框架一致，不干预（弹簧尾帧、驻留态、完成态）
    private static final float Z_EPSILON = 0.001f;
    private static final int MAX_FIXUP_LOGS = 5;
    private static final int MAX_EVENT_LOGS = 3;
    // 记录表上限（壁纸层 home/lock 各一 + 层换代孤儿；超限整体清空自愈）
    private static final int MAX_RECORDS = 16;

    // WallpaperController.getWallpaperSurface(:79/:873) 的设备分叉，install 时判定一次
    private static boolean sFoldOrTabletDevice;

    private static final Map<String, AnimWrite> sAnimWrites = new HashMap<>();
    private static int sFixupLogCount = 0;
    private static int sEventLogCount = 0;

    private static final class AnimWrite {
        float z;            // 记录语义：该层矩阵当前烘焙的 zoom（相对 hScale 基线）
        final float px;     // P = parentFrame 中心 × pivot（动画侧）
        final float py;
        final int tAnimX;   // 动画本帧使用的 t_anim（诊断用）
        final int tAnimY;
        int lastAppliedTx;  // 上次覆盖所用 offset（诊断：区分滚动变化与结构性发散）
        int lastAppliedTy;

        AnimWrite(float z, float px, float py, int tAnimX, int tAnimY) {
            this.z = z;
            this.px = px;
            this.py = py;
            this.tAnimX = tAnimX;
            this.tAnimY = tAnimY;
        }
    }

    public static void install(ClassLoader classLoader) {
        sFoldOrTabletDevice = detectFoldOrTablet(classLoader);
        hookAnimWrite(classLoader);
        hookWmsPositionWrite(classLoader);
        hookZoomChannelChange(classLoader);
        hookMatrixRewrite(classLoader);
    }

    // 复刻 WallpaperController.java:79 的 IS_FOLD_OR_TABLET_DEVICE 判定，
    // 决定 t 用 mXOffset/mYOffset（平板/折叠，:880）还是 mPortrait*（手机，:875）
    private static boolean detectFoldOrTablet(ClassLoader classLoader) {
        try {
            Class<?> sp = XH.findClass("android.os.SystemProperties", classLoader);
            int type = (Integer) XH.callStaticMethod(sp, "getInt",
                    "persist.sys.muiltdisplay_type", 0);
            String chars = (String) XH.callStaticMethod(sp, "get",
                    "ro.build.characteristics", "");
            boolean foldOrTablet = type == 2 || chars.contains("tablet");
            MainHook.log(TAG + "[ZoomFix]: device foldOrTablet=" + foldOrTablet
                    + " (type=" + type + " chars=" + chars + ")");
            return foldOrTablet;
        } catch (Throwable t) {
            // 判定失败按平板走（与 v3 行为一致，piano 实证 t_anim=mXOffset）
            MainHook.logError(TAG + "[ZoomFix]: device detect failed, assume tablet", t);
            return true;
        }
    }

    // 动画侧：updateScaleIfNeeded 每帧把完整变换（平移 z·t_anim+(1-z)·P）写到壁纸
    // Surface，这里记录 {z, P, t_anim}，按动画拷贝的层 id 作 key。无 TTL：矩阵
    // 烘焙的 z 在 SF 中持久，记录跟着持久；层换代后旧 key 自然失配。
    private static void hookAnimWrite(ClassLoader classLoader) {
        XposedInterface xp = MainHook.xposed();
        try {
            java.lang.reflect.Method m = XH.findMethodExact(
                    XH.findClass("com.android.server.wm.MiuiWallpaperSurfaceAnimation",
                            classLoader),
                    "updateScaleIfNeeded",
                    SurfaceControl.class, Rect.class,
                    float.class, float.class, float.class, Point.class);
            xp.hook(m).intercept(chain -> {
                // 原方法异常照常传播；仅在正常完成且返回 true（矩阵已写）时记录。
                // 注意：本方法返回 boolean，任何路径都必须原样返回 proceed() 的结果——
                // 返回 null 会在调用方拆箱处 NPE，直接打崩 system_server（2026-11-10 教训）。
                Object result = chain.proceed();
                try {
                    if (Boolean.TRUE.equals(result)) {
                        SurfaceControl surface = (SurfaceControl) chain.getArg(0);
                        Rect parentFrame = (Rect) chain.getArg(1);
                        float zoom = (Float) chain.getArg(2);
                        Point tAnim = (Point) chain.getArg(5);
                        if (surface != null && parentFrame != null && tAnim != null) {
                            int layerId = getLayerId(surface);
                            if (layerId >= 0) { // 拿不到层身份就不记录，宁可少干预
                                Object anim = chain.getThisObject();
                                float pivotX = XH.getFloatField(anim, "mPivotX");
                                float pivotY = XH.getFloatField(anim, "mPivotY");
                                float px = (parentFrame.left + parentFrame.right) * pivotX;
                                float py = (parentFrame.top + parentFrame.bottom) * pivotY;
                                synchronized (sAnimWrites) {
                                    if (!sAnimWrites.containsKey(layerKey(layerId))
                                            && sAnimWrites.size() >= MAX_RECORDS) {
                                        sAnimWrites.clear(); // 层换代孤儿堆积的自愈路径
                                    }
                                    sAnimWrites.put(layerKey(layerId),
                                            new AnimWrite(zoom, px, py, tAnim.x, tAnim.y));
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookAnimWrite failed", t);
                }
                return result;
            });
            MainHook.log(TAG + "[ZoomFix]: Hook MiuiWallpaperSurfaceAnimation.updateScaleIfNeeded OK");
        } catch (Throwable t) {
            MainHook.logError(TAG + "[ZoomFix]: Hook updateScaleIfNeeded failed", t);
        }
    }

    // WMS 侧：updateSurfacePosition 的拦截。仅当框架本次真的判定移动
    // （mLastSurfacePosition 变化）且非延后写场景才干预，把最终写入位置覆盖为
    // z·t+(1-z)·P——t 取与动画同源的 offset 字段，z 取记录（矩阵烘焙值），
    // |z-z_w|<ε（与框架一致）时退场。
    private static void hookWmsPositionWrite(ClassLoader classLoader) {
        XposedInterface xp = MainHook.xposed();
        try {
            java.lang.reflect.Method m = XH.findMethodExact(
                    XH.findClass("com.android.server.wm.WindowState", classLoader),
                    "updateSurfacePosition",
                    SurfaceControl.Transaction.class);
            xp.hook(m).intercept(chain -> {
                Object win = chain.getThisObject();
                // 前置快照：mLastSurfacePosition（final Point，框架原地 set，必须拷贝）
                Point before = null;
                try {
                    if (XH.getBooleanField(win, "mIsWallpaper")) {
                        Object last = XH.getObjectField(win, "mLastSurfacePosition");
                        if (last instanceof Point) {
                            before = new Point((Point) last);
                        }
                    }
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookWmsPositionWrite.before failed", t);
                }
                Object result = chain.proceed();
                try {
                    if (!XH.getBooleanField(win, "mIsWallpaper")) {
                        return result;
                    }
                    SurfaceControl.Transaction transaction =
                            (SurfaceControl.Transaction) chain.getArg(0);
                    SurfaceControl surface = (SurfaceControl) XH.getObjectField(
                            win, "mSurfaceControl");
                    if (transaction == null || surface == null) {
                        return result;
                    }
                    // 门控 1：mLastSurfacePosition 未变 = 框架本次没判定移动
                    //（早 return / 等值 / leash 路径，:4369 守卫）
                    if (before == null) {
                        return result;
                    }
                    Point now = (Point) XH.getObjectField(win, "mLastSurfacePosition");
                    if (before.equals(now)) {
                        return result;
                    }
                    // 门控 2：frame/insets 尺寸变化 → 框架走 applyWithNextDraw
                    //（:4390-4391）本次事务没写位置，让位
                    if (isDeferredResize(win)) {
                        return result;
                    }
                    int layerId = getLayerId(surface);
                    if (layerId < 0) {
                        return result;
                    }
                    AnimWrite write;
                    synchronized (sAnimWrites) {
                        write = sAnimWrites.get(layerKey(layerId));
                    }
                    if (write == null) {
                        return result; // 层换代后旧记录失配：不干预，等新动画写入匹配记录
                    }
                    float zw = XH.getFloatField(win, "mWallpaperScale");
                    // 诊断：尾帧窗口——z≈1 而通道≠1，v3 的 |z-1| 判据在此漏补
                    if (Math.abs(write.z - 1.0f) < Z_EPSILON
                            && Math.abs(zw - 1.0f) >= Z_EPSILON) {
                        logEvent("tail window: z≈1 ch=" + zw + " layer=L" + layerId);
                    }
                    // 与框架一致（含驻留态/完成态）不干预
                    if (Math.abs(write.z - zw) < Z_EPSILON) {
                        return result;
                    }
                    int tx = sFoldOrTabletDevice
                            ? XH.getIntField(win, "mXOffset")
                            : XH.getIntField(win, "mPortraitXOffset");
                    int ty = sFoldOrTabletDevice
                            ? XH.getIntField(win, "mYOffset")
                            : XH.getIntField(win, "mPortraitYOffset");
                    // 诊断：offset 没动但动画 t_anim 发散（结构性，非滚动）
                    if (tx == write.lastAppliedTx && ty == write.lastAppliedTy
                            && (Math.abs(write.tAnimX - tx) > 1
                            || Math.abs(write.tAnimY - ty) > 1)) {
                        logEvent("t_anim (" + write.tAnimX + ", " + write.tAnimY
                                + ") diverges from offset (" + tx + ", " + ty + ")");
                    }
                    write.lastAppliedTx = tx;
                    write.lastAppliedTy = ty;
                    float x = write.z * tx + (1.0f - write.z) * write.px;
                    float y = write.z * ty + (1.0f - write.z) * write.py;
                    transaction.setPosition(surface, x, y);
                    if (sFixupLogCount < MAX_FIXUP_LOGS) {
                        sFixupLogCount++;
                        MainHook.log(TAG + "[ZoomFix]: position unified z=" + write.z
                                + " ch=" + zw + " t=(" + tx + ", " + ty + ") tAnim=("
                                + write.tAnimX + ", " + write.tAnimY + ") -> ("
                                + x + ", " + y + ") layer=L" + layerId);
                    }
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookWmsPositionWrite failed", t);
                }
                return result;
            });
            MainHook.log(TAG + "[ZoomFix]: Hook WindowState.updateSurfacePosition OK");
        } catch (Throwable t) {
            MainHook.logError(TAG + "[ZoomFix]: Hook updateSurfacePosition failed", t);
        }
    }

    // 轻量 belt：zoom 通道变化的当时（setWallpaperOffset :4989 写完、下一次
    // traversal 之前）就把记录 z 同步为新通道值——updateScaleIfNeeded 将在此
    // 重写矩阵线性（新烘焙 z = z_w），消除"通道已变、记录仍旧"的一帧错写窗口。
    private static void hookZoomChannelChange(ClassLoader classLoader) {
        XposedInterface xp = MainHook.xposed();
        try {
            java.lang.reflect.Method m = XH.findMethodExact(
                    XH.findClass("com.android.server.wm.WindowState", classLoader),
                    "setWallpaperOffset",
                    int.class, int.class, float.class);
            xp.hook(m).intercept(chain -> {
                Object win = chain.getThisObject();
                float oldScale = 0f;
                boolean captured = false;
                try {
                    if (XH.getBooleanField(win, "mIsWallpaper")) {
                        oldScale = XH.getFloatField(win, "mWallpaperScale");
                        captured = true;
                    }
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookZoomChannelChange.before failed", t);
                }
                Object result = chain.proceed();
                try {
                    if (!captured || !XH.getBooleanField(win, "mIsWallpaper")) {
                        return result;
                    }
                    float newScale = XH.getFloatField(win, "mWallpaperScale");
                    if (oldScale == newScale) {
                        return result; // 含 :4984 值不变短路：矩阵不会被重写
                    }
                    rebasedRecord(win, newScale, "channel " + oldScale + "->" + newScale);
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookZoomChannelChange failed", t);
                }
                return result;
            });
            MainHook.log(TAG + "[ZoomFix]: Hook WindowState.setWallpaperOffset OK");
        } catch (Throwable t) {
            MainHook.logError(TAG + "[ZoomFix]: Hook setWallpaperOffset failed", t);
        }
    }

    // 兜底 belt：updateScaleIfNeeded（:4272-4285）重写矩阵线性 ⟺ mLastHScale/VScale
    // 前后变化（mHScale/globalScale/mWallpaperScale 任一变化的汇合点，比盯通道更全）。
    // 重写后矩阵烘焙 z = mWallpaperScale，记录同步过去；hook2 的 |z-z_w| 判据自然退场。
    private static void hookMatrixRewrite(ClassLoader classLoader) {
        XposedInterface xp = MainHook.xposed();
        try {
            java.lang.reflect.Method m = XH.findMethodExact(
                    XH.findClass("com.android.server.wm.WindowState", classLoader),
                    "updateScaleIfNeeded");
            xp.hook(m).intercept(chain -> {
                Object win = chain.getThisObject();
                float h0 = 0f, v0 = 0f;
                boolean captured = false;
                try {
                    if (XH.getBooleanField(win, "mIsWallpaper")) {
                        h0 = XH.getFloatField(win, "mLastHScale");
                        v0 = XH.getFloatField(win, "mLastVScale");
                        captured = true;
                    }
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookMatrixRewrite.before failed", t);
                }
                Object result = chain.proceed();
                try {
                    if (!captured || !XH.getBooleanField(win, "mIsWallpaper")) {
                        return result;
                    }
                    float h1 = XH.getFloatField(win, "mLastHScale");
                    float v1 = XH.getFloatField(win, "mLastVScale");
                    if (h0 == h1 && v0 == v1) {
                        return result; // 未重写矩阵
                    }
                    rebasedRecord(win,
                            XH.getFloatField(win, "mWallpaperScale"),
                            "matrix rewritten");
                } catch (Throwable t) {
                    MainHook.logError(TAG + "[ZoomFix]: hookMatrixRewrite failed", t);
                }
                return result;
            });
            MainHook.log(TAG + "[ZoomFix]: Hook WindowState.updateScaleIfNeeded OK");
        } catch (Throwable t) {
            MainHook.logError(TAG + "[ZoomFix]: Hook updateScaleIfNeeded failed", t);
        }
    }

    // 矩阵线性被框架重写后的记录再定基：z := mWallpaperScale（新烘焙值）
    private static void rebasedRecord(Object win, float newScale, String reason) {
        SurfaceControl surface = (SurfaceControl) XH.getObjectField(win, "mSurfaceControl");
        if (surface == null) {
            return;
        }
        int layerId = getLayerId(surface);
        if (layerId < 0) {
            return;
        }
        synchronized (sAnimWrites) {
            AnimWrite write = sAnimWrites.get(layerKey(layerId));
            if (write != null && Math.abs(write.z - newScale) >= Z_EPSILON) {
                logEvent(reason + ", record z " + write.z + " -> " + newScale
                        + " layer=L" + layerId);
                write.z = newScale;
            }
        }
    }

    private static boolean isDeferredResize(Object win) {
        try {
            Object frames = XH.getObjectField(win, "mWindowFrames");
            return (Boolean) XH.callMethod(frames, "isFrameSizeChanged")
                    || (Boolean) XH.callMethod(win, "surfaceInsetsChanging");
        } catch (Throwable t) {
            return false; // 判定不了按非延后处理（宁可多干预，不漏补）
        }
    }

    private static int getLayerId(SurfaceControl surface) {
        try {
            return (Integer) XH.callMethod(surface, "getLayerId");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String layerKey(int layerId) {
        return "L" + layerId;
    }

    private static void logEvent(String msg) {
        if (sEventLogCount < MAX_EVENT_LOGS) {
            sEventLogCount++;
            MainHook.log(TAG + "[ZoomFix]: " + msg);
        }
    }
}
