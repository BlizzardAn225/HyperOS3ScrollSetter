package com.blizzard225.wallpapersetter

object ModuleStatus {
    // 模块未激活时默认 false
    // 模块激活后，LSPosed 会在 App 进程启动时将其设为 true
    @JvmField
    var isActive: Boolean = false
}
