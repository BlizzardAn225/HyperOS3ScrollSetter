# HyperOS 3 Scroll Setter

[**English**](#english) | [**中文**](#chinese)

<a name="english"></a>
## English

### Make Your HyperOS 3 Device Support Scrolling Wallpaper Again!

In Xiaomi HyperOS 3, the wallpaper setting logic has been moved from "Wallpaper & Personalization" (`com.miui.thememanager`) to "Lock Screen Edit" (`com.miui.aod`). The scrolling wallpaper feature has been removed from the UI and forcibly disabled at the software level.

### Issue Review

#### Why Can't HyperOS 3 Set Scrolling Wallpaper?

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

#### Why is simply enabling the wallpaper scrolling switch not enough?

When applying a wallpaper, even without scaling, the "Lock screen editor" crops the wallpaper. The cropped bitmap is small, and simply setting `pref_key_wallpaper_screen_scrolled_span` to 1 results in only a small scrollable area. Moreover, in OS2, when the home screen wallpaper and lock screen wallpaper are the same, the lock screen wallpaper follows the scrolling position of the home screen wallpaper (e.g., if the home screen is swiped to the far right, the lock screen also displays the far-right area of the wallpaper). This behavior is broken in OS3 for two reasons:

1. **At the wallpaper app level**: The "Lock screen editor" in OS3 always sets both the home screen and lock screen wallpapers simultaneously; it cannot set the same wallpaper separately.
2. **At the Framework level**: `MiuiKeyguardWallpaperWindowController` calls `setDesktopWallpaperShowWhenLocked(false)`, preventing the home screen wallpaper Surface from being displayed on the lock screen. Even if the wallpaper app detects that the home screen and lock screen use the same image (`isSameImageWallpaper() = true`) and sets the lock screen Surface alpha to 0 (transparent), the home screen Surface is still hidden by the Framework, resulting in a black lock screen background.

### How It Works

#### Enabling home screen wallpaper scrolling

**No Root + Shizuku bypasses official restrictions:**

1. **Standard API wallpaper setting** — Uses `WallpaperManager.setBitmap()` to write directly, bypassing the "Lock screen editor" cropping.
2. **Shizuku command execution** — After authorization, use Shizuku to repeatedly execute `settings put secure pref_key_wallpaper_screen_scrolled_span 1` and kill the `com.miui.wallpaper` process. The loop stops 3 seconds after the process is terminated, enabling scrolling wallpaper without root access.

**Root:**

1. **Hook `isOs3AtLeast()`** → Return `false`: Prevents all forced disabling triggered by OS3 checks.
2. **Hook `checkWallpaperScroll()`** → Return directly: Prevents the reset logic when wallpaper changes.
3. **ContentObserver monitoring**: Registers a listener when the wallpaper app starts. Once a non-`1` value is detected, it immediately attempts to fix and write back. Only sends a notification if the fix fails.
4. **Hook bitmap size calculation functions** (`oc.q`, `oc.n`, `n5r1`, `l`, `lrht`, `gyi`): Force the system to use "scrollable" mode (`isScrollable = true`) when calculating target wallpaper dimensions, ensuring full size is used instead of screen size before cropping.
5. **Hook `ni7` as a fallback**: If secondary processing exists, force its `isScrollableWallpaper` parameter to `true`.
6. **Hook `TemplateApiImpl.cropBitmap()` and `TemplateApiImpl.s()`**: Target AOD and Theme processes respectively, directly skipping bitmap cropping before writing and returning the original full-resolution bitmap, preventing the wallpaper engine from only displaying a cropped small area.

#### Enabling lock screen wallpaper to follow home screen scrolling

**No Root + Shizuku:**

1. **`WallpaperManager.setBitmap()` sets both simultaneously** — After checking "Apply lock screen wallpaper simultaneously", use `FLAG_SYSTEM | FLAG_LOCK` to write the same cropped bitmap to both home screen and lock screen, bypassing the "Lock screen editor" cropping and simultaneous setting logic.
2. The wallpaper app detects that the home screen and lock screen use the same image → hides the lock screen Surface (alpha=0) → the home screen Surface is wider than the screen due to scroll=1 → the Framework tracks the scroll position → the lock screen directly displays the current scroll position of the home screen Surface.

**Root:**

1. **Hook `setDesktopWallpaperShowWhenLocked()`** — Force the parameter to `true`: When `MiuiKeyguardWallpaperWindowController` calls `setDesktopWallpaperShowWhenLocked(false)`, the hook changes it to `true`, ensuring the home screen wallpaper Surface is always visible on the lock screen. Combined with the wallpaper app's own same-image detection mechanism (`isSameImageWallpaper()` → `hideKeyguardWallpaper()` → keyguard alpha=0), the lock screen directly displays the home screen Surface with the scroll offset.
2. The remaining hooks (`isOs3AtLeast`, `checkWallpaperScroll`, size calculation, and cropping interception) are shared with the home screen scrolling implementation above, ensuring scroll=1 is not reset and wallpapers are not cropped.

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
    > If you experience a brief black screen on the home screen after rebooting, manually force-stop the "Wallpaper" app once in Settings.

### Building from Source

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# Open and build in Android Studio
```

### Compatibility

All devices running on HyperOS 3.

| Root/Shizuku | Devices | Status |
| :--- | :--- | :--- |
| **Root** | Xiaomi 17 Ultra, Xiaomi 17 Pro, Xiaomi 15 Pro, Xiaomi Pad 8 Pro | ✅ Tested |
| **Shizuku** | Xiaomi 17 Ultra, Xiaomi Pad 8 Pro, Redmi K Pad | ✅ Tested |
| **Root** | Other Xiaomi devices (HyperOS 3) | Theoretically works |
| **Shizuku** | Other Xiaomi devices (HyperOS 3) | Theoretically works, but scrolling needs to be re-enabled after reboot |

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

### 问题检查

#### 为什么澎湃OS3无法设置随屏滚动？

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

#### 仅仅打开随屏滚动开关为什么不行？

在应用壁纸时，即使没有缩放壁纸，“锁屏编辑”也会将壁纸进行裁切，裁切后的位图较小，仅仅将 `pref_key_wallpaper_screen_scrolled_span` 设置为1，会导致滚动区域只有一小块。此外，在 OS2 中，若桌面壁纸和锁屏壁纸相同，锁屏壁纸会跟随桌面壁纸的滚动位置（例如桌面划到最右侧，锁屏也显示壁纸最右侧区域）。OS3 中此行为失效，原因有两个：

1. **壁纸应用层面**：OS3 的"锁屏编辑"总是同时设置桌面和锁屏壁纸，无法单独设置相同壁纸；
2. **Framework 层面**：`MiuiKeyguardWallpaperWindowController` 调用 `setDesktopWallpaperShowWhenLocked(false)`，阻止桌面壁纸的 Surface 在锁屏上显示。即使壁纸应用检测到桌面和锁屏使用相同图片（`isSameImageWallpaper() = true`），并将锁屏 Surface 的 alpha 设为 0（透明），桌面 Surface 仍然被 Framework 隐藏，锁屏显示为黑色背景。

### 工作原理

#### 开启桌面壁纸随屏滚动

免Root+Shizuku绕过官方限制：
1. 标准 API 设置壁纸 — 使用 `WallpaperManager.setBitmap()` 直接写入，不经过“锁屏编辑”裁剪；
2. **Shizuku 执行命令** — 授权后通过Shizuku循环执行 `settings put secure pref_key_wallpaper_screen_scrolled_span 1` 并结束进程 `com.miui.wallpaper` ，结束进程后3秒停止循环写入命令，直接开启随屏滚动，无需 Root 权限。

Root：
1. **Hook `isOs3AtLeast()`** → 返回 `false`：阻止所有因 OS3 判断而触发的强制关闭；
2. **Hook `checkWallpaperScroll()`** → 直接返回：阻止壁纸变更时的重置逻辑；
3. **ContentObserver 监听**：在壁纸APP启动时注册监听器，一旦发现值变为非 `1`，立即尝试修复写回。修复失败才发送通知;
4. **Hook 位图尺寸计算函数**（`oc.q`、`oc.n`、`n5r1`、`l`、`lrht`、`gyi`）：强制使系统在计算壁纸目标尺寸时使用“可滚动”模式（`isScrollable = true`），从而在裁切位图之前就决定采用全尺寸而非屏幕尺寸。
5. **Hook `ni7` 作为兜底**：若存在二次处理，强制其 `isScrollableWallpaper` 参数为 `true`。
6. **Hook `TemplateApiImpl.cropBitmap()` 和 `TemplateApiImpl.s()`**：分别针对 AOD 和 Theme 进程，直接跳过写入前的位图裁切，返回原始全分辨率位图，避免壁纸引擎只能显示被裁切后的小块区域。

#### 开启锁屏壁纸随桌面滚动

**免Root + Shizuku：**
1. **`WallpaperManager.setBitmap()` 同时设置** — 勾选"同时应用锁屏壁纸"后，使用 `FLAG_SYSTEM | FLAG_LOCK` 将同一张裁切后的位图同时写入桌面和锁屏，绕过"锁屏编辑"的裁切和同时设置逻辑；
2. 壁纸应用检测到桌面和锁屏使用相同图片 → 隐藏锁屏 Surface（alpha=0）→ 桌面 Surface 因 scroll=1 而宽于屏幕 → Framework 跟踪滚动位置 → 锁屏直接显示桌面 Surface 的当前滚动位置。

**Root：**
1. **Hook `setDesktopWallpaperShowWhenLocked()`** — 强制参数为 `true`：当 `MiuiKeyguardWallpaperWindowController` 调用 `setDesktopWallpaperShowWhenLocked(false)` 时，hook 将其改为 `true`，确保桌面壁纸的 Surface 始终在锁屏上可见。配合壁纸应用自身的同图检测机制（`isSameImageWallpaper()` → `hideKeyguardWallpaper()` → keyguard alpha=0），锁屏直接显示带有滚动偏移的桌面 Surface；
2. 其余 hook（`isOs3AtLeast`、`checkWallpaperScroll`、尺寸计算、裁切拦截）与上述桌面随屏滚动共用，确保 scroll=1 不被重置、壁纸不被裁切。


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
    >若重启后进入桌面时，桌面壁纸出现短暂黑屏，请在设置中手动结束一次“壁纸”APP的进程。

### 从源码构建

```bash
git clone https://github.com/BlizzardAn225/HyperOS3ScrollSetter.git
cd HyperOS3-Scroll-Setter
# 在 Android Studio 中打开并构建
```

### 兼容性

以下均为澎湃OS3设备

| Root/Shizuku | 设备 | 状态 |
| :--- | :--- | :--- |
| Root | 小米17 Ultra, 小米17 Pro, 小米15 Pro, 小米平板8 Pro | ✅ 已测试 |
| Shizuku | 小米17 Ultra, 小米平板8 Pro, 红米K Pad | ✅ 已测试 |
| Root | 其他小米设备 (澎湃OS3) | 理论上可行 |
| Shizuku | 其他小米设备 (澎湃OS3) | 理论上可行，重启后需重新开启随屏滚动 |

### 许可证

[GPL-3.0](LICENSE)

### 免责声明

由于官方已不适配随屏滚动，所以使用时可能出现bug，如掉帧、渲染错误、锁屏进入桌面时黑屏一下等问题。

---
