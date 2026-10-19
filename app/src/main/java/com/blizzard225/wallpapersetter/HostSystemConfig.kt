package com.blizzard225.wallpapersetter

import android.app.Activity
import android.content.Context
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

/**
 * 宿主设备卡片：HyperOS3 / HyperOS4 互斥勾选，初次安装无勾选。
 * 模块模式：勾选/变更后提示需要重启壁纸APP（模块按宿主版本应用对应 Hook 逻辑）；
 * Shizuku 模式：勾选后立即生效。
 * HyperOS4 下展开设备类型（平板/手机）：
 *   手机 → Shizuku 不执行逆运算扩展；平板 → 需勾选预设分辨率 3200×2136 或输入分辨率。
 */
class HostSystemConfig(
    private val activity: Activity,
    private val isModuleActive: () -> Boolean
) {

    companion object {
        const val DEVICE_PHONE = 1
        const val DEVICE_TABLET = 2
        private const val HOST_PREFS = "wallpaper_host_prefs"
        private const val HOST_KEY = "host_system"
        private const val DEVICE_KEY = "device_type"
        private const val TABLET_PRESET_KEY = "tablet_res_preset"
        private const val TABLET_RES_W_KEY = "tablet_res_w"
        private const val TABLET_RES_H_KEY = "tablet_res_h"
    }

    // hostSystem 0=未选择，3=HyperOS3，4=HyperOS4
    // deviceType 0=未选择，1=手机，2=平板（仅 HyperOS4 + Shizuku 生效）
    var hostSystem = 0
        private set
    var deviceType = 0
        private set
    var tabletPreset = true
        private set
    var tabletResW = 3200
        private set
    var tabletResH = 2136
        private set

    private var updatingHostUi = false
    private var updatingDeviceUi = false
    private lateinit var editResWidth: TextInputEditText
    private lateinit var editResHeight: TextInputEditText

    fun setup() {
        // 防御：若当前布局变体缺少宿主设备卡片则跳过，避免 findViewById 空指针
        if (activity.findViewById<View?>(R.id.hostSystemCard) == null) return
        val cbHostOs3 = activity.findViewById<MaterialCheckBox>(R.id.cbHostOs3)
        val cbHostOs4 = activity.findViewById<MaterialCheckBox>(R.id.cbHostOs4)
        val cbDevicePhone = activity.findViewById<MaterialCheckBox>(R.id.cbDevicePhone)
        val cbDeviceTablet = activity.findViewById<MaterialCheckBox>(R.id.cbDeviceTablet)
        val cbResPreset = activity.findViewById<MaterialCheckBox>(R.id.cbResPreset)
        editResWidth = activity.findViewById(R.id.editResWidth)
        editResHeight = activity.findViewById(R.id.editResHeight)

        val prefs = activity.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
        hostSystem = try {
            prefs.getInt(HOST_KEY, 0)
        } catch (e: Exception) {
            0
        }
        deviceType = try {
            prefs.getInt(DEVICE_KEY, 0)
        } catch (e: Exception) {
            0
        }
        tabletPreset = try {
            prefs.getBoolean(TABLET_PRESET_KEY, true)
        } catch (e: Exception) {
            true
        }
        tabletResW = try {
            prefs.getInt(TABLET_RES_W_KEY, 3200)
        } catch (e: Exception) {
            3200
        }
        tabletResH = try {
            prefs.getInt(TABLET_RES_H_KEY, 2136)
        } catch (e: Exception) {
            2136
        }

        updatingHostUi = true
        cbHostOs3.isChecked = hostSystem == 3
        cbHostOs4.isChecked = hostSystem == 4
        updatingHostUi = false
        updatingDeviceUi = true
        cbDevicePhone.isChecked = deviceType == DEVICE_PHONE
        cbDeviceTablet.isChecked = deviceType == DEVICE_TABLET
        cbResPreset.isChecked = tabletPreset
        updatingDeviceUi = false
        if (editResWidth.text.isNullOrBlank()) editResWidth.setText(tabletResW.toString())
        if (editResHeight.text.isNullOrBlank()) editResHeight.setText(tabletResH.toString())
        applyVisibility()

        cbHostOs3.setOnCheckedChangeListener { _, isChecked ->
            if (updatingHostUi) return@setOnCheckedChangeListener
            setHostSystem(if (isChecked) 3 else 0)
        }
        cbHostOs4.setOnCheckedChangeListener { _, isChecked ->
            if (updatingHostUi) return@setOnCheckedChangeListener
            setHostSystem(if (isChecked) 4 else 0)
        }
        cbDevicePhone.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDeviceUi) return@setOnCheckedChangeListener
            setDeviceType(if (isChecked) DEVICE_PHONE else 0)
        }
        cbDeviceTablet.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDeviceUi) return@setOnCheckedChangeListener
            setDeviceType(if (isChecked) DEVICE_TABLET else 0)
        }
        cbResPreset.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDeviceUi) return@setOnCheckedChangeListener
            tabletPreset = isChecked
            prefs.edit().putBoolean(TABLET_PRESET_KEY, isChecked).apply()
            applyVisibility()
        }
        editResWidth.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                tabletResW = v
                prefs.edit().putInt(TABLET_RES_W_KEY, v).apply()
            }
        })
        editResHeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                tabletResH = v
                prefs.edit().putInt(TABLET_RES_H_KEY, v).apply()
            }
        })
    }

    fun applyVisibility() {
        // 设备类型/分辨率子选项仅在 HyperOS4 + Shizuku 免Root 模式下显示（模块模式不显示）
        activity.findViewById<View?>(R.id.os4OptionsLayout)?.visibility =
            if (hostSystem == 4 && !isModuleActive()) View.VISIBLE else View.GONE
        // 平板：显示分辨率区块（预设勾选框 + 手输行），预设勾选时隐藏手输行
        activity.findViewById<View?>(R.id.tabletResLayout)?.visibility =
            if (hostSystem == 4 && !isModuleActive() && deviceType == DEVICE_TABLET)
                View.VISIBLE else View.GONE
        activity.findViewById<View?>(R.id.tabletCustomResLayout)?.visibility =
            if (tabletPreset) View.GONE else View.VISIBLE
    }

    /** HyperOS4 平板逆运算扩展所需的屏幕分辨率；null 表示配置未完成（已提示用户） */
    fun requireExpandResolution(): IntArray? {
        if (tabletPreset) return intArrayOf(3200, 2136)
        val w = if (this::editResWidth.isInitialized)
            editResWidth.text?.toString()?.toIntOrNull() ?: 0 else 0
        val h = if (this::editResHeight.isInitialized)
            editResHeight.text?.toString()?.toIntOrNull() ?: 0 else 0
        if (w <= 0 || h <= 0) {
            Toast.makeText(activity, "请勾选或输入屏幕分辨率", Toast.LENGTH_LONG).show()
            return null
        }
        return intArrayOf(w, h)
    }

    private fun setHostSystem(host: Int) {
        if (hostSystem == host) return
        hostSystem = host
        updatingHostUi = true
        activity.findViewById<MaterialCheckBox>(R.id.cbHostOs3).isChecked = host == 3
        activity.findViewById<MaterialCheckBox>(R.id.cbHostOs4).isChecked = host == 4
        updatingHostUi = false
        try {
            activity.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(HOST_KEY, host).apply()
        } catch (e: Exception) {
        }
        applyVisibility()
        onHostSystemChanged(host)
    }

    private fun setDeviceType(type: Int) {
        if (deviceType == type) return
        deviceType = type
        updatingDeviceUi = true
        activity.findViewById<MaterialCheckBox>(R.id.cbDevicePhone).isChecked = type == DEVICE_PHONE
        activity.findViewById<MaterialCheckBox>(R.id.cbDeviceTablet).isChecked = type == DEVICE_TABLET
        updatingDeviceUi = false
        try {
            activity.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(DEVICE_KEY, type).apply()
        } catch (e: Exception) {
        }
        applyVisibility()
    }

    private fun onHostSystemChanged(host: Int) {
        val name = when (host) {
            3 -> "HyperOS3"
            4 -> "HyperOS4"
            else -> null
        }
        if (name == null) {
            Toast.makeText(activity, "已清除宿主系统选择", Toast.LENGTH_SHORT).show()
            return
        }
        if (isModuleActive()) {
            Toast.makeText(activity, "重启「壁纸」APP 后生效", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, "宿主系统已选择 $name，设置壁纸时立即生效", Toast.LENGTH_SHORT).show()
        }
    }
}
