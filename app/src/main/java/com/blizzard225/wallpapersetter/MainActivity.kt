package com.blizzard225.wallpapersetter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import rikka.shizuku.Shizuku

/**
 * 主界面接线层：装配 [ModeWallpaperController]（裁剪预览+分模式写入）、
 * [ShizukuModuleController]（模块状态+Shizuku+命令卡片）、[HostSystemConfig]（宿主设备）
 * 与防压暗开关；持有 Shizuku 免Root 壁纸设置路径（裁剪→逆运算→OS4 扩展→setBitmap）。
 * 命令执行见 [WallpaperCommands]/[ShizukuRunner]，位图处理见 [WallpaperBitmap]/
 * [AntiDarkenCompensator]。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var modeController: ModeWallpaperController
    private lateinit var shizukuController: ShizukuModuleController
    private lateinit var hostConfig: HostSystemConfig

    private lateinit var cbAntiDarken: MaterialCheckBox

    private val ANTI_DARKEN_PREFS = "wallpaper_anti_darken_prefs"
    private val ANTI_DARKEN_ENABLED_KEY = "enabled"
    private val ANTI_DARKEN_SETTINGS_KEY = "wallpaper_anti_darken_enabled"
    private val ACTION_UPDATE_ANTI_DARKEN = "com.blizzard225.wallpapersetter.ACTION_UPDATE_ANTI_DARKEN"
    private val WALLPAPER_PKG = "com.miui.miwallpaper"

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                WallpaperBitmap.decodeUri(this, it)?.let { bitmap ->
                    modeController.onImagePicked(bitmap)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        // 现代 libxposed API：模块 app 不再被自身注入，ModuleStatus 无法由 hook 侧
        // 写入；改为监听框架下发的 XposedService——收到 binder 即模块在 LSPosed 中
        // 已启用（service 库的 XposedProvider 在 app 进程启动时收 binder 并缓存，
        // 这里注册的回调会被立即补发）。
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                ModuleStatus.isActive = true
                runOnUiThread { shizukuController.refresh() }
            }

            override fun onServiceDied(service: XposedService) {
                ModuleStatus.isActive = false
                runOnUiThread { shizukuController.refresh() }
            }
        })

        cbAntiDarken = findViewById(R.id.cbAntiDarken)

        modeController = ModeWallpaperController(this) { shizukuController.moduleActive }
        shizukuController = ShizukuModuleController(this) { onStatusApplied() }
        hostConfig = HostSystemConfig(this) { shizukuController.moduleActive }

        shizukuController.setup()
        shizukuController.refresh()
        shizukuController.refreshCommandButtons()
        hostConfig.setup()
        setupAntiDarkenCheckbox()
        modeController.setup()

        modeController.btnPick.setOnClickListener {
            imagePicker.launch("image/*")
        }

        findViewById<Button>(R.id.btnSetStandard).setOnClickListener {
            applyCropAndSetWallpaper()
        }

        findViewById<Button>(R.id.btnSetLock).setOnClickListener {
            applyCropAndSetLockWallpaper()
        }
    }

    override fun onResume() {
        super.onResume()
        shizukuController.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        modeController.teardown()
        shizukuController.teardown()
    }

    /** 模块状态落定后的跨域联动（防压暗文案 / 宿主设备卡 / 模式行显隐） */
    private fun onStatusApplied() {
        updateAntiDarkenLabel()
        hostConfig.applyVisibility()
        modeController.updateModeUiVisibility()
    }

    // ===================== 壁纸设置（Shizuku 免Root 路径） =====================

    private fun applyCropAndSetWallpaper() {
        if (shizukuController.moduleActive) {
            modeController.dispatchModeApply(forLock = false)
            return
        }
        if (modeController.lightBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = modeController.cropImageView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        applyCompensated(cropped) { bitmap ->
            val finalBitmap = maybeExpandForOs4(bitmap)
            if (finalBitmap != null) {
                val ok = if (modeController.isApplyBothChecked()) WallpaperBitmap.setBoth(this, finalBitmap)
                else WallpaperBitmap.setStandard(this, finalBitmap)
                if (ok) {
                    modeController.clearApplyCache()
                }
            }
        }
    }

    private fun applyCropAndSetLockWallpaper() {
        if (shizukuController.moduleActive) {
            modeController.dispatchModeApply(forLock = true)
            return
        }
        if (modeController.lightBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = modeController.cropImageView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, "裁剪失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }

        applyCompensated(cropped) { bitmap ->
            val finalBitmap = maybeExpandForOs4(bitmap)
            if (finalBitmap != null) {
                if (WallpaperBitmap.setLock(this, finalBitmap)) {
                    modeController.clearApplyCache()
                }
            }
        }
    }

    /**
     * 若免Root+Shizuku 且勾选了逆运算，则在后台线程对壁纸做逆运算提亮，
     * 完成后回到主线程回调；模块模式、HyperOS4 或未勾选时直接回调原图。
     */
    private fun applyCompensated(bitmap: Bitmap, callback: (Bitmap) -> Unit) {
        if (!cbAntiDarken.isChecked || shizukuController.moduleActive) {
            callback(bitmap)
            return
        }
        Toast.makeText(this, "正在逆运算还原壁纸颜色...", Toast.LENGTH_SHORT).show()
        Thread {
            val compensated = AntiDarkenCompensator.compensate(bitmap)
            runOnUiThread {
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
        if (shizukuController.moduleActive || hostConfig.hostSystem != 4) return bitmap
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
        if (shizukuController.moduleActive) {
            cbAntiDarken.text = "不压暗壁纸，更改后需重启壁纸APP"
        } else {
            cbAntiDarken.text = "逆运算，压暗后壁纸颜色近似还原，若颜色异常，请在相册手动调整后，不勾选此项设置壁纸"
        }
    }

    private fun setupAntiDarkenCheckbox() {
        // 模块模式下开关状态由壁纸进程 hook 经 Settings.Secure 维护（跨进程可靠）；
        // 免Root 模式存于 APP 自身 SharedPreferences，直接读 isChecked 即时生效。
        val saved = if (shizukuController.moduleActive) {
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
            if (shizukuController.moduleActive) {
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
