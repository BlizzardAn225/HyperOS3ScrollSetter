package com.blizzard225.wallpaperscrollfix;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.SurfaceControl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 随屏滚动 × 壁纸缩放动画抖动修复（方案A，system_server 侧，HyperOS3/4）。
 *
 * 问题：回桌面/退出应用时 launcher 经 "miui.wallpaper.animation" 让
 * MiuiWallpaperSurfaceAnimation 对壁纸 Surface 做 zoom（如 1.18→1.0，中心 pivot）。
 * 该动画用 setMatrix(Matrix, float[]) 写壁纸 Surface——此 API 会把矩阵平移分量
 * 一并写成 Surface 位置；而同一 Surface 的位置 WMS 也在独立维护
 * （WindowState.updateSurfacePosition 写 mSurfacePosition = 帧原点 + mXOffset，
 * 即随屏滚动偏移 t）。两个写入者的值相差 (z-1)(t-P)（z=zoom，P=parentFrame 中心
 * 乘 pivot）：z≠1 且 t≠P 时逐帧互相覆盖 → 回桌面大幅抖动、划桌面小幅抖动。
 * 滚动关闭时 surface=屏宽、t≡0 恒定，WMS 不再改写位置，故不抖。
 *
 * 修复（几何零变化，只统一写入值）：动画仍写完整变换，其平移恒为 z·t+(1-z)·P；
 * 本类记录该值，并在 WMS 写位置时替换成同一个数。两个写入者永远一致；
 * z=1（动画外）时该值恒等于 t，记录过期后自动失效，对正常渲染零影响。
 */
public class WallpaperZoomFix {

    private static final String TAG = "WallpaperScrollFix";
    // 记录有效期：动画结束/取消后超过此时长即不再用旧值覆盖 WMS 写入
    private static final long RECORD_TTL_MS = 2000L;
    private static final int MAX_FIXUP_LOGS = 5;

    private static final Map<String, AnimWrite> sAnimWrites = new HashMap<>();
    private static int sFixupLogCount = 0;

    private static final class AnimWrite {
        final float x;
        final float y;
        final float z;
        final long time;

        AnimWrite(float x, float y, float z, long time) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }

    public static void install(ClassLoader classLoader) {
        hookAnimWrite(classLoader);
        hookWmsPositionWrite(classLoader);
    }

    // 动画侧：updateScaleIfNeeded 每帧把完整变换（含平移 z·t+(1-z)·P）写到壁纸
    // Surface，这里只做记录，不改动动画行为
    private static void hookAnimWrite(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.MiuiWallpaperSurfaceAnimation",
                    classLoader,
                    "updateScaleIfNeeded",
                    SurfaceControl.class, Rect.class,
                    float.class, float.class, float.class, Point.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!Boolean.TRUE.equals(param.getResult())) {
                                    return;
                                }
                                SurfaceControl surface = (SurfaceControl) param.args[0];
                                Rect parentFrame = (Rect) param.args[1];
                                float zoom = (float) param.args[2];
                                Point point = (Point) param.args[5];
                                if (surface == null || parentFrame == null || point == null) {
                                    return;
                                }
                                float pivotX = XposedHelpers.getFloatField(param.thisObject, "mPivotX");
                                float pivotY = XposedHelpers.getFloatField(param.thisObject, "mPivotY");
                                float px = (parentFrame.left + parentFrame.right) * pivotX;
                                float py = (parentFrame.top + parentFrame.bottom) * pivotY;
                                AnimWrite write = new AnimWrite(
                                        zoom * point.x + (1.0f - zoom) * px,
                                        zoom * point.y + (1.0f - zoom) * py,
                                        zoom, SystemClock.uptimeMillis());
                                String key = surfaceKey(surface);
                                if (key == null) {
                                    return;
                                }
                                synchronized (sAnimWrites) {
                                    sAnimWrites.put(key, write);
                                    purgeExpiredLocked(SystemClock.uptimeMillis());
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "[ZoomFix]: hookAnimWrite failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "[ZoomFix]: Hook MiuiWallpaperSurfaceAnimation.updateScaleIfNeeded OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[ZoomFix]: Hook updateScaleIfNeeded failed: " + t);
        }
    }

    // WMS 侧：updateSurfacePosition 照常计算滚动偏移（mSurfacePosition 保持最新，
    // 动画每帧读到的 t 不受影响），只把最终写入 Surface 的位置替换为动画一致的平移。
    // z=1 或无新鲜记录时不干预，保持 WMS 原值
    private static void hookWmsPositionWrite(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.WindowState",
                    classLoader,
                    "updateSurfacePosition",
                    SurfaceControl.Transaction.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!XposedHelpers.getBooleanField(param.thisObject, "mIsWallpaper")) {
                                    return;
                                }
                                SurfaceControl.Transaction transaction =
                                        (SurfaceControl.Transaction) param.args[0];
                                SurfaceControl surface = (SurfaceControl) XposedHelpers.getObjectField(
                                        param.thisObject, "mSurfaceControl");
                                if (transaction == null || surface == null) {
                                    return;
                                }
                                String key = surfaceKey(surface);
                                if (key == null) {
                                    return;
                                }
                                AnimWrite write;
                                synchronized (sAnimWrites) {
                                    write = sAnimWrites.get(key);
                                }
                                if (write == null || write.z == 1.0f
                                        || SystemClock.uptimeMillis() - write.time > RECORD_TTL_MS) {
                                    return;
                                }
                                transaction.setPosition(surface, write.x, write.y);
                                if (sFixupLogCount < MAX_FIXUP_LOGS) {
                                    sFixupLogCount++;
                                    XposedBridge.log(TAG + "[ZoomFix]: position unified (" + write.x
                                            + ", " + write.y + ") z=" + write.z + " key=" + key);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "[ZoomFix]: hookWmsPositionWrite failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "[ZoomFix]: Hook WindowState.updateSurfacePosition OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[ZoomFix]: Hook updateSurfacePosition failed: " + t);
        }
    }

    private static void purgeExpiredLocked(long now) {
        Iterator<Map.Entry<String, AnimWrite>> it = sAnimWrites.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().time > RECORD_TTL_MS) {
                it.remove();
            }
        }
    }

    // SurfaceControl.toString() = "Surface(name=<图层名>)/@0x<hash>"；
    // 动画持有的拷贝经 copyFrom 保留图层名，与 WindowState 原对象同名同图层
    private static String surfaceKey(SurfaceControl surface) {
        try {
            String s = surface.toString();
            int start = s.indexOf("name=");
            if (start < 0) {
                return s;
            }
            start += 5;
            int end = s.indexOf(")/@", start);
            if (end < 0) {
                end = s.lastIndexOf(')');
            }
            return (end > start) ? s.substring(start, end) : s.substring(start);
        } catch (Throwable t) {
            return null;
        }
    }
}
