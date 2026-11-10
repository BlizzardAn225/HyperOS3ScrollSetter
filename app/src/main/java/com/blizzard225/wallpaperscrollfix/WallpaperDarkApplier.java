package com.blizzard225.wallpaperscrollfix;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 分模式壁纸写入（深色模式 dark 类型双图），运行在 com.miui.miwallpaper 进程内。
 *
 * 背景：MIUI 的深色模式壁纸是独立壁纸类型（type="dark"，与 IMAGE 同引擎渲染），
 * 数据为 dark/ 目录下浅色/深色两张并存文件；系统切换深色时引擎按 uiMode 自动换图。
 * 写入的唯一正规入口是 MIUI client API
 * {@code MiuiWallpaperManager.setMiuiDarkModeWallpaper(忽略位, 浅色图, 深色图, which)}：
 * 它 bind 本进程的壁纸服务拿目标路径表 → 本进程把位图落盘 → 服务端写 DataXML
 * （type/pathSize/path0/path1/support_dark）→ FileObserver 触发引擎重载，全程免手动刷新。
 * 权限校验为 checkCallingOrSelfPermission（同进程直调必过），因此必须在本进程内调用，
 * 普通 app 侧无法完成（免Root/Shizuku 亦不可行）。
 *
 * 写入语义（与 setter app 的勾选框对应）：
 *   - 勾浅色：若当前 type=dark → 只更新配对中的浅色位（深色文件保留）；
 *              若当前 type=image → 走系统 WallpaperManager 单图路径（行为与旧版一致）。
 *   - 勾深色：写入 dark 双图，浅色配对图取用户所选浅色图；
 *              若用户未选浅色（仅勾深色）则取当前生效的浅色壁纸文件，
 *              文件不可用时兜底为同图成对。
 *   - which=3（桌面+锁屏）时按 which=1、2 各自独立执行。
 *
 * 版本适配：client 类 OS3/OS4 同名同签名（OS3 :527 / OS4 :1828）；
 * 反射调用均 fail-soft，失败只打日志与 Toast，不影响宿主。
 */
public final class WallpaperDarkApplier {

    private static final String TAG = MainHook.TAG;
    private static final String MIUI_MGR_CLASS = "com.miui.miwallpaper.MiuiWallpaperManager";
    private static final String TYPE_DARK = "dark";
    private static final String THEME_MAGIC_DIR = "/data/system/theme_magic/users/0/wallpaper/";
    /** 写入结果回传 setter app（hook 进程 Toast 被系统屏蔽，成败由 app 侧提示并清缓存） */
    private static final String ACTION_APPLY_RESULT =
            "com.blizzard225.wallpapersetter.ACTION_APPLY_RESULT";
    private static final String SETTER_PKG = "com.blizzard225.wallpapersetter";

    private static final int WHICH_HOME = 1;
    private static final int WHICH_LOCK = 2;
    private static final int MASK_LIGHT = 1;
    private static final int MASK_DARK = 2;

    private final Context mContext;
    private final Object mMgr; // com.miui.miwallpaper.MiuiWallpaperManager 单例

    private WallpaperDarkApplier(Context context, Object mgr) {
        mContext = context;
        mMgr = mgr;
    }

    /** 广播入口：which 1=桌面 2=锁屏 3=两者；mask bit0=浅色 bit1=深色；uri 为 setter app FileProvider 授权图 */
    public static void apply(final Context context, Intent intent) {
        final int which = intent.getIntExtra("which", 0);
        final int mask = intent.getIntExtra("mask", 0);
        final String lightUri = intent.getStringExtra("light_uri");
        final String darkUri = intent.getStringExtra("dark_uri");
        if (which <= 0 || mask <= 0 || context == null) {
            return;
        }
        MainHook.log(TAG + "[DarkApply]: received which=" + which + " mask=" + mask
                + " light=" + (lightUri != null) + " dark=" + (darkUri != null));
        new Thread(() -> {
            try {
                Class<?> mgrClass = XH.findClass(
                        MIUI_MGR_CLASS, context.getClassLoader());
                Object mgr = ensureManagerReady(context, mgrClass);
                WallpaperDarkApplier applier = new WallpaperDarkApplier(context, mgr);
                boolean both = (which & WHICH_HOME) != 0 && (which & WHICH_LOCK) != 0;
                if (both) {
                    applier.applyOne(WHICH_HOME, mask, lightUri, darkUri);
                    applier.applyOne(WHICH_LOCK, mask, lightUri, darkUri);
                } else {
                    applier.applyOne(which, mask, lightUri, darkUri);
                }
                toast(context, "壁纸写入完成");
                sendResult(context, true, null);
            } catch (Throwable t) {
                MainHook.log(TAG + "[DarkApply]: failed: " + t
                        + "\n" + android.util.Log.getStackTraceString(t));
                toast(context, "壁纸写入失败：" + t.getMessage());
                sendResult(context, false, t.getMessage());
            }
        }, "ws-dark-apply").start();
    }

