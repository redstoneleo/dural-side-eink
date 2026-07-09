package com.example.myapp

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class ShellService : IShellService.Stub() {
    companion object {
        private const val TAG = "ShellService"
        private const val MAX_OUTPUT_CHARS = 500_000

        // @hide flag 数值，shell 进程天然有权使用
        private const val FLAG_PUBLIC                          = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY               = 1 shl 3
        private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val FLAG_TRUSTED                        = 1 shl 10
    }

    // 持有虚拟显示器引用，防止被 GC
    private var virtualDisplay: VirtualDisplay? = null

    init {
        Log.d(TAG, "ShellService started, UID: ${Process.myUid()}")
    }

    override fun runCommand(cmd: String): String {
        Log.d(TAG, "Executing: $cmd")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))

            val stdInput = BufferedReader(InputStreamReader(process.inputStream))
            val stdError = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (stdInput.readLine().also { line = it } != null) output.append(line).append("\n")

            val error = StringBuilder()
            while (stdError.readLine().also { line = it } != null) error.append(line).append("\n")

            stdInput.close()
            stdError.close()
            process.waitFor()
            val exitCode = process.exitValue()

            val outStr = output.toString().trim()
            val errStr = error.toString().trim()
            val safeOutput = if (outStr.length > MAX_OUTPUT_CHARS) outStr.take(MAX_OUTPUT_CHARS) else outStr

            if (exitCode != 0) {
                Log.e(TAG, "Command failed (exit $exitCode): $cmd")
                if (errStr.isNotEmpty()) Log.e(TAG, "stderr: $errStr")
                "ERROR_EXIT_$exitCode: $errStr"
            } else if (errStr.isNotEmpty()) {
                Log.w(TAG, "Command succeeded with stderr: $errStr")
                if (safeOutput.isEmpty()) "ERROR_STDERR: $errStr" else safeOutput
            } else {
                if (safeOutput.isEmpty()) "OK" else safeOutput
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing: $cmd", e)
            "EXCEPTION: ${e.message}"
        }
    }

    /**
     * 在 shell 进程（UID=2000）中创建带 TRUSTED 标志的虚拟显示器。
     *
     * shell 天然拥有 ADD_TRUSTED_DISPLAY 权限，因此：
     *   - 不需要 pm grant
     *   - 不需要重启 App
     *   - TRUSTED + SHOULD_SHOW_SYSTEM_DECORATIONS 立即生效
     *
     * @return displayId，失败返回 -1
     */
    override fun createTrustedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        surface: Surface
    ): Int {
        Log.d(TAG, "createTrustedVirtualDisplay: ${width}x${height} @ ${dpi}dpi, flags=0x${flags.toString(16)}, UID=${Process.myUid()}")
        return try {
            // 通过 Context 获取 DisplayManager
            // ShellService 运行在 App 的 UserService 进程中，可以直接使用 Android API
            val context = getApplicationContext() ?: run {
                Log.e(TAG, "Cannot get application context")
                return -1
            }

            val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as DisplayManager

            Log.d(TAG, "Creating VirtualDisplay with flags=0x${flags.toString(16)}, UID=${Process.myUid()}")

            val vd = displayManager.createVirtualDisplay(name, width, height, dpi, surface, flags)
            if (vd != null) {
                virtualDisplay = vd
                val displayId = vd.display.displayId
                Log.d(TAG, "VirtualDisplay created: id=$displayId")
                displayId
            } else {
                Log.e(TAG, "createVirtualDisplay returned null")
                -1
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in createTrustedVirtualDisplay: ${e.message}")
            -1
        } catch (e: Exception) {
            Log.e(TAG, "Exception in createTrustedVirtualDisplay", e)
            -1
        }
    }

    private fun getApplicationContext(): android.content.Context? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getMethod("currentApplication")
            method.invoke(null) as? android.content.Context
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get application context by reflection", e)
            null
        }
    }

    override fun destroy() {
        Log.d(TAG, "ShellService destroying")
        virtualDisplay?.release()
        virtualDisplay = null
        exitProcess(0)
    }
}
