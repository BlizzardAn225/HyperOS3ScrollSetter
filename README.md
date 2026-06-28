# HyperOS 3 Scroll Setter

[**English**](#english) | [**中文**](#chinese)

<a name="english"></a>
## English

### Make Your HyperOS 3 Device Support Scrolling Wallpaper Again!

In Xiaomi HyperOS 3, the wallpaper setting logic has been moved from "Wallpaper & Personalization" (`com.miui.thememanager`) to "Lock Screen Edit" (`com.miui.aod`). The scrolling wallpaper feature has been removed from the UI and forcibly disabled at the software level.

### Why Can't HyperOS 3 Set Scrolling Wallpaper?

After analyzing the "Wallpaper & Personalization" app (`com.miui.thememanager`), "Wallpaper" app (`com.miui.miwallpaper`), and "Lock Screen Edit" app (`com.miui.aod`), we identified that the switch controlling scrolling wallpaper is `pref_key_wallpaper_screen_scrolled_span`:
- Value `1` → Enable scrolling wallpaper
- Value `0` or `null` → Disable scrolling wallpaper

Further inspection reveals the following logic in the "Wallpaper" app:

```java
if (i == 1) {
    if (MiuiWallpaperManager.MI_WALLPAPER_TYPE_SUPER_WALLPAPER.equals(str)) {
        SystemSettingUtils.setScrollWithScreen(1);
    } else if (SystemBuildUtil.isOs3AtLeast()) {
        SystemSettingUtils.setScrollWithScreen(0);
    }
}
```

That is, when setting a desktop wallpaper:
- **If it's a Super Wallpaper** → Enable scrolling
- **Otherwise if it's OS3** → **Forcibly disable scrolling**

There is also a reset mechanism:

```java
public static void checkWallpaperScroll(Context context) {
    // ...
    if (SystemBuildUtil.isOs3AtLeast()
        && SystemSettingUtils.isScrollWithScreen()) {
        Log.info("os3 disable scroll with screen");
        SystemSettingUtils.setScrollWithScreen(0);
    }
}
```
Reset condition: OS3 + normal wallpaper + scroll == 1 → automatically reset to 0

### Why Isn't Simply Turning On the Scrolling Switch Enough?

When applying a wallpaper, even without scaling, the "Lock Screen Edit" app will crop the wallpaper. The cropped bitmap is smaller, and simply setting `pref_key_wallpaper_screen_scrolled_span` to 1 will result in only a small area being scrollable.

### How It Works

**No Root + Shizuku bypass:**
1. **Standard API wallpaper setting** — Use `WallpaperManager.setBitmap()` to write directly, bypassing "Lock Screen Edit" cropping;
2. **Shizuku command execution** — After authorization, execute `settings put secure pref_key_wallpaper_screen_scrolled_span 1` to directly enable scrolling wallpaper without Root privileges.

**Root:**
1. **Hook `isOs3AtLeast()`** → Return `false`: Prevents all forced disabling triggered by OS3 checks;
2. **Hook `checkWallpaperScroll()`** → Return directly: Prevents reset logic when wallpaper changes;
3. **ContentObserver monitoring**: Register a listener when the Wallpaper app starts. Once a non-`1` value is detected, attempt to fix and write back immediately. Only send notification if the fix fails;
4. **Hook bitmap size calculation functions** (`oc.q`, `oc.n`, `n5r1`, `l`, `lrht`, `gyi`): Force the system to use "scrollable" mode (`isScrollable = true`) when calculating target wallpaper dimensions, ensuring the full size is used instead of screen size before cropping;
5. **Hook `ni7` as a fallback**: If secondary processing exists, force its `isScrollableWallpaper` parameter to `true`;
6. **Hook `TemplateApiImpl.cropBitmap()` and `TemplateApiImpl.s()`**: Target AOD and Theme processes respectively, directly skipping bitmap cropping before writing and returning the original full-resolution bitmap, preventing the wallpaper engine from only displaying a cropped small area.

### Significance

**HyperOS 3 still supports the scrolling wallpaper feature**. This app bypasses the official wallpaper setting pathway, directly setting the wallpaper and enabling scrolling. The original WallpaperScrollFix module intercepted all wallpaper cropping logic, making it impossible to directly set lock screen wallpaper scaling. Moreover, since the "Lock Screen Edit" app applies both desktop and lock screen wallpapers simultaneously, **disabling the module to set a lock screen wallpaper would overwrite the uncropped desktop wallpaper; re-setting the desktop wallpaper would overwrite the cropped lock screen wallpaper**. This forced users to manually crop in the gallery, which was too cumbersome. This app incorporates the interception functionality of the WallpaperScrollFix module and adds the ability to bypass the "Lock Screen Edit" app, directly crop the wallpaper, and pass parameters to "Wallpaper & Personalization" to set both desktop and lock screen wallpapers.

### Usage Instructions

**No Root:**
1. Start Shizuku, install the app, and grant Shizuku permissions;
2. Select an image from the gallery;
3. Set the wallpaper;
4. Click "Enable Scrolling Wallpaper" to automatically execute the command.

**Root:**
1. Activate the module;
2. Restart the "Wallpaper" app (if "Lock Screen Edit" or "Wallpaper & Personalization" is running, it is recommended to restart them as well);
3. Set lock screen/desktop wallpaper through the app, or use the original setting method.

### Building from Source

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# Open and build in Android Studio
```

### Compatibility

| Device             | System     | Root/Shizuku | Status                                         |
| ------------------ | ---------- | ------------ | ---------------------------------------------- |
| Xiaomi Pad 8 Pro   | HyperOS 3  | Root         | ✅ Tested                                       |
| Redmi K Pad        | HyperOS 3  | Shizuku      | ✅ Tested                                       |
| Other Xiaomi devices | HyperOS 3 | Root         | Theoretically feasible                         |
| Other Xiaomi tablets | HyperOS 3 | Shizuku      | Theoretically feasible, scrolling needs to be re-enabled after reboot |
| Other Xiaomi phones  | HyperOS 3 | Shizuku      | Difficult to implement                         |

**Important: Due to differences in wallpaper app logic between phones and tablets, this app (no Root, relying solely on Shizuku permissions) does NOT work on HyperOS 3 phones.**

### License

[GPL-3.0](LICENSE)

### Disclaimer

Since the official system no longer supports scrolling wallpaper, bugs may occur during use, such as frame drops, rendering errors, or a black screen when entering the desktop from the lock screen.

---

<a name="chinese"></a>
# 澎湃OS3壁纸设置器

## 中文

### 让你的澎湃OS3设备重新设置随屏滚动的壁纸！

在小米澎湃OS3（HyperOS 3）中，壁纸设置逻辑从“壁纸与个性化”（`com.miui.thememanager`）转移到了“锁屏编辑”（`com.miui.aod`），并从UI层面移除、软件层面强制关闭了随屏滚动功能。

### 为什么澎湃OS3无法设置随屏滚动？

. 经过对 “壁纸与个性化”APP （`com.miui.thememanager`）、“壁纸”APP（`com.miui.miwallpaper`）、“锁屏编辑”APP（`com.miui.aod`） 的分析，定位到控制随屏滚动的开关为 `pref_key_wallpaper_screen_scrolled_span`：  
   - 值为 `1` → 开启随屏滚动  
   - 值为 `0` 或 `null` → 关闭随屏滚动  

   继续检查发现，“壁纸”中存在以下逻辑：

```java
if (i == 1) {
    if (MiuiWallpaperManager.MI_WALLPAPER_TYPE_SUPER_WALLPAPER.equals(str)) {
        SystemSettingUtils.setScrollWithScreen(1);
    } else if (SystemBuildUtil.isOs3AtLeast()) {
        SystemSettingUtils.setScrollWithScreen(0);
    }
}
```

   即正在设置桌面壁纸时：  
   - **如果是超级壁纸** → 开启随屏滚动  
   - **否则如果是 OS3** → **强制关闭随屏滚动**  

   同时存在复位机制：
```java
   public static void checkWallpaperScroll(Context context) {
       // ...
       if (SystemBuildUtil.isOs3AtLeast()
           && SystemSettingUtils.isScrollWithScreen()) {
           Log.info("os3 disable scroll with screen");
           SystemSettingUtils.setScrollWithScreen(0);
       }
   }
```
   复位条件： OS3 + 普通壁纸 + scroll == 1 → 自动改回 0

### 仅仅打开随屏滚动开关为什么不行？

在应用壁纸时，即使没有缩放壁纸，“锁屏编辑”也会将壁纸进行裁切，裁切后的位图较小，仅仅将 `pref_key_wallpaper_screen_scrolled_span` 设置为1，会导致滚动区域只有一小块。

### 工作原理

免Root+Shizuku绕过官方限制：
1. 标准 API 设置壁纸 — 使用 `WallpaperManager.setBitmap()` 直接写入，不经过“锁屏编辑”裁剪；
2. **Shizuku 执行命令** — 授权后通过 `settings put secure pref_key_wallpaper_screen_scrolled_span 1` 直接开启随屏滚动，无需 Root 权限。

Root：
1. **Hook `isOs3AtLeast()`** → 返回 `false`：阻止所有因 OS3 判断而触发的强制关闭；
2. **Hook `checkWallpaperScroll()`** → 直接返回：阻止壁纸变更时的重置逻辑；
3. **ContentObserver 监听**：在壁纸APP启动时注册监听器，一旦发现值变为非 `1`，立即尝试修复写回。修复失败才发送通知;
4. **Hook 位图尺寸计算函数**（`oc.q`、`oc.n`、`n5r1`、`l`、`lrht`、`gyi`）：强制使系统在计算壁纸目标尺寸时使用“可滚动”模式（`isScrollable = true`），从而在裁切位图之前就决定采用全尺寸而非屏幕尺寸。
5. **Hook `ni7` 作为兜底**：若存在二次处理，强制其 `isScrollableWallpaper` 参数为 `true`。
6. **Hook `TemplateApiImpl.cropBitmap()` 和 `TemplateApiImpl.s()`**：分别针对 AOD 和 Theme 进程，直接跳过写入前的位图裁切，返回原始全分辨率位图，避免壁纸引擎只能显示被裁切后的小块区域。
   
### 意义

**澎湃OS3 仍然支持随屏滚动功能**，本APP绕过官方设置壁纸的途径，直接设置壁纸并启用随屏滚动。原本WallpaperScrollFix模块因拦截了所有壁纸裁剪逻辑，导致无法直接设置锁屏壁纸的缩放，且由于“锁屏编辑”设置壁纸的逻辑，是同时应用桌面壁纸和锁屏壁纸。**若关闭模块以设置锁屏壁纸，则将覆盖未裁切的桌面壁纸；若重新设置桌面壁纸，则会覆盖裁切后的锁屏壁纸**，导致用户需要在相册中手动裁切，过于麻烦。本软件合入了WallpaperScrollFix模块的拦截功能，并增加了绕过“锁屏编辑”APP，直接裁切壁纸，并向“壁纸与个性化”传参设置桌面壁纸和锁屏壁纸的功能。

### 使用说明

免Root：
1. 启动 Shizuku，安装 APP，授予 Shizuku 权限；
2. 从相册选择图片；
3. 设置壁纸；
4. 点击「开启随屏滚动」自动执行命令。

Root：
1. 激活模块；
2. 重启“壁纸”APP（若“锁屏编辑”APP、“壁纸与个性化”APP在运行，最好一并重启）；
3. 进入软件设置锁屏/桌面壁纸，或使用原本的设置方式。

### 从源码构建

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# 在 Android Studio 中打开并构建
```

### 兼容性

| 设备        | 系统版本  | Root/Shizuku | 状态                 |
| --------- | ----- | ------------ | ------------------ |
| 小米平板8 Pro | 澎湃OS3 | Root         | ✅ 已测试              |
| 红米K Pad   | 澎湃OS3 | Shizuku      | ✅ 已测试              |
| 其他小米设备    | 澎湃OS3 | Root         | 理论上可行              |
| 其他小米平板    | 澎湃OS3 | Shizuku      | 理论上可行，重启后需重新开启随屏滚动 |
| 其他小米手机    | 澎湃OS3 | Shizuku      | 难以实现               |

**重要：由于手机和平板的壁纸应用逻辑不同，免Root、本APP仅靠Shizuku权限工作时在澎湃OS3手机上无效。**

### 许可证

[GPL-3.0](LICENSE)

### 免责声明

由于官方已不适配随屏滚动，所以使用时可能出现bug，如掉帧、渲染错误、锁屏进入桌面时黑屏一下等问题。

---
