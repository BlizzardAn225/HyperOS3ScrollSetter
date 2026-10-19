package com.blizzard225.wallpapersetter

import java.lang.reflect.Method

/**
 * Shizuku 命令执行封装：反射调用 Shizuku.newProcess 在 shell 身份下跑命令。
 * exec 等待退出并返回退出码（异常返回 -1）；get 额外读取 stdout。
 */
object ShizukuRunner {

    private val newProcess: Method? by lazy {
        try {
            Class.forName("rikka.shizuku.Shizuku")
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
        } catch (e: Exception) {
            null
        }
    }

    fun exec(vararg args: String): Int {
        return try {
            val process = newProcess!!.invoke(null, args, null, null) as Process
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    fun get(vararg args: String): String {
        return try {
            val process = newProcess!!.invoke(null, args, null, null) as Process
            val result = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            process.destroy()
            result
        } catch (e: Exception) {
            ""
        }
    }
}
