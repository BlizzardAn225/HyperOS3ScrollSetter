package com.blizzard225.wallpapersetter

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private var originalBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private var shizukuAvailable = false
    private var shizukuPermissionGranted = false
    private var moduleActive = false
    private var shizukuInitialized = false

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    private val ANTI_DARKEN_PREFS = "wallpaper_anti_darken_prefs"
    private val ANTI_DARKEN_ENABLED_KEY = "enabled"
    private val ANTI_DARKEN_SETTINGS_KEY = "wallpaper_anti_darken_enabled"
    private val ACTION_UPDATE_ANTI_DARKEN = "com.blizzard225.wallpapersetter.ACTION_UPDATE_ANTI_DARKEN"
    private val WALLPAPER_PKG = "com.miui.miwallpaper"

    private lateinit var cropImageView: CropImageView
    private lateinit var cropOptionsCard: View
    private lateinit var chipGroupRatio: ChipGroup
    private lateinit var customRatioLayout: View
    private lateinit var editRatioWidth: TextInputEditText
    private lateinit var editRatioHeight: TextInputEditText
    private lateinit var btnPick: MaterialButton
    private lateinit var moduleStatusCard: MaterialCardView
    private lateinit var moduleStatusIcon: TextView
    private lateinit var moduleStatusText: TextView
    private lateinit var cbApplyBoth: MaterialCheckBox
    private lateinit var cbAntiDarken: MaterialCheckBox

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val bitmap = decodeUri(it)
                if (bitmap != null) {
                    originalBitmap = bitmap
                    cropImageView.setImageBitmap(bitmap)
                    cropImageView.setAspectRatio(null)
                    cropImageView.reset()
                    findViewById<View>(R.id.previewPlaceholder).visibility = View.GONE
                    cropOptionsCard.visibility = View.VISIBLE
                    btnPick.text = "重新选择"

                    findViewById<Chip>(R.id.chipFree).isChecked = true
                    customRatioLayout.visibility = View.GONE
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        cropImageView = findViewById(R.id.cropImageView)
        cropOptionsCard = findViewById(R.id.cropOptionsCard)
        chipGroupRatio = findViewById(R.id.chipGroupRatio)
        customRatioLayout = findViewById(R.id.customRatioLayout)
        editRatioWidth = findViewById(R.id.editRatioWidth)
        editRatioHeight = findViewById(R.id.editRatioHeight)
        btnPick = findViewById(R.id.btnPick)
        moduleStatusCard = findViewById(R.id.moduleStatusCard)
        moduleStatusIcon = findViewById(R.id.moduleStatusIcon)
        moduleStatusText = findViewById(R.id.moduleStatusText)
        cbApplyBoth = findViewById(R.id.cbApplyBoth)
        cbAntiDarken = findViewById(R.id.cbAntiDarken)

        checkModuleStatus()
        setupAntiDarkenCheckbox()

        btnPick.setOnClickListener {
            imagePicker.launch("image/*")
        }

        findViewById<Button>(R.id.btnSetStandard).setOnClickListener {
            applyCropAndSetWallpaper()
        }

        findViewById<Button>(R.id.btnSetLock).setOnClickListener {
            applyCropAndSetLockWallpaper()
        }

        cbApplyBoth.setOnCheckedChangeListener { _, isChecked ->
            val btnSetStandard = findViewById<MaterialButton>(R.id.btnSetStandard)
            val btnSetLock = findViewById<MaterialButton>(R.id.btnSetLock)
            if (isChecked) {
                btnSetStandard.text = "设置桌面 + 锁屏壁纸"
                btnSetLock.isEnabled = false
                btnSetLock.alpha = 0.5f
            } else {
                btnSetStandard.text = "设置桌面壁纸"
                btnSetLock.isEnabled = true
                btnSetLock.alpha = 1.0f
            }
        }

        setupRatioChips()

        updateButtonState()
    }

    override fun onResume() {
        super.onResume()
        checkModuleStatus()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (shizukuInitialized && shizukuAvailable) {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    private fun setupRatioChips() {
        chipGroupRatio.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val chip = group.findViewById<Chip>(checkedIds[0])
            when (chip.id) {
                R.id.chipFree -> {
                    cropImageView.setAspectRatio(null)
                    customRatioLayout.visibility = View.GONE
                }
                R.id.chip21_9 -> {
                    cropImageView.setAspectRatio(21f / 9f)
                    customRatioLayout.visibility = View.GONE
                }
                R.id.chip16_9 -> {
                    cropImageView.setAspectRatio(16f / 9f)
                    customRatioLayout.visibility = View.GONE
                }
                R.id.chip16_10 -> {
                    cropImageView.setAspectRatio(16f / 10f)
                    customRatioLayout.visibility = View.GONE
                }
                R.id.chipCustom -> {
                    customRatioLayout.visibility = View.VISIBLE
                    applyCustomRatio()
                }
            }
        }

        findViewById<Button>(R.id.btnApplyCustomRatio).setOnClickListener {
            applyCustomRatio()
        }
    }

    private fun applyCustomRatio() {
        val widthStr = editRatioWidth.text.toString()
        val heightStr = editRatioHeight.text.toString()

        if (widthStr.isNotEmpty() && heightStr.isNotEmpty()) {
            val width = widthStr.toFloatOrNull()
            val height = heightStr.toFloatOrNull()

            if (width != null && height != null && width > 0 && height > 0) {
                cropImageView.setAspectRatio(width / height)
            } else {
                Toast.makeText(this, "请输入有效的比例", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initShizuku() {
        if (shizukuInitialized) return
        shizukuInitialized = true
        shizukuAvailable = isShizukuAvailable()

        if (shizukuAvailable) {
            shizukuPermissionGranted = isShizukuPermissionGranted()

            Shizuku.addRequestPermissionResultListener(permissionListener)

            if (!shizukuPermissionGranted) {
                requestShizukuPermission()
            }
        } else {
            Toast.makeText(
                this,
                "未检测到 Shizuku，将使用复制命令模式",
                Toast.LENGTH_LONG
            ).show()
        }

        updateButtonState()
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                shizukuPermissionGranted =
                    grantResult == PackageManager.PERMISSION_GRANTED
                updateModuleStatusCard()
                updateButtonState()

                Toast.makeText(
                    this,
                    if (shizukuPermissionGranted)
                        "Shizuku 权限已授予"
                    else
                        "Shizuku 权限被拒绝",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private fun updateButtonState() {
        val btn = findViewById<MaterialButton>(R.id.btnCopyCommand)

        if (moduleActive) {
            btn.text = "无需手动设置"
            btn.isEnabled = false
            btn.alpha = 0.5f
            btn.setOnClickListener { }
            return
        }

        btn.isEnabled = true
        btn.alpha = 1.0f

        if (shizukuPermissionGranted) {
            btn.text = "开启随屏滚动"

            btn.setOnClickListener {
                enableScroll()
            }
        } else {
            btn.text = "复制命令"

            btn.setOnClickListener {
                copyCommandToClipboard()
            }
        }
    }

    private fun checkModuleStatus() {
        moduleActive = ModuleStatus.isActive
        updateModuleStatusCard()
        updateAntiDarkenLabel()
        if (!moduleActive) {
            initShizuku()
        }
        updateButtonState()
    }

    private fun updateModuleStatusCard() {
        when {
            moduleActive -> {
                moduleStatusCard.setCardBackgroundColor(Color.parseColor("#31D362"))
                moduleStatusIcon.text = "✓"
                moduleStatusText.text = "已激活\nLSPosed"
            }
            shizukuPermissionGranted -> {
                moduleStatusCard.setCardBackgroundColor(Color.parseColor("#FF9A2F"))
                moduleStatusIcon.text = "○"
                moduleStatusText.text = "免Root工作中\nShizuku"
            }
            else -> {
                moduleStatusCard.setCardBackgroundColor(Color.parseColor("#FF0000"))
                moduleStatusIcon.text = "✕"
                moduleStatusText.text = "未激活\nNone"
            }
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(
                SHIZUKU_PERMISSION_REQUEST_CODE
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "请求 Shizuku 权限失败：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun enableScroll() {
        Toast.makeText(this, "正在执行，请稍候...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val startTime = System.currentTimeMillis()
                var killed = false

                while (true) {
                    val elapsed = System.currentTimeMillis() - startTime

                    // 执行 settings put
                    runShizukuCommand("settings", "put", "secure",
                        "pref_key_wallpaper_screen_scrolled_span", "1")

                    // 约1秒后杀进程（给settings put一点时间先写入）
                    if (!killed && elapsed >= 1000) {
                        runShizukuCommand("am", "force-stop", "com.miui.miwallpaper")
                        killed = true
                    }

                    // 杀进程3秒后停止循环
                    if (killed && elapsed >= 4000) {
                        break
                    }

                    Thread.sleep(500)
                }

                runOnUiThread {
                    Toast.makeText(this, "随屏滚动已开启", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "执行异常：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runShizukuCommand(vararg args: String): Int {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, args, null, null) as Process
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    private fun shizukuExec(
        newProcess: java.lang.reflect.Method,
        command: Array<String>
    ): Int {
        val process = newProcess.invoke(null, command, null, null) as Process
        val exitCode = process.waitFor()
        process.destroy()
        return exitCode
    }

    private fun shizukuGet(
        newProcess: java.lang.reflect.Method,
        command: Array<String>
    ): String {
        val process = newProcess.invoke(null, command, null, null) as Process
        val result = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor()
        process.destroy()
        return result
    }

    private fun copyCommandToClipboard() {

        val command = """
            settings put secure pref_key_wallpaper_screen_scrolled_span 1
        """.trimIndent()

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager

        val clip =
            ClipData.newPlainText("Shizuku 命令", command)

        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            this,
            "命令已复制到剪贴板！\n请在 Shizuku 终端中执行。",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "解码失败: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
            null
        }
    }

    private fun applyCropAndSetWallpaper() {
        if (originalBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = cropImageView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        val both = cbApplyBoth.isChecked
        applyCompensated(cropped) { bitmap ->
            if (both) {
                setWallpaperBoth(bitmap)
            } else {
                setWallpaperStandard(bitmap)
            }
        }
    }

    /**
     * 若免Root+Shizuku 且勾选了逆运算，则在后台线程对壁纸做逆运算提亮，
     * 完成后回到主线程回调；模块模式或未勾选时直接回调原图。
     */
    private fun applyCompensated(bitmap: Bitmap, callback: (Bitmap) -> Unit) {
        if (!cbAntiDarken.isChecked || moduleActive) {
            croppedBitmap = bitmap
            callback(bitmap)
            return
        }
        Toast.makeText(this, "正在逆运算还原壁纸颜色...", Toast.LENGTH_SHORT).show()
        Thread {
            val compensated = compensateAntiDarken(bitmap)
            runOnUiThread {
                croppedBitmap = compensated
                callback(compensated)
            }
        }.start()
    }

    private fun setWallpaperBoth(bitmap: Bitmap) {
        try {
            val wm = WallpaperManager.getInstance(this)

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

            Toast.makeText(
                this,
                "桌面 + 锁屏壁纸已设置",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "设置失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setWallpaperStandard(bitmap: Bitmap) {
        try {
            val wm = WallpaperManager.getInstance(this)

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

            Toast.makeText(
                this,
                "桌面壁纸已设置",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "设置失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyCropAndSetLockWallpaper() {
        if (originalBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = cropImageView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        applyCompensated(cropped) { bitmap ->
            setWallpaperLock(bitmap)
        }
    }

    private fun setWallpaperLock(bitmap: Bitmap) {
        try {
            val wm = WallpaperManager.getInstance(this)

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

            Toast.makeText(
                this,
                "锁屏壁纸已设置",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "设置失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupAntiDarkenCheckbox() {
        // 模块模式下开关状态由壁纸进程 hook 经 Settings.Secure 维护（跨进程可靠）；
        // 免Root 模式存于 APP 自身 SharedPreferences，直接读 isChecked 即时生效。
        val saved = if (moduleActive) {
            try {
                Settings.Secure.getInt(contentResolver, ANTI_DARKEN_SETTINGS_KEY, 0) == 1
            } catch (e: Exception) {
                false
            }
        } else {
            try {
                getSharedPreferences(ANTI_DARKEN_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(ANTI_DARKEN_ENABLED_KEY, false)
            } catch (e: Exception) {
                false
            }
        }
        cbAntiDarken.isChecked = saved

        // 根据激活模式设置不同文字
        updateAntiDarkenLabel()

        cbAntiDarken.setOnCheckedChangeListener { _, isChecked ->
            // 持久化到 APP 自身 SharedPreferences（免Root 逆运算路径使用）
            try {
                getSharedPreferences(ANTI_DARKEN_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(ANTI_DARKEN_ENABLED_KEY, isChecked).apply()
            } catch (e: Exception) {
            }
            if (moduleActive) {
                // 模块模式：广播通知壁纸进程 hook 更新防压暗开关
                try {
                    val intent = Intent(ACTION_UPDATE_ANTI_DARKEN)
                    intent.setPackage(WALLPAPER_PKG)
                    intent.putExtra("enabled", isChecked)
                    sendBroadcast(intent)
                } catch (e: Exception) {
                }
                Toast.makeText(this, "需重启壁纸APP后生效", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateAntiDarkenLabel() {
        if (moduleActive) {
            cbAntiDarken.text = "不压暗壁纸，更改后需重启壁纸APP"
        } else {
            cbAntiDarken.text = "逆运算，压暗后壁纸颜色被还原"
        }
    }

    /**
     * 构建 reverseLut：reverseLut[k] = 输入明度 z_X（0..1），使得
     * transformColor(z_X) 输出的明度 ≈ k/255 * 0.9。
     *
     * shader 中 transformColor 对明度 z 的正向映射：
     *   v' = mix(z, z*0.9 + 0.1, z) = 1.1z - 0.1z²
     *   最终明度 = v' * (1 - 0.1*z) = (1.1z - 0.1z²)(1 - 0.1z)，值域约为 [0, 0.9]
     */
    private fun buildReverseLut(): FloatArray {
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
    private fun compensateAntiDarken(src: Bitmap): Bitmap {
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