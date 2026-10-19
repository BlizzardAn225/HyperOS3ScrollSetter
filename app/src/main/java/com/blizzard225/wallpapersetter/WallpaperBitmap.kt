package com.blizzard225.wallpapersetter

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast

/** 壁纸位图处理：解码、设置到桌面/锁屏、HyperOS4 引擎规则扩展 */
object WallpaperBitmap {

    fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            val debugInfo = buildString {
                appendLine("=== WallpaperSetter 解码失败 ===")
                appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine("URI: $uri")
                appendLine("scheme: ${uri.scheme}")
                appendLine("authority: ${uri.authority}")
                appendLine("path: ${uri.path}")
                appendLine("系统: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android版本: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("异常信息: ${e.javaClass.name}: ${e.message}")
                appendLine()
                appendLine("堆栈:")
                appendLine(android.util.Log.getStackTraceString(e))
            }

            // 依然保留 Logcat 输出，方便自己本地调试时看
            android.util.Log.e("WallpaperSetter", "Failed to decode URI: $uri", e)

            // 写入剪贴板
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("WallpaperSetter Debug Info", debugInfo)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(
                    context,
                    "解码失败: ${e.message}\n详细信息已复制到剪贴板，可反馈给开发者",
                    Toast.LENGTH_LONG
                ).show()
            } catch (clipEx: Exception) {
                // 极端情况下剪贴板本身也可能不可用，避免二次崩溃
                android.util.Log.e("WallpaperSetter", "Failed to copy to clipboard", clipEx)
                Toast.makeText(context, "解码失败: ${e.message}", Toast.LENGTH_LONG).show()
            }

            null
        }
    }

    fun setBoth(context: Context, bitmap: Bitmap) {
        try {
            val wm = WallpaperManager.getInstance(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
            } else {
                wm.setBitmap(bitmap)
            }

            Toast.makeText(context, "桌面 + 锁屏壁纸已设置", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun setStandard(context: Context, bitmap: Bitmap) {
        try {
            val wm = WallpaperManager.getInstance(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM
                )
            } else {
                wm.setBitmap(bitmap)
            }

            Toast.makeText(context, "桌面壁纸已设置", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun setLock(context: Context, bitmap: Bitmap) {
        try {
            val wm = WallpaperManager.getInstance(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )
            } else {
                wm.setBitmap(bitmap)
            }

            Toast.makeText(context, "锁屏壁纸已设置", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * HyperOS4 + Shizuku：按引擎规则扩展位图。
     * OS4 壁纸引擎计算 surface 时把位图缩放钳制在 ≤1（不放大覆盖屏幕），
     * 随屏滚动下 surface 宽高比会偏离位图比例，导致壁纸被纵向居中裁切。
     * 按用户提供的屏幕分辨率把位图均匀放大到恰好覆盖屏幕，使钳制失效，
     * 引擎计算结果与 HyperOS3 一致，选定区域即可完整显示。
     */
    fun expandForOs4(context: Context, src: Bitmap, screenW: Int, screenH: Int): Bitmap {
        val scale = maxOf(
            screenW / src.width.toFloat(),
            screenH / src.height.toFloat()
        )
        if (scale <= 1f) return src
        var nw = (src.width * scale + 0.5f).toInt()
        var nh = (src.height * scale + 0.5f).toInt()
        // GL 纹理尺寸上限保护
        val maxDim = 4096
        if (nw > maxDim || nh > maxDim) {
            val k = maxDim / maxOf(nw, nh).toFloat()
            nw = (nw * k).toInt()
            nh = (nh * k).toInt()
        }
        if (nw <= src.width || nh <= src.height) return src
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        Toast.makeText(context, "已按 HyperOS4 引擎规则扩展壁纸至 ${nw}x${nh}", Toast.LENGTH_LONG).show()
        return out
    }
}
