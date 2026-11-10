package com.blizzard225.wallpapersetter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/**
 * 裁剪预览 + 分模式写入域（自 MainActivity 拆出）：
 * 裁剪视图与比例 chips、浅/深模式位图与裁剪框缓存、模式勾选 UI、
 * 模块模式写入分发（FileProvider + 广播）与写入结果接收。
 * 模块状态（moduleActive）由 [isActive] 注入，宿主为 [MainActivity]。
 */
class ModeWallpaperController(
    private val activity: AppCompatActivity,
    private val isActive: () -> Boolean
) {

    // 各模式裁剪框（0..1 源图比例）：跨勾选切换持久，不因换图/换分辨率清除（需求3）；
    // 两图同分辨率时调整互相传播（需求4）
    var lightBitmap: Bitmap? = null
        private set
    var darkBitmap: Bitmap? = null
        private set
    private var lightCropF: RectF? = null
    private var darkCropF: RectF? = null
    private var lastActiveMode: Boolean? = null
    private var suppressModeCallback = false

    private val ACTION_APPLY_WALLPAPER_MODE =
        "com.blizzard225.wallpapersetter.ACTION_APPLY_WALLPAPER_MODE"
    private val ACTION_APPLY_RESULT = "com.blizzard225.wallpapersetter.ACTION_APPLY_RESULT"
    private val WALLPAPER_PKG = "com.miui.miwallpaper"

    val cropImageView: CropImageView = activity.findViewById(R.id.cropImageView)
    val btnPick: MaterialButton = activity.findViewById(R.id.btnPick)
    private val cropOptionsCard: View = activity.findViewById(R.id.cropOptionsCard)
    private val previewPlaceholder: View = activity.findViewById(R.id.previewPlaceholder)
    private val chipGroupRatio: ChipGroup = activity.findViewById(R.id.chipGroupRatio)
    private val customRatioLayout: View = activity.findViewById(R.id.customRatioLayout)
    private val editRatioWidth: TextInputEditText = activity.findViewById(R.id.editRatioWidth)
    private val editRatioHeight: TextInputEditText = activity.findViewById(R.id.editRatioHeight)
    private val cbApplyBoth: MaterialCheckBox = activity.findViewById(R.id.cbApplyBoth)
    private val modeSelectRow: View = activity.findViewById(R.id.modeSelectRow)
    private val cbModeLight: MaterialCheckBox = activity.findViewById(R.id.cbModeLight)
    private val cbModeDark: MaterialCheckBox = activity.findViewById(R.id.cbModeDark)
    private val tvModeHint: TextView = activity.findViewById(R.id.tvModeHint)

    /** 模块写入结果：hook 回传（其自身 Toast 被系统屏蔽）。成功→"设置成功"+清缓存；失败文案与 Shizuku 路径一致 */
    private val applyResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra("success", false) ?: return
            if (success) {
                Toast.makeText(activity, "设置成功", Toast.LENGTH_SHORT).show()
                clearApplyCache()
            } else {
                val error = intent.getStringExtra("error") ?: "未知原因"
                Toast.makeText(activity, "设置失败：$error", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 接收器注册 + 比例 chips + 应用按钮联动 + 模式勾选 UI（原 onCreate 分散接线收拢） */
    fun setup() {
        val resultFilter = IntentFilter(ACTION_APPLY_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(applyResultReceiver, resultFilter, Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(applyResultReceiver, resultFilter)
        }

        setupRatioChips()

        cbApplyBoth.setOnCheckedChangeListener { _, isChecked ->
            activity.findViewById<MaterialButton>(R.id.btnSetStandard).text =
                if (isChecked) "设置桌面 + 锁屏壁纸" else "设置桌面壁纸"
            updateApplyButtons()
        }

        setupModeUi()
    }

    fun teardown() {
        try {
            activity.unregisterReceiver(applyResultReceiver)
        } catch (_: Exception) {
        }
    }

    /** Shizuku 路径读取"桌面+锁屏"合并勾选（cbApplyBoth 归本控制器所有） */
    fun isApplyBothChecked(): Boolean = cbApplyBoth.isChecked

    /** 相册选图落地：归属当前勾选模式并复位裁剪视图（原 imagePicker 回调体） */
    fun onImagePicked(bitmap: Bitmap) {
        val toDark = cbModeDark.isChecked
        if (toDark) darkBitmap = bitmap else lightBitmap = bitmap
        cropImageView.setAspectRatio(null)
        cropImageView.reset()
        previewPlaceholder.visibility = View.GONE
        cropOptionsCard.visibility = View.VISIBLE
        btnPick.text = "重新选择"

        findViewById<Chip>(R.id.chipFree).isChecked = true
        customRatioLayout.visibility = View.GONE

        if (!cbModeLight.isChecked && !cbModeDark.isChecked) {
            suppressModeCallback = true
            cbModeLight.isChecked = true
            suppressModeCallback = false
        }
        displayModeImage()
    }

    private fun <T : View> findViewById(id: Int): T = activity.findViewById(id)

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
                Toast.makeText(activity, "请输入有效的比例", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupModeUi() {
        cbModeLight.isChecked = true
        cbModeDark.isChecked = false
        cbModeLight.setOnCheckedChangeListener { _, isChecked ->
            if (suppressModeCallback) return@setOnCheckedChangeListener
            if (isChecked && cbModeDark.isChecked) {
                suppressModeCallback = true
                cbModeDark.isChecked = false
                suppressModeCallback = false
            }
            onModeChanged()
        }
        cbModeDark.setOnCheckedChangeListener { _, isChecked ->
            if (suppressModeCallback) return@setOnCheckedChangeListener
            if (isChecked && cbModeLight.isChecked) {
                suppressModeCallback = true
                cbModeLight.isChecked = false
                suppressModeCallback = false
            }
            onModeChanged()
        }
        updateModeUiVisibility()
    }

    fun updateModeUiVisibility() {
        val vis = if (isActive()) View.VISIBLE else View.GONE
        modeSelectRow.visibility = vis
        tvModeHint.visibility = vis
        updateApplyButtons()
    }

    /** 应用按钮：模块模式且勾选了任一模式时可用；双图都选后变为"同时设置浅色/深色…"（需求2） */
    private fun updateApplyButtons() {
        val btnStandard = activity.findViewById<MaterialButton>(R.id.btnSetStandard)
        val btnLock = activity.findViewById<MaterialButton>(R.id.btnSetLock)
        if (!isActive()) {
            btnStandard.isEnabled = true
            btnStandard.alpha = 1f
            btnLock.isEnabled = true
            btnLock.alpha = 1f
            return
        }
        val anyMode = cbModeLight.isChecked || cbModeDark.isChecked
        val bothImages = lightBitmap != null && darkBitmap != null
        btnStandard.text = when {
            bothImages -> "同时设置浅色/深色桌面壁纸"
            cbApplyBoth.isChecked -> "设置桌面 + 锁屏壁纸"
            else -> "设置桌面壁纸"
        }
        btnLock.text = if (bothImages) "同时设置浅色/深色锁屏壁纸" else "设置锁屏壁纸"
        btnStandard.isEnabled = anyMode
        btnStandard.alpha = if (anyMode) 1f else 0.5f
        // 双图模式两按钮各自独立（桌面/锁屏）；cbApplyBoth 的合并语义仅单图模式生效
        btnLock.isEnabled = anyMode && !(cbApplyBoth.isChecked && !bothImages)
        btnLock.alpha = if (btnLock.isEnabled) 1f else 0.5f
    }

    /** 当前激活模式：true=深色 false=浅色 null=都未勾选 */
    private fun activeModeIsDark(): Boolean? = when {
        cbModeDark.isChecked -> true
        cbModeLight.isChecked -> false
        else -> null
    }

    /** 当前模式自己的裁剪框（0..1 比例，跨切换持久，不因换图清除） */
    private fun modeCropF(dark: Boolean): RectF? = if (dark) darkCropF else lightCropF

    private fun storeModeF(dark: Boolean, f: RectF) {
        if (dark) darkCropF = RectF(f) else lightCropF = RectF(f)
    }

    /** 用裁剪框（比例）直接裁源图（用于"同时设置浅色/深色"的非激活模式位图） */
    private fun cropByRect(bitmap: Bitmap, f: RectF?): Bitmap {
        if (f == null) return bitmap
        val l = (f.left * bitmap.width).coerceIn(0f, bitmap.width.toFloat()).toInt()
        val t = (f.top * bitmap.height).coerceIn(0f, bitmap.height.toFloat()).toInt()
        val r = (f.right * bitmap.width).coerceIn(0f, bitmap.width.toFloat()).toInt()
        val b = (f.bottom * bitmap.height).coerceIn(0f, bitmap.height.toFloat()).toInt()
        if (r <= l || b <= t) return bitmap
        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
    }

    /** 切换前把当前裁剪框比例存入当前模式；两图同分辨率时同步传播到另一模式（需求4） */
    private fun captureCropRect() {
        val mode = lastActiveMode ?: return
        val f = cropImageView.getCropRectFractions() ?: return
        val bitmap = (if (mode) darkBitmap else lightBitmap) ?: return
        storeModeF(mode, f)
        val other = if (mode) lightBitmap else darkBitmap
        if (other != null && other.width == bitmap.width && other.height == bitmap.height) {
            storeModeF(!mode, f)
        }
    }

    /**
     * 预览框跟随激活模式：该模式有缓存图则显示，并恢复该模式自己的裁剪框
     * （0..1 比例，由 onDraw 在 imageRect 就绪时应用，同分辨率双图切换坐标不变）；
     * 无缓存图则清空预览（缓存不清）。
     */
    private fun displayModeImage() {
        val mode = activeModeIsDark()
        lastActiveMode = mode
        val target = when (mode) {
            true -> darkBitmap
            false -> lightBitmap
            null -> return
        }
        btnPick.text = if (target != null) "重新选择" else "从相册选择图片"
        // 空态：隐藏裁剪视图本体、显示占位提示；「裁剪比例」卡保持常显
        if (target == null) {
            cropImageView.visibility = View.GONE
            previewPlaceholder.visibility = View.VISIBLE
            return
        }
        cropImageView.visibility = View.VISIBLE
        previewPlaceholder.visibility = View.GONE
        cropImageView.setImageBitmap(target)
        cropImageView.reset()
        modeCropF(mode == true)?.let { cropImageView.setCropRectFractions(it) }
        cropOptionsCard.visibility = View.VISIBLE
    }

    private fun onModeChanged() {
        captureCropRect()
        displayModeImage()
        updateApplyButtons()
    }

    /**
     * 模块模式下的分模式写入：勾选哪个模式只替换哪个模式的壁纸，另一模式保持不变。
     * 图片经 FileProvider 交给壁纸进程 hook 执行（同进程直调 MIUI 内部 API 免调用方校验）；
     * 浅色/深色配对与当前壁纸类型（image/dark）由 hook 按实际状态处理。
     */
    fun dispatchModeApply(forLock: Boolean) {
        val wantDark = cbModeDark.isChecked
        val wantLight = cbModeLight.isChecked
        if (!wantDark && !wantLight) return
        val bothImages = lightBitmap != null && darkBitmap != null

        if (wantDark && darkBitmap == null) {
            Toast.makeText(activity, "请先选择深色图片", Toast.LENGTH_SHORT).show()
            return
        }
        if (wantLight && lightBitmap == null) {
            Toast.makeText(activity, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        captureCropRect()
        var lightCrop: Bitmap? = null
        var darkCrop: Bitmap? = null
        if (bothImages) {
            // 需求2：双图都选 → 一次同时写浅+深，各按自己模式的裁剪框取图
            lightCrop = cropByRect(lightBitmap!!, modeCropF(false))
            darkCrop = cropByRect(darkBitmap!!, modeCropF(true))
        } else {
            val cropped = cropImageView.getCroppedBitmap()
            if (cropped == null) {
                Toast.makeText(activity, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
                return
            }
            if (wantDark) darkCrop = cropped else lightCrop = cropped
        }

        val mask = if (bothImages) 3 else if (wantDark) 2 else 1
        Toast.makeText(activity, "正在写入壁纸...", Toast.LENGTH_SHORT).show()
        Thread {
            val lightFile = lightCrop?.let { encodeToCache(it, "light") }
            val darkFile = darkCrop?.let { encodeToCache(it, "dark") }
            activity.runOnUiThread {
                if ((lightCrop != null && lightFile == null) || (darkCrop != null && darkFile == null)) {
                    Toast.makeText(activity, "图片写入缓存失败，请重试", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                sendApplyBroadcast(resolveWhich(forLock), mask, lightFile, darkFile)
            }
        }.start()
    }

    /** which 语义与 MIUI 一致：1=桌面 2=锁屏 3=两者；双图模式下桌面/锁屏按钮各自独立 */
    private fun resolveWhich(forLock: Boolean): Int = when {
        lightBitmap != null && darkBitmap != null -> if (forLock) 2 else 1
        cbApplyBoth.isChecked -> 3
        forLock -> 2
        else -> 1
    }

    /**
     * PNG 无损缓存。广色域位图（实测 DCI-P3）先统一转 sRGB：引擎渲染链对
     * DCI-P3 标记文件的显示有发白/降饱和偏差，官方锁屏编辑器写入的即为
     * sRGB 形态；Canvas 跨色域绘制做色彩转换，外观保持不变。
     */
    private fun encodeToCache(bitmap: Bitmap, name: String): File? = try {
        val dir = File(activity.cacheDir, "wallpaper_apply")
        dir.mkdirs()
        File(dir, "$name.jpg").delete()
        File(dir, "$name.png").delete()
        val file = File(dir, "$name.png")
        val src = if (bitmap.colorSpace == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)) bitmap else {
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
            out
        }
        file.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 0, it) }
        file
    } catch (e: Exception) {
        null
    }

    private fun sendApplyBroadcast(which: Int, mask: Int, lightFile: File?, darkFile: File?) {
        try {
            val intent = Intent(ACTION_APPLY_WALLPAPER_MODE).setPackage(WALLPAPER_PKG)
            intent.putExtra("which", which)
            intent.putExtra("mask", mask)
            fun putUri(name: String, file: File?) {
                if (file == null) return
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                activity.grantUriPermission(WALLPAPER_PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.putExtra(name, uri.toString())
            }
            putUri("light_uri", lightFile)
            putUri("dark_uri", darkFile)
            activity.sendBroadcast(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "发送失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 写入成功后清空 FileProvider 缓存图（静默；图片此时已被壁纸进程消费） */
    fun clearApplyCache() {
        try {
            File(activity.cacheDir, "wallpaper_apply").deleteRecursively()
        } catch (_: Exception) {
        }
    }
}
