# HyperOS 3 Scroll Wallpaper Setter

[**English**](#english) | [**中文**](#chinese)

<a name="english"></a>
## English

### Set scrolling wallpaper on your HyperOS 3 device!

In Xiaomi's HyperOS 3, the wallpaper setting logic has been moved from "Themes & Personalization" (`com.miui.thememanager`) to "Lock screen editor" (`com.miui.aod`). The scrolling feature has been removed from the UI and forcibly disabled at the software level.

### How It Works

HyperOS 3 forcibly disables scrolling when setting a normal wallpaper:

```java
if (SystemBuildUtil.isOs3AtLeast()) {
    SystemSettingUtils.setScrollWithScreen(0); // Force disable
}
```

This toggle corresponds to the setting `pref_key_wallpaper_screen_scrolled_span`:
- `1` → Enable scrolling
- `0` or `null` → Disable scrolling

This APP bypasses the official restrictions through the following methods:

1. **Standard API wallpaper setting** — Uses `WallpaperManager.setBitmap()` to write directly, bypassing theme store cropping.
2. **Shizuku command execution** — After authorization, runs `settings put secure pref_key_wallpaper_screen_scrolled_span 1` to enable scrolling without root.
3. **Direct write (experimental)** — Attempts to write via MIUI wallpaper ContentProvider to bypass system restrictions.

### Significance

**HyperOS 3 still supports the scrolling feature** — this APP bypasses the official wallpaper setting pathway, directly sets the wallpaper, and enables scrolling.

### Requirements

- Shizuku enabled.
- Xiaomi HyperOS 3 (Android 15+).

### Usage

1. Launch Shizuku, install the APP, and grant Shizuku permissions.
2. Select an image from the gallery.
3. Tap "Set wallpaper via standard method".
4. Tap "Enable scrolling" to automatically execute the command.

> If your device has root access, it is recommended to use WallpaperScrollFix (https://github.com/BlizzardAn225/WallpaperScrollFix) instead.

### Build from Source

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# Open and build in Android Studio
```

### Compatibility

| Device | System Version | Status |
|--------|----------------|--------|
| Xiaomi Pad 8 Pro | HyperOS 3 | ✅ Tested |
| Other HyperOS 3 devices | HyperOS 3 | Theoretically works |

### License

[GPL-3.0](LICENSE)

### Disclaimer

1. Since the official feature is no longer adapted, bugs may occur, such as frame drops, rendering errors, or a brief black screen when entering the home screen from the lock screen.

---

<a name="chinese"></a>
## 中文

### 让你的澎湃OS3设备重新设置随屏滚动的壁纸！

在小米澎湃OS3（HyperOS 3）中，壁纸设置逻辑从“壁纸与个性化”（`com.miui.thememanager`）转移到了“锁屏编辑”（`com.miui.aod`），并从UI层面移除、软件层面强制关闭了随屏滚动功能。

### 工作原理

澎湃 OS3 在设置普通壁纸时会强制关闭随屏滚动：

```java
if (SystemBuildUtil.isOs3AtLeast()) {
    SystemSettingUtils.setScrollWithScreen(0); // 强制禁用
}
```

该开关即为设置项 `pref_key_wallpaper_screen_scrolled_span`：
- `1` → 启用随屏滚动
- `0` 或 `null` → 禁用随屏滚动

本 APP 绕过官方限制的方式：
1. **标准 API 设置壁纸** — 使用 `WallpaperManager.setBitmap()` 直接写入，不经过主题商店裁剪；
2. **Shizuku 执行命令** — 授权后通过 `settings put secure pref_key_wallpaper_screen_scrolled_span 1` 直接开启随屏滚动，无需 Root 权限；
3. **直接写入（实验性）** — 尝试通过 MIUI 壁纸 ContentProvider 写入，绕过系统限制。
   
### 意义

**澎湃OS3 仍然支持随屏滚动功能**，本APP绕过官方设置壁纸的途径，直接设置壁纸并启用随屏滚动。

### 使用要求

- 启用Shizuku；
- 小米澎湃OS3（Android 15+）。

### 使用说明

1. 启动 Shizuku，安装 APP，授予 Shizuku 权限；
2. 从相册选择图片；
3. 点击「标准方式设置壁纸」；
4. 点击「开启随屏滚动」自动执行命令。
>若设备拥有root权限，建议使用WallpaperScrollFix (https://github.com/BlizzardAn225/WallpaperScrollFix)。

### 从源码构建

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# 在 Android Studio 中打开并构建
```

### 兼容性

| 设备 | 系统版本 | 状态 |
|------|----------|------|
| 小米平板8 Pro | 澎湃OS3 | ✅ 已测试 |
| 其他澎湃OS3设备 | 澎湃OS3 | 理论上可行 |

### 许可证

[GPL-3.0](LICENSE)

### 免责声明

1. 由于官方已不适配随屏滚动，所以使用时可能出现bug，如掉帧、渲染错误、锁屏进入桌面时黑屏一下等问题。

---
