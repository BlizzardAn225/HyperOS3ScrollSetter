package com.blizzard225.wallpapersetter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * 随屏滚动 / 壁纸缩放 的 shell 命令与执行、复制入口。
 * 复制出的命令与 Shizuku 自动执行的命令保持一致。
 */
object WallpaperCommands {

    // 与 Shizuku 自动执行（enableScroll 循环）逐拍一致：
    // 共 4 秒、每 0.5 秒写一次设置（9 次），第 3 次写入后（约 1 秒）杀壁纸进程，
    // 之后继续写 3 秒，防止壁纸进程重启后把设置改回
    private val SCROLL_COMMAND = listOf(
        "i=0",
        "while [ \$i -lt 9 ]; do",
        "  settings put secure pref_key_wallpaper_screen_scrolled_span 1",
        "  [ \$i -eq 2 ] && am force-stop com.miui.miwallpaper",
        "  sleep 0.5",
        "  i=\$((i+1))",
        "done"
    ).joinToString("\n")

    // miui_home_animation_rate：2=极速动画（跳过壁纸缩放动画，即关闭壁纸缩放），1=正常
    private fun zoomArgs(enabled: Boolean): Array<String> = arrayOf(
        "settings", "put", "system", "miui_home_animation_rate",
        if (enabled) "1" else "2"
    )

    /**
     * Shizuku 已授权：自动执行开启随屏滚动（反复写设置，1 秒后重启壁纸进程，共 4 秒）。
     * 执行中 Shizuku 掉线（exec 返回 -1）时中止并回调 onShizukuFailed。
     */
    fun enableScroll(activity: Activity, onShizukuFailed: () -> Unit = {}) {
        Toast.makeText(activity, "正在执行，请稍候...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val startTime = System.currentTimeMillis()
                var killed = false
                var shizukuBroken = false

                while (true) {
                    val elapsed = System.currentTimeMillis() - startTime

                    // 执行 settings put
                    if (ShizukuRunner.exec(
                            "settings", "put", "secure",
                            "pref_key_wallpaper_screen_scrolled_span", "1"
                        ) == -1
                    ) {
                        shizukuBroken = true
                        break
                    }

                    // 约1秒后杀进程（给settings put一点时间先写入）
                    if (!killed && elapsed >= 1000) {
                        if (ShizukuRunner.exec("am", "force-stop", "com.miui.miwallpaper") == -1) {
                            shizukuBroken = true
                            break
                        }
                        killed = true
                    }

                    // 杀进程3秒后停止循环
                    if (killed && elapsed >= 4000) {
                        break
                    }

                    Thread.sleep(500)
                }

                activity.runOnUiThread {
                    if (shizukuBroken) {
                        onShizukuFailed()
                    } else {
                        Toast.makeText(activity, "随屏滚动已开启", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                activity.runOnUiThread {
                    onShizukuFailed()
                }
            }
        }.start()
    }

    /** 未授权 Shizuku：复制与自动执行一致的命令 */
    fun copyScrollCommand(activity: Activity) {
        copyToClipboard(activity, "Shizuku 命令", SCROLL_COMMAND)
        Toast.makeText(
            activity,
            "命令已复制到剪贴板！\n请在 Shizuku 终端中执行。",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Shizuku 已授权：直接执行命令开启/关闭壁纸缩放。
     * Shizuku 掉线（exec 返回 -1）时回调 onShizukuFailed。
     */
    fun setWallpaperZoom(activity: Activity, enabled: Boolean, onShizukuFailed: () -> Unit = {}) {
        Toast.makeText(activity, "正在执行，请稍候...", Toast.LENGTH_SHORT).show()

        Thread {
            val code = ShizukuRunner.exec(*zoomArgs(enabled))
            activity.runOnUiThread {
                when {
                    code == -1 -> onShizukuFailed()
                    code == 0 -> Toast.makeText(
                        activity,
                        if (enabled) "壁纸缩放已开启" else "壁纸缩放已关闭",
                        Toast.LENGTH_LONG
                    ).show()
                    else -> Toast.makeText(activity, "执行失败（exit=$code）", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 未授权 Shizuku：复制开启/关闭壁纸缩放的命令 */
    fun copyZoomCommand(activity: Activity, enabled: Boolean) {
        copyToClipboard(activity, "Shizuku 命令", zoomArgs(enabled).joinToString(" "))
        Toast.makeText(
            activity,
            "命令已复制到剪贴板！\n请在 Shizuku 终端中执行。",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
