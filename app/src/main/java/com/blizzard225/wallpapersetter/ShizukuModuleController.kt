package com.blizzard225.wallpapersetter

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import rikka.shizuku.Shizuku

/**
 * 模块激活状态 + Shizuku 域（自 MainActivity 拆出）：
 * 状态卡片（含激活 binder 1s 宽限检测）、Shizuku 初始化与授权、
 * 随屏滚动/壁纸缩放命令卡片（自动执行或复制命令）。
 * 状态落定后的联动（防压暗文案、宿主设备卡、模式行显隐）经 [onStatusApplied] 回调宿主。
 */
class ShizukuModuleController(
    private val activity: AppCompatActivity,
    private val onStatusApplied: () -> Unit
) {

    var moduleActive: Boolean = false
        private set

    private var shizukuAvailable = false
    private var shizukuPermissionGranted = false
    private var shizukuInitialized = false

    // 激活 binder 异步到达（通常 <1s）：打开 app 后先宽限 1s 再判"未激活"/初始化
    // Shizuku，避免已启用模块时闪"未激活"和"未检测到 Shizuku"提示（service 链路时序）
    private val mainHandler = Handler(Looper.getMainLooper())
    private var graceDeadline = 0L

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private lateinit var moduleStatusCard: MaterialCardView
    private lateinit var moduleStatusIcon: TextView
    private lateinit var moduleStatusText: TextView
    private lateinit var jitterCard: View
    private lateinit var btnZoomOff: MaterialButton
    private lateinit var btnZoomOn: MaterialButton

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                shizukuPermissionGranted =
                    grantResult == PackageManager.PERMISSION_GRANTED
                updateModuleStatusCard()
                updateButtonState()

                Toast.makeText(
                    activity,
                    if (shizukuPermissionGranted)
                        "Shizuku 权限已授予"
                    else
                        "Shizuku 权限被拒绝",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    fun setup() {
        moduleStatusCard = activity.findViewById(R.id.moduleStatusCard)
        moduleStatusIcon = activity.findViewById(R.id.moduleStatusIcon)
        moduleStatusText = activity.findViewById(R.id.moduleStatusText)
        jitterCard = activity.findViewById(R.id.jitterCard)
        btnZoomOff = activity.findViewById(R.id.btnZoomOff)
        btnZoomOn = activity.findViewById(R.id.btnZoomOn)
    }

    fun teardown() {
        if (shizukuInitialized && shizukuAvailable) {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    /** 仅刷新命令卡片（原 onCreate 末尾的独立 updateButtonState 调用点） */
    fun refreshCommandButtons() {
        updateButtonState()
    }

    /** 模块状态刷新入口（onCreate / onResume / service binder 事件）。宽限期内只刷检测中卡片 */
    fun refresh() {
        moduleActive = ModuleStatus.isActive
        if (!moduleActive) {
            val now = SystemClock.uptimeMillis()
            if (graceDeadline == 0L) {
                graceDeadline = now + 1000
                mainHandler.postDelayed({ refresh() }, 1000)
            }
            if (now < graceDeadline) {
                // 宽限期内：中性"检测中"状态，不落"未激活"、不初始化 Shizuku；
                // binder 到达时 onServiceBind 会立即刷新为"已激活"
                moduleStatusCard.setCardBackgroundColor(Color.parseColor("#C4C4C4"))
                moduleStatusIcon.text = "…"
                moduleStatusText.text = "正在检测模块激活…"
                return
            }
        }
        updateModuleStatusCard()
        onStatusApplied()
        if (!moduleActive) {
            initShizuku()
        }
        updateButtonState()
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
                activity,
                "未检测到 Shizuku，将使用复制命令模式",
                Toast.LENGTH_LONG
            ).show()
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
                activity,
                "请求 Shizuku 权限失败：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Shizuku 掉线/丢权限的统一反馈：状态卡片转"未激活"并提示。
     * 前置检查发现掉线、或命令执行中掉线（exec 返回 -1）都会走这里。
     */
    private fun onShizukuBroken() {
        shizukuAvailable = false
        shizukuPermissionGranted = false
        updateModuleStatusCard()
        Toast.makeText(activity, "执行失败，检查Shizuku是否正常工作", Toast.LENGTH_LONG).show()
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

        val btn = activity.findViewById<MaterialButton>(R.id.btnCopyCommand)

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
                runShizukuAction { WallpaperCommands.enableScroll(activity) { onShizukuBroken() } }
            }
        } else {
            btn.text = "复制命令"

            btn.setOnClickListener {
                WallpaperCommands.copyScrollCommand(activity)
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
                runShizukuAction { WallpaperCommands.setWallpaperZoom(activity, false) { onShizukuBroken() } }
            }
            btnZoomOn.setOnClickListener {
                runShizukuAction { WallpaperCommands.setWallpaperZoom(activity, true) { onShizukuBroken() } }
            }
        } else {
            btnZoomOff.text = "复制关闭壁纸缩放命令"
            btnZoomOn.text = "复制打开壁纸缩放命令"
            btnZoomOff.setOnClickListener { WallpaperCommands.copyZoomCommand(activity, false) }
            btnZoomOn.setOnClickListener { WallpaperCommands.copyZoomCommand(activity, true) }
        }
    }
}
