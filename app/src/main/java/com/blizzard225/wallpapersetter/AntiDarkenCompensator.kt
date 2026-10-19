package com.blizzard225.wallpapersetter

import android.graphics.Bitmap

/** 壁纸压暗（transformColor）的逆运算：设置前把壁纸提亮，压暗后表现为原图 */
object AntiDarkenCompensator {

    /**
     * 构建 reverseLut：reverseLut[k] = 输入明度 z_X（0..1），使得
     * transformColor(z_X) 输出的明度 ≈ k/255 * 0.9。
     *
     * shader 中 transformColor 对明度 z 的正向映射：
     *   v' = mix(z, z*0.9 + 0.1, z) = 1.1z - 0.1z²
     *   最终明度 = v' * (1 - 0.1*z) = (1.1z - 0.1z²)(1 - 0.1z)，值域约为 [0, 0.9]
     */
    fun buildReverseLut(): FloatArray {
        val forwardLut = FloatArray(256)
        for (i in 0 until 256) {
            val z = i / 255f
            val vp = 1.1f * z - 0.1f * z * z
            forwardLut[i] = vp * (1f - 0.1f * z)
        }
        val reverseLut = FloatArray(256)
        for (k in 0 until 256) {
            val target = (k / 255f) * 0.9f
            var lo = 0
            var hi = 255
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (forwardLut[mid] <= target) lo = mid else hi = mid - 1
            }
            reverseLut[k] = lo / 255f
        }
        return reverseLut
    }

    /**
     * 对原始 Bitmap 执行 transformColor 的逆运算，得到"应设置"的壁纸 X，
     * 使得系统压暗（transformColor）后表现为原图。
     *
     * 每个像素：
     *   (1) 原图 HSV(h, s, v)
     *   (2) z_X = reverseLut(v) —— 使 (1.1z_X - 0.1z_X²)(1 - 0.1z_X) = v
     *   (3) s_X = s / 0.98（因为正向把饱和度乘 0.98）
     *   (4) 输出 X = hsv2rgb(h, s_X, z_X)
     */
    fun compensate(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val reverseLut = buildReverseLut()

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f

            // 原图 HSV
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val delta = maxC - minC

            var hue = 0f
            var sat = 0f
            if (maxC > 0f && delta > 1e-6f) {
                sat = delta / maxC
                hue = when (maxC) {
                    r -> ((g - b) / delta % 6f + 6f) % 6f
                    g -> (b - r) / delta + 2f
                    else -> (r - g) / delta + 4f
                }
                hue /= 6f
            }

            // z_X：反查使压暗后明度=原明度的输入明度
            val vOrig = maxC
            var zX = 1f
            if (vOrig < 0.9f) {
                zX = reverseLut[((vOrig / 0.9f) * 255f).toInt().coerceIn(0, 255)]
            }

            // s_X：补偿饱和度（上限 1 防溢出）
            val satX = (sat / 0.98f).coerceAtMost(1f)

            // HSV(h, satX, zX) → RGB
            val hi = (hue * 6f).toInt()
            val f = hue * 6f - hi
            val pVal = zX * (1f - satX)
            val qVal = zX * (1f - f * satX)
            val tVal = zX * (1f - (1f - f) * satX)

            val (rF, gF, bF) = when (hi % 6) {
                0 -> Triple(zX, tVal, pVal)
                1 -> Triple(qVal, zX, pVal)
                2 -> Triple(pVal, zX, tVal)
                3 -> Triple(pVal, qVal, zX)
                4 -> Triple(tVal, pVal, zX)
                else -> Triple(zX, pVal, qVal)
            }

            pixels[i] = (a shl 24) or
                    ((rF.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                    ((gF.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                    (bF.coerceIn(0f, 1f) * 255f).toInt()
        }

        val result = Bitmap.createBitmap(w, h, src.config)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}
