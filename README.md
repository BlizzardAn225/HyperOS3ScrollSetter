# HyperOS 3/4 Scroll Setter

[**中文**](#chinese) | [**English**](#english)

<a name="chinese"></a>

# 澎湃OS 3/4 随屏滚动壁纸设置器

### 让澎湃OS 3/4 重新支持随屏滚动壁纸

澎湃OS 3 从官方壁纸设置界面中移除了随屏滚动功能，并在软件层面强制关闭普通壁纸的随屏滚动。

**澎湃OS 3/4 随屏滚动壁纸设置器**通过绕过这些限制，让小米澎湃OS设备重新使用随屏滚动壁纸。

### 功能

- 重新启用澎湃OS 3/4 随屏滚动壁纸
- 绕过「锁屏编辑」的壁纸裁切
- 支持桌面与锁屏壁纸同步滚动
- 禁止/对抗系统自动压暗壁纸
- 支持 **Root** 和 **Shizuku** 两种模式
- 针对澎湃OS 3、澎湃OS 4 分别进行适配

### 使用方法

#### Shizuku / 免 Root

1. 启动 Shizuku，并授予本应用所需权限；
2. 从相册选择图片；
3. 设置壁纸；
4. 在应用中开启「随屏滚动」。

澎湃OS 4 平板需要在「宿主设备」中选择正确的设备类型和屏幕分辨率。适用设备可以使用 `3200×2136` 预设值。

#### Root

1. 激活模块；
2. 重启设备；
3. 使用本应用或系统原有方式设置桌面/锁屏壁纸；
4. 开启「不压暗壁纸」后，需要重新启动一次「壁纸」APP。
   > 如果重启设备后进入桌面时壁纸短暂黑屏，可以在系统设置中手动结束一次「壁纸」APP进程。

### 兼容性

| 模式 | 设备 | 状态 |
| :--- | :--- | :--- |
| **Root** | 小米 17 Ultra、小米 17 Pro、小米 15 Pro、小米平板 8 Pro | ✅ 已测试 |
| **Shizuku** | 小米 17 Ultra、小米平板 8 Pro、Redmi K Pad | ✅ 已测试 |
| **Root** | 其他澎湃OS 3/4 小米设备 | 理论上可行 |
| **Shizuku** | 其他澎湃OS 3/4 小米设备 | 理论上可行，重启后可能需要重新开启随屏滚动 |

其他澎湃OS 3/4 设备也可能正常工作，但实际兼容性取决于具体系统版本及壁纸引擎实现。

### 从源码构建

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3ScrollSetter
# 在 Android Studio 中打开并构建
```

### 许可证

[GPL-3.0](LICENSE)

### 免责声明

随屏滚动壁纸已经不再受到澎湃OS官方支持，使用过程中可能出现：

- 掉帧
- 渲染错误
- 从锁屏进入桌面时短暂黑屏

使用本项目产生的任何问题由用户自行承担。


---

<a name="english"></a>

### Make HyperOS 3/4 Support Scrolling Wallpapers Again

HyperOS 3 removed scrolling wallpaper from the standard wallpaper-setting UI and added software-level restrictions that disable it for normal wallpapers.

**HyperOS 3/4 Scroll Setter** bypasses these restrictions and allows Xiaomi HyperOS devices to use scrolling wallpapers again.

### Features

- Enable scrolling wallpapers on HyperOS 3/4
- Set wallpapers directly without the cropping performed by the Lock Screen Editor
- Support synchronized home-screen and lock-screen scrolling
- Disable/Convert automatic wallpaper darkening
- Support **Root** and **Shizuku** modes
- HyperOS 3 and HyperOS 4 specific adaptations

### Usage

#### Shizuku / No Root

1. Start Shizuku and grant the app the required permission;
2. Select an image from the gallery;
3. Set the wallpaper;
4. Enable **Scrolling Wallpaper** in the app.

For HyperOS 4 tablets, configure the correct device type and screen resolution in the **Host Device** section. The 3200×2136 preset can be used where applicable.

#### Root

1. Activate the module;
2. Root the device;
3. Set the home-screen and/or lock-screen wallpaper through the app, or use the original system method;
4. Restart the **Wallpaper** app after enabling **Don't Darken Wallpaper**.
   > If the home screen briefly becomes black after rebooting device, force-stop the **Wallpaper** app once from Settings.

### Compatibility

| Mode | Device | Status |
| :--- | :--- | :--- |
| **Root** | Xiaomi 17 Ultra, Xiaomi 17 Pro, Xiaomi 15 Pro, Xiaomi Pad 8 Pro | ✅ Tested |
| **Shizuku** | Xiaomi 17 Ultra, Xiaomi Pad 8 Pro, Redmi K Pad | ✅ Tested |
| **Root** | Other Xiaomi devices running HyperOS 3/4 | Theoretically supported |
| **Shizuku** | Other Xiaomi devices running HyperOS 3/4 | Theoretically supported; scrolling may need to be re-enabled after reboot |

Other HyperOS 3/4 devices may also work, but compatibility depends on the system wallpaper implementation and version.

### Building from Source

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# Open the project in Android Studio and build
```

### License

[GPL-3.0](LICENSE)

### Disclaimer

Scrolling wallpapers are no longer officially supported by HyperOS. Unexpected behavior may occur, including:

- Frame drops
- Rendering errors
- Temporary black screens when entering the desktop from the lock screen

Use the module at your own risk.