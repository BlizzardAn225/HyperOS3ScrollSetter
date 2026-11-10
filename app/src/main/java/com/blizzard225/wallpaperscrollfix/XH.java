package com.blizzard225.wallpaperscrollfix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射工具兼容层：替代现代 libxposed API 中已移除的 XposedHelpers 子集。
 * 现代 API（101+）框架侧不再提供 XposedHelpers，反射一律走本类；
 * 语义与旧 XposedHelpers 对齐（失败抛 RuntimeException，调用方本就 catch Throwable）。
 *
 * 注意交接文档 §3.1 的坑：callMethod 系列在实参含 null 时无法可靠推断重载，
 * 此类场景必须走带 Class[] 的精确变体（见 callMethodExact/callStaticMethodExact）。
 */
final class XH {

    private XH() {
    }

    // ================================================================
    // 类加载
    // ================================================================

    static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }

    // ================================================================
    // 方法定位
    // ================================================================

    /** 沿继承链向上找同签名声明方法（含私有），找不到抛 RuntimeException。 */
    static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new RuntimeException("Method not found: "
                + clazz.getName() + "." + methodName);
    }

    /** 同 findMethodExact，但找不到返回 null（探测用）。 */
    static Method findMethodExactIfExists(Class<?> clazz, String methodName,
            Class<?>... parameterTypes) {
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================
    // 调用
    // ================================================================

    /** 实参类型推断的调用（所有实参均非 null 时才可安全使用）。 */
    static Object callMethod(Object receiver, String methodName, Object... args) {
        Method m = findBestMatchMethod(receiver.getClass(), methodName, args);
        try {
            return m.invoke(receiver, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 显式参数类型的调用——实参可能为 null 时必须用这个。 */
    static Object callMethodExact(Object receiver, String methodName,
            Class<?>[] parameterTypes, Object... args) {
        Method m = findMethodExact(receiver.getClass(), methodName, parameterTypes);
        try {
            return m.invoke(receiver, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method m = findBestMatchMethod(clazz, methodName, args);
        try {
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static Object callStaticMethodExact(Class<?> clazz, String methodName,
            Class<?>[] parameterTypes, Object... args) {
        Method m = findMethodExact(clazz, methodName, parameterTypes);
        try {
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // ================================================================
    // 字段
    // ================================================================

    private static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new RuntimeException("Field not found: "
                + clazz.getName() + "." + fieldName);
    }

    static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean getBooleanField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getBoolean(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static int getIntField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static float getFloatField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getFloat(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // ================================================================
    // 内部：按名称+实参做最佳匹配（仅限实参全非 null 的调用点）
    // ================================================================

    private static Method findBestMatchMethod(Class<?> clazz, String methodName, Object[] args) {
        Method best = null;
        for (Class<?> c = clazz; c != null && best == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != args.length) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < pts.length; i++) {
                    if (!isAssignable(pts[i], args[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    best = m;
                    break;
                }
            }
        }
        if (best == null) {
            throw new RuntimeException("Method not found: "
                    + clazz.getName() + "." + methodName
                    + " (" + args.length + " args)");
        }
        best.setAccessible(true);
        return best;
    }

    private static boolean isAssignable(Class<?> param, Object arg) {
        if (arg == null) {
            return !param.isPrimitive();
        }
        Class<?> boxed = param;
        if (param.isPrimitive()) {
            boxed = param == int.class ? Integer.class
                    : param == boolean.class ? Boolean.class
                    : param == float.class ? Float.class
                    : param == long.class ? Long.class
                    : param == double.class ? Double.class
                    : param == byte.class ? Byte.class
                    : param == short.class ? Short.class
                    : Character.class;
        }
        return boxed.isInstance(arg);
    }
}
