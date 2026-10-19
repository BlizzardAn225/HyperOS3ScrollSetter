package com.blizzard225.wallpapersetter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku

/**
 * 主界面：壁纸裁剪/设置 + 模块状态 + 宿主设备 + 随屏滚动/壁纸缩放命令卡片。
 * 命令执行见 [WallpaperCommands]/[ShizukuRunner]，位图处理见 [WallpaperBitmap]/
 * [AntiDarkenCompensator]，宿主设备配置见 [HostSystemConfig]。
 */
class MainActivity : AppCompatActivity() {

    private var originalBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private var shizukuAvailable = false
    private var shizukuPermissionGranted = false
    private var moduleActive = false
    private var shizukuInitialized = false

    private lateinit var hostConfig: HostSystemConfig

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
    private lateinit var jitterCard: View
    private lateinit var btnZoomOff: MaterialButton
    private lateinit var btnZoomOn: MaterialButton

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val bitmap = WallpaperBitmap.decodeUri(this, it)
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
        jitterCard = findViewById(R.id.jitterCard)
        btnZoomOff = findViewById(R.id.btnZoomOff)
        btnZoomOn = findViewById(R.id.btnZoomOn)

        hostConfig = HostSystemConfig(this) { moduleActive }

        checkModuleStatus()
        hostConfig.setup()
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

    // ===================== Shizuku / 模块状态 =====================

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

    private fun checkModuleStatus() {
        moduleActive = ModuleStatus.isActive
        updateModuleStatusCard()
        updateAntiDarkenLabel()
        hostConfig.applyVisibility()
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
            // 直接以 pingBinder 的结果为准（旧写法忽略返回值，Shizuku 已死时也判为可用）
            Shizuku.pingBinder()
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

    // ===================== 命令卡片（随屏滚动 / 应对壁纸抖动） =====================

    /**
     * Shizuku 掉线/丢权限的统一反馈：状态卡片转"未激活"并提示。
     * 前置检查发现掉线、或命令执行中掉线（exec 返回 -1）都会走这里。
     */
    private fun onShizukuBroken() {
        shizukuAvailable = false
        shizukuPermissionGranted = false
        updateModuleStatusCard()
        Toast.makeText(this, "执行失败，检查Shizuku是否正常工作", Toast.LENGTH_LONG).show()
    }

    /**
     * Shizuku 执行前置检查：设备上 Shizuku 服务可能被自动关闭导致本 APP 丢权限。
     * 检查失败时状态卡片转为"未激活"、toast 提示，不执行命令；
     * Shizuku 恢复后再次点击即自动恢复执行。
     */
    private fun runShizukuAction(action: () -> Unit) {
        if (!(isShizukuAvailable() && isShizukuPermissionGranted())) {
            onShizukuBroken()
            return
        }
        shizukuPermissionGranted = true
        updateModuleStatusCard()
        action()
    }

    private fun updateButtonState() {
        updateZoomCard()

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
                runShizukuAction { WallpaperCommands.enableScroll(this) { onShizukuBroken() } }
            }
        } else {
            btn.text = "复制命令"

            btn.setOnClickListener {
                WallpaperCommands.copyScrollCommand(this)
            }
        }
    }

    /**
     * 应对壁纸抖动卡片：显示逻辑与"随屏滚动"卡片类似。
     * 模块模式（Framework 已修复缩放/滚动冲突）不显示本卡片；
     * Shizuku 已授权 → 按钮直接执行命令；未授权 → 按钮复制命令。
     */
    private fun updateZoomCard() {
        if (moduleActive) {
            jitterCard.visibility = View.GONE
            return
        }
        jitterCard.visibility = View.VISIBLE
        if (shizukuPermissionGranted) {
            btnZoomOff.text = "关闭壁纸缩放"
            btnZoomOn.text = "打开壁纸缩放"
            btnZoomOff.setOnClickListener {
                runShizukuAction { WallpaperCommands.setWallpaperZoom(this, false) { onShizukuBroken() } }
            }
            btnZoomOn.setOnClickListener {
                runShizukuAction { WallpaperCommands.setWallpaperZoom(this, true) { onShizukuBroken() } }
            }
        } else {
            btnZoomOff.text = "复制关闭壁纸缩放命令"
            btnZoomOn.text = "复制打开壁纸缩放命令"
            btnZoomOff.setOnClickListener { WallpaperCommands.copyZoomCommand(this, false) }
            btnZoomOn.setOnClickListener { WallpaperCommands.copyZoomCommand(this, true) }
        }
    }

    // ===================== 壁纸裁剪与设置 =====================

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
            val finalBitmap = maybeExpandForOs4(bitmap)
            if (finalBitmap != null) {
                if (both) {
                    WallpaperBitmap.setBoth(this, finalBitmap)
                } else {
                    WallpaperBitmap.setStandard(this, finalBitmap)
                }
            }
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
            val finalBitmap = maybeExpandForOs4(bitmap)
            if (finalBitmap != null) {
                WallpaperBitmap.setLock(this, finalBitmap)
            }
        }
    }

    /**
     * 若免Root+Shizuku 且勾选了逆运算，则在后台线程对壁纸做逆运算提亮，
     * 完成后回到主线程回调；模块模式、HyperOS4 或未勾选时直接回调原图。
     */
    private fun applyCompensated(bitmap: Bitmap, callback: (Bitmap) -> Unit) {
        if (!cbAntiDarken.isChecked || moduleActive) {
            croppedBitmap = bitmap
            callback(bitmap)
            return
        }
        Toast.makeText(this, "正在逆运算还原壁纸颜色...", Toast.LENGTH_SHORT).show()
        Thread {
            val compensated = AntiDarkenCompensator.compensate(bitmap)
            runOnUiThread {
                croppedBitmap = compensated
                callback(compensated)
            }
        }.start()
    }

    /**
     * HyperOS4 + Shizuku：按引擎规则扩展位图（见 WallpaperBitmap.expandForOs4）。
     * 手机：不执行逆运算扩展（返回原图）；平板：按屏幕分辨率扩展。
     * 返回 null 表示配置不完整、已提示用户并中止本次设置。
     */
    private fun maybeExpandForOs4(bitmap: Bitmap): Bitmap? {
        if (moduleActive || hostConfig.hostSystem != 4) return bitmap
        when (hostConfig.deviceType) {
            HostSystemConfig.DEVICE_PHONE -> return bitmap
            HostSystemConfig.DEVICE_TABLET -> {}
            else -> {
                Toast.makeText(this, "请先在「宿主设备」中勾选设备类型（平板/手机）", Toast.LENGTH_LONG).show()
                return null
            }
        }
        val res = hostConfig.requireExpandResolution() ?: return null
        return WallpaperBitmap.expandForOs4(this, bitmap, res[0], res[1])
    }

    // ===================== 防压暗开关 =====================

    /** HyperOS4 下暂不启用防压暗逻辑（模块不注入、Shizuku 不做逆运算），待验证后决定是否移除 */
    private fun updateAntiDarkenLabel() {
        if (moduleActive) {
            cbAntiDarken.text = "不压暗壁纸，更改后需重启壁纸APP"
        } else {
            cbAntiDarken.text = "逆运算，压暗后壁纸颜色近似还原，若颜色异常，请在相册手动调整后，不勾选此项设置壁纸"
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
                Toast.makeText(this, "重启「壁纸」APP 后生效", Toast.LENGTH_LONG).show()
            }
        }
    }
}
