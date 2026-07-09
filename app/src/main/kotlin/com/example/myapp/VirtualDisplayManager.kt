package com.example.myapp

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.util.Log

/**
 * 虚拟显示器管理器
 *
 * 创建用于运行第三方应用任务的虚拟显示器。
 * 配合 Shizuku 的 am move-task 实现跨屏迁移。
 *
 * ── Flag 说明（来自 AOSP DisplayManager.java 源码）──────────────────────────
 *  FLAG_PUBLIC                         (1<<0)  公开 SDK
 *      允许其他 App 在此显示器上显示窗口，am move-task 必须有此 flag。
 *
 *  FLAG_OWN_CONTENT_ONLY               (1<<3)  公开 SDK
 *      只显示自己的内容，不镜像主屏，绕过 ADD_MIRROR_DISPLAY 权限检查。
 *
 *  FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (1<<9)  @TestApi @hide
 *      让虚拟显示器拥有状态栏、导航栏、桌面壁纸。
 *      ⚠️  AOSP 注释原文："This flag doesn't work without VIRTUAL_DISPLAY_FLAG_TRUSTED"
 *
 *  FLAG_TRUSTED                        (1<<10) @SystemApi @hide
 *      SHOULD_SHOW_SYSTEM_DECORATIONS 的前提条件。
 *      需要通过 ADB 一次性授权：
 *          adb shell pm grant <packageName> android.permission.ADD_TRUSTED_DISPLAY
 * ────────────────────────────────────────────────────────────────────────────
 */
object VirtualDisplayManager {
    private const val TAG = "VirtualDisplayManager"

    // 手动定义 @hide flag 数值（AOSP 源码中的定义）
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9   // 0x200
    private const val FLAG_TRUSTED                        = 1 shl 10  // 0x400

    private var currentDisplay: VirtualDisplay? = null

    /** 当前虚拟显示器 ID，-1 表示未创建 */
    var currentDisplayId: Int = -1
        private set

    /**
     * 创建虚拟显示器（App 进程，无系统装饰降级版）。
     * 仅在 ShellService 不可用时作为兜底。
     */
    fun createPublicVirtualDisplay(
        context: Context,
        surface: Surface,
        width: Int = 540,
        height: Int = 960,
        dpi: Int = 160
    ): Int {
        release()
        val fallbackFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        return tryCreate(context, surface, width, height, dpi, fallbackFlags, "fallback (no system decorations)")
    }

    /**
     * 由 ShellService 创建完成后，在 App 进程中登记 displayId。
     * 虚拟显示器实际由 shell 进程持有，App 只记录 ID 用于后续操作。
     */
    fun registerExternalDisplay(displayId: Int) {
        // 虚拟显示器由 ShellService 持有，这里只记录 ID
        currentDisplayId = displayId
        Log.d(TAG, "Registered external virtual display: id=$displayId")
    }

    private fun tryCreate(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        label: String
    ): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return try {
            Log.d(TAG, "Creating virtual display [$label]: ${width}x${height} @ ${dpi}dpi, flags=0x${flags.toString(16)}")
            val vd = displayManager.createVirtualDisplay("InkScreenDisplay", width, height, dpi, surface, flags)
            if (vd != null) {
                currentDisplay = vd
                currentDisplayId = vd.display.displayId
                Log.d(TAG, "Virtual display created [$label]: id=$currentDisplayId")
                currentDisplayId
            } else {
                Log.e(TAG, "createVirtualDisplay returned null [$label]")
                -1
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException [$label]: ${e.message}")
            -1
        } catch (e: Exception) {
            Log.e(TAG, "Exception [$label]", e)
            -1
        }
    }

    /**
     * 释放当前虚拟显示器
     */
    fun release() {
        currentDisplay?.release()
        currentDisplay = null
        currentDisplayId = -1
        Log.d(TAG, "Virtual display released")
    }
}
