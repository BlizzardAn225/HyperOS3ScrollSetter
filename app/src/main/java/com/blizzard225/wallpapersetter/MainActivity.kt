package com.blizzard225.wallpapersetter

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {

    private var originalBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private var shizukuAvailable = false
    private var shizukuPermissionGranted = false

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val bitmap = decodeUri(it)
                if (bitmap != null) {
                    originalBitmap = bitmap
                    findViewById<ImageView>(R.id.preview).setImageBitmap(bitmap)
                    findViewById<View>(R.id.previewPlaceholder).visibility = View.GONE
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initShizuku()

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            imagePicker.launch("image/*")
        }

        findViewById<Button>(R.id.btnSetStandard).setOnClickListener {
            applyCropAndSetWallpaper(false)
        }

        findViewById<Button>(R.id.btnSetDirect).setOnClickListener {
            applyCropAndSetWallpaper(true)
        }

        updateButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (shizukuAvailable) {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    private fun initShizuku() {
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
        Toast.makeText(this, "正在通过 Shizuku 执行...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val clazz = Class.forName("rikka.shizuku.Shizuku")
                val method = clazz.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true

                val process = method.invoke(
                    null,
                    arrayOf("settings", "put", "secure", "pref_key_wallpaper_screen_scrolled_span", "1"),
                    null,
                    null
                ) as Process

                val exitCode = process.waitFor()

                runOnUiThread {
                    if (exitCode == 0) {
                        Toast.makeText(this, "随屏滚动已开启", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "执行失败，退出码：$exitCode", Toast.LENGTH_LONG).show()
                        copyCommandToClipboard()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "执行异常：${e.message}", Toast.LENGTH_LONG).show()
                    copyCommandToClipboard()
                }
            }
        }.start()
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

    private fun applyCropAndSetWallpaper(
        directWrite: Boolean
    ) {

        val original = originalBitmap ?: run {
            Toast.makeText(
                this,
                "请先选择图片",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        croppedBitmap = original

        if (directWrite) {
            setWallpaperDirect(croppedBitmap!!)
        } else {
            setWallpaperStandard(croppedBitmap!!)
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
                "壁纸已设置（标准 API）",
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

    private fun setWallpaperDirect(bitmap: Bitmap) {

        val tmpFile = File(
            cacheDir,
            "tmp_wallpaper.jpg"
        )

        try {

            FileOutputStream(tmpFile).use { out ->
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    100,
                    out
                )
            }

            try {
                val miuiContentUri =
                    Uri.parse(
                        "content://com.miui.miwallpaper.wallpaper"
                    )

                contentResolver
                    .openOutputStream(miuiContentUri)
                    ?.use { os ->

                        tmpFile.inputStream().use { input ->
                            input.copyTo(os)
                        }

                        Toast.makeText(
                            this,
                            "通过 ContentProvider 写入成功",
                            Toast.LENGTH_SHORT
                        ).show()

                        return
                    }

            } catch (_: Exception) {
            }

            Toast.makeText(
                this,
                "直接写入失败，请使用 ADB 手动推送。",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "保存失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPostSetupHint() {
        Toast.makeText(
            this,
            "壁纸已设置。\n建议开启随屏滚动。",
            Toast.LENGTH_LONG
        ).show()
    }
}