    /** 写入结果回传 setter app（hook 进程 Toast 被系统屏蔽，成败由 app 侧提示并清缓存） */
    private static void sendResult(Context context, boolean success, String error) {
        try {
            Intent result = new Intent(ACTION_APPLY_RESULT).setPackage(SETTER_PKG);
            result.putExtra("success", success);
            if (error != null) {
                result.putExtra("error", error);
            }
            context.sendBroadcast(result);
        } catch (Throwable t) {
            MainHook.log(TAG + "[DarkApply]: sendResult failed: " + t);
        }
    }

    /**
     * initSync 等 3.5s 超时后仍会返回实例（mService 可能为 null，后续 set* 全部
     * 静默 no-op），因此这里轮询 isServiceReady 确认真正就绪，未就绪重试绑定。
     */
    private static Object ensureManagerReady(Context context, Class<?> mgrClass) throws Exception {
        Object mgr = XH.callStaticMethod(mgrClass, "initSync", context);
        for (int i = 0; i < 5; i++) {
            Object ready = null;
            try {
                ready = XH.callMethod(mgr, "isServiceReady");
            } catch (Throwable t) {
                MainHook.log(TAG + "[DarkApply]: isServiceReady reflect failed: " + t);
                break; // 无法探测时按原行为继续
            }
            if (Boolean.TRUE.equals(ready)) {
                return mgr;
            }
            MainHook.log(TAG + "[DarkApply]: service not ready, retry " + (i + 1) + "/5");
            Thread.sleep(1000);
            mgr = XH.callStaticMethod(mgrClass, "initSync", context);
        }
        try {
            Object ready = XH.callMethod(mgr, "isServiceReady");
            if (!Boolean.TRUE.equals(ready)) {
                throw new IllegalStateException("壁纸服务未就绪（bindService 超时）");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable ignored) {
        }
        return mgr;
    }

    private void applyOne(int which, int mask, String lightUri, String darkUri) throws Exception {
        String type = currentType(which);
        MainHook.log(TAG + "[DarkApply]: which=" + which + " currentType=" + type);
        boolean wantLight = (mask & MASK_LIGHT) != 0;
        boolean wantDark = (mask & MASK_DARK) != 0;

        if (wantDark) {
            Bitmap dark = decodeUri(darkUri);
            if (dark == null) {
                throw new IllegalStateException("深色图片解码失败");
            }
            try {
                Bitmap light = lightUri != null ? decodeUri(lightUri) : null;
                if (light == null) {
                    light = decodeCurrentFile(which, type, false);
                }
                if (light == null) {
                    light = dark; // 兜底：现有浅色图不可用时同图成对
                }
                try {
                    // setMiuiDarkModeWallpaper(忽略位, 浅色, 深色, which)，同步返回 boolean。
                    // 注意：首参为 null 时不能用 callMethod（有两个 4 参重载，findMethodBestMatch
                    // 对 null 实参推断会 NPE），必须显式参数类型查找后 invoke。
                    Object result = invokeSetDark(light, dark, which);
                    MainHook.log(TAG + "[DarkApply]: setDark which=" + which
                            + " -> " + result + " typeAfter=" + currentType(which));
                    if (!Boolean.TRUE.equals(result)) {
                        throw new IllegalStateException(
                                "setMiuiDarkModeWallpaper 返回 false（服务未就绪或路径表为空）");
                    }
                } finally {
                    if (light != dark) light.recycle();
                }
                verifyDarkApplied(which);
            } finally {
                dark.recycle();
            }
            return;
        }

        // 仅浅色
        Bitmap light = decodeUri(lightUri);
        if (light == null) {
            throw new IllegalStateException("浅色图片解码失败");
        }
        try {
            if (TYPE_DARK.equals(type)) {
                // 只更新 dark 对里的浅色位，保留现有深色文件
                Bitmap darkExisting = decodeCurrentFile(which, type, true);
                try {
                    Object result = invokeSetDark(light, darkExisting != null ? darkExisting : light, which);
                    MainHook.log(TAG + "[DarkApply]: setDark(lightOnly) which=" + which
                            + " -> " + result + " typeAfter=" + currentType(which));
                    if (!Boolean.TRUE.equals(result)) {
                        throw new IllegalStateException(
                                "setMiuiDarkModeWallpaper 返回 false（服务未就绪或路径表为空）");
                    }
                } finally {
                    if (darkExisting != null) darkExisting.recycle();
                }
                verifyDarkApplied(which);
            } else {
                // 普通 image 类型：走引擎私有 21 参 setWallpaper，supportDark(第10参)=false
                // 豁免压暗（官方锁屏编辑器同款机制：bundle support_dark=false → f2251m=false
                // → uDarken 恒 false，且 DataXML 持久化）。引擎 builder 桥硬编码 true，
                // WallpaperManager.setBitmap 走服务端缺省也是 true，均会压暗，故必须直调。
                Object result = invokeSetImageNoDarken(light, which);
                MainHook.log(TAG + "[DarkApply]: setImageNoDarken which=" + which + " -> " + result);
                if (!(result instanceof Integer) || (Integer) result != 0) {
                    throw new IllegalStateException("setWallpaper 返回 " + result + "（非 0 即失败）");
                }
            }
        } finally {
            light.recycle();
        }
    }

    /** 防 set* 静默 no-op：写入后核对 type 确实变为 dark（FileObserver 落盘有短暂延迟，给 3s 余地） */
    private void verifyDarkApplied(int which) throws Exception {
        for (int i = 0; i < 6; i++) {
            String after = currentType(which);
            if (TYPE_DARK.equals(after)) {
                MainHook.log(TAG + "[DarkApply]: verified type=dark which=" + which);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("写入后 type 仍为 " + currentType(which)
                + "（set* 可能被 isServiceReady 静默跳过）");
    }

    /**
     * 引擎私有 21 参 setWallpaper：实参复刻 builder 桥 A()，仅 supportDark(第10参)=false
     * ——官方锁屏编辑器的"不压暗"机制（bundle support_dark=false → MiuiWallpaperData
     * f2251m=false → uDarken 恒 false，DataXML 持久化跨重启）。
     * 返回码：0 成功 / 1002 服务未就绪 / 1004 参数 / 1005 写盘 / 1009 空 map / 1003 异常被吞。
     */
    private Object invokeSetImageNoDarken(Bitmap light, int which) throws Exception {
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method candidate : mMgr.getClass().getDeclaredMethods()) {
            if (candidate.getName().equals("setWallpaper") && candidate.getParameterCount() == 21) {
                m = candidate;
                break;
            }
        }
        if (m == null) {
            throw new IllegalStateException("21 参 setWallpaper 未找到（宿主版本漂移）");
        }
        m.setAccessible(true);
        Object clockTypeInfo = new int[]{-1, -1};
        try {
            Object def = XH.getStaticObjectField(
                    XH.findClass(
                            "com.miui.miwallpaper.MiuiWallpaperManager$WallpaperApplierBuilder",
                            mMgr.getClass().getClassLoader()),
                    "DEFAULT_CLOCK_TYPE_INFO");
            if (def instanceof int[]) {
                clockTypeInfo = def;
            }
        } catch (Throwable ignored) {
        }
        // (which, type, 浅, 深, comp, videoPath, content, isVideoLoop, carousel,
        //  supportDark, effectId, blurState, supportMatting, clockStyleType,
        //  clockTypeInfo, originW, originH, linkage, doodleStatus, sensorParams, allKeyFrame)
        return m.invoke(mMgr,
                which, "image", light, null,
                null, null, null,
                Boolean.FALSE, Boolean.TRUE,
                Boolean.FALSE,
                -1, -1,
                Boolean.FALSE,
                null, clockTypeInfo,
                0, 0,
                null, -1, null, -1);
    }

    private String currentType(int which) {
        try {
            Object type = XH.callMethod(mMgr, "getMiuiWallpaperType", which);
            return type instanceof String ? (String) type : null;
        } catch (Throwable t) {
            MainHook.log(TAG + "[DarkApply]: getMiuiWallpaperType failed: " + t);
            return null;
        }
    }

    /**
     * setMiuiDarkModeWallpaper 有 (Bitmap,Bitmap,Bitmap,int) 与
     * (InputStream,Bitmap,Bitmap,int) 两个重载，首参恒传 null ——
     * 必须显式参数类型定位 Method 后 invoke，不能走 callMethod 的实参推断。
     */
    private Object invokeSetDark(Bitmap light, Bitmap dark, int which) throws Exception {
        Class<?> mgrClass = mMgr.getClass();
        java.lang.reflect.Method m;
        try {
            m = XH.findMethodExact(mgrClass, "setMiuiDarkModeWallpaper",
                    Bitmap.class, Bitmap.class, Bitmap.class, int.class);
        } catch (Throwable t) {
            m = XH.findMethodExact(mgrClass, "setMiuiDarkModeWallpaper",
                    java.io.InputStream.class, Bitmap.class, Bitmap.class, int.class);
        }
        m.setAccessible(true);
        return m.invoke(mMgr, null, light, dark, which);
    }

    /**
     * 取当前生效的壁纸文件位图：dark 类型用 getMiuiWallpaperDarkFilePath 的 [浅,深] 对；
     * 其它类型回退 THEME_MAGIC_PATH 的 image 目录布局，最后兜底返回 null。
     */
    /**
     * 取当前生效的壁纸文件位图：
     *   - type=dark → getMiuiWallpaperDarkFilePath 的 [浅,深] 路径对（注意该 API
     *     无论当前类型如何都返回 dark/ 目录路径，type 非 dark 时文件不存在，不能先查）；
     *   - type≠dark 且要浅色位 → 当前生效图在 image/ 目录；
     *   - type≠dark 且要深色位 → 不存在（返回 null，调用方走同图兜底）。
     * 任一路径解码失败时尝试候选列表中的下一个。
     */
    private Bitmap decodeCurrentFile(int which, String type, boolean isDark) {
        String name = which == WHICH_LOCK ? "lock_wallpaper" : "home_wallpaper";
        java.util.ArrayList<String> candidates = new java.util.ArrayList<>(2);
        if (TYPE_DARK.equals(type)) {
            try {
                Object result = XH.callMethod(mMgr, "getMiuiWallpaperDarkFilePath", which);
                // OS4 该 API 返回 String[]{浅路径, 深路径}（兼容 List 形态）
                if (result instanceof String[] && ((String[]) result).length >= 2) {
                    candidates.add(((String[]) result)[isDark ? 1 : 0]);
                } else if (result instanceof List && ((List<?>) result).size() >= 2) {
                    candidates.add((String) ((List<?>) result).get(isDark ? 1 : 0));
                }
            } catch (Throwable t) {
                MainHook.log(TAG + "[DarkApply]: getMiuiWallpaperDarkFilePath failed: " + t);
            }
            candidates.add(THEME_MAGIC_DIR + "dark/" + name + (isDark ? "_dark.jpg" : ".jpg"));
        } else if (!isDark) {
            candidates.add(THEME_MAGIC_DIR + "image/" + name + ".jpg");
        } else {
            return null;
        }
        for (String path : candidates) {
            try {
                Bitmap bitmap = decodePath(path);
                if (bitmap != null) {
                    return bitmap;
                }
            } catch (Throwable t) {
                MainHook.log(TAG + "[DarkApply]: decode " + path + " failed: " + t);
            }
        }
        return null;
    }

    private Bitmap decodeUri(String uri) {
        if (uri == null) {
            return null;
        }
        InputStream in = null;
        try {
            in = mContext.getContentResolver().openInputStream(Uri.parse(uri));
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            MainHook.log(TAG + "[DarkApply]: decode " + uri + " failed: " + t);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private Bitmap decodePath(String path) throws Exception {
        InputStream in = null;
        try {
            in = new FileInputStream(new File(path));
            return BitmapFactory.decodeStream(in);
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void toast(final Context context, final String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }
}
