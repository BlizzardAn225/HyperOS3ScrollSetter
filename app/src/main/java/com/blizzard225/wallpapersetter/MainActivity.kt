package com.blizzard225.wallpapersetter

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private var originalBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private var shizukuAvailable = false
    private var shizukuPermissionGranted = false
    private var moduleActive = false
    private var shizukuInitialized = false

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

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

        checkModuleStatus()

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
            btn.setTextColor(Color.GRAY)
            btn.setOnClickListener { }
            return
        }

        if (shizukuPermissionGranted) {
            btn.text = "开启随屏滚动"
            btn.setTextColor(Color.WHITE)

            btn.setOnClickListener {
                enableScroll()
            }
        } else {
            btn.text = "复制命令"
            btn.setTextColor(Color.WHITE)

            btn.setOnClickListener {
                copyCommandToClipboard()
            }
        }
    }

    private fun checkModuleStatus() {
        moduleActive = ModuleStatus.isActive
        updateModuleStatusCard()
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

        croppedBitmap = cropped

        if (cbApplyBoth.isChecked) {
            setWallpaperBoth(croppedBitmap!!)
        } else {
            setWallpaperStandard(croppedBitmap!!)
        }
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
                "桌面 + 锁屏壁纸已设置\n锁屏将跟随桌面滚动",
                Toast.LENGTH_LONG
            ).show()

            showPostSetupHint()

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

            showPostSetupHint()

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

        croppedBitmap = cropped
        setWallpaperLock(croppedBitmap!!)
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

    private fun showPostSetupHint() {
        if (moduleActive) return
        Toast.makeText(
            this,
            "壁纸已设置。\n建议开启随屏滚动。",
            Toast.LENGTH_LONG
        ).show()
    }
}
