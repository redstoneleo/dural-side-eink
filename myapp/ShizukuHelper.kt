package com.example.myapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 辅助类 — 纯 Shell 命令梯队方案
 *
 * 通过 Shizuku UserService 在高权限进程（UID 0/2000）中执行 shell 命令。
 * 零反射、零隐藏 API，100% 应用商店合规。
 *
 * 命令梯队：从现代到古老，自动降级，兼容 Android 10 ~ 16+
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    private const val BIND_TIMEOUT_MS = 10_000L

    private var shellService: IShellService? = null
    private var appContext: Context? = null
    @Volatile
    private var isBinderReceived = false
    @Volatile
    private var isBinding = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        isBinderReceived = true
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        isBinderReceived = false
        shellService = null
    }

    private var bindLatch: CountDownLatch? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "ShellService connected")
            shellService = IShellService.Stub.asInterface(binder)
            isBinding = false
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ShellService disconnected")
            resetShellService()
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "ShellService binding died")
            resetShellService()
        }
    }

    private fun resetShellService() {
        shellService = null
        isBinding = false
        bindLatch?.countDown()
        bindLatch = null
    }

    /**
     * 获取 ShellService（阻塞，最多等待 BIND_TIMEOUT_MS）
     * 必须在后台线程调用！
     */
    private fun getServiceBlocking(): IShellService? {
        shellService?.let { return it }

        val context = appContext ?: return null

        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku binder not available")
            return null
        }

        // 如果正在绑定中，等待
        if (isBinding && bindLatch != null) {
            Log.d(TAG, "Waiting for ongoing bind...")
            bindLatch?.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return shellService
        }

        // 发起绑定
        isBinding = true
        val latch = CountDownLatch(1)
        bindLatch = latch

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell_service")
            .tag("shell_service")

        try {
            Log.d(TAG, "Binding to ShellService...")
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Binding failed", e)
            isBinding = false
            return null
        }

        val connected = latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!connected) {
            Log.e(TAG, "ShellService bind timeout after ${BIND_TIMEOUT_MS}ms")
            resetShellService()
            return null
        }
        if (shellService == null) {
            Log.e(TAG, "ShellService bind completed but service is null")
            resetShellService()
            return null
        }
        Log.d(TAG, "ShellService ready")
        return shellService
    }

    /**
     * 在后台线程执行阻塞操作，结果通过回调返回主线程
     */
    private fun <T> runOnBackground(
        default: T,
        mainCallback: ((T) -> Unit)?,
        block: () -> T
    ) {
        if (mainCallback != null) {
            Thread {
                val result = block()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    mainCallback(result)
                }
            }.start()
        } else {
            block()
        }
    }

    fun checkShizukuStatus(context: Context): String {
        if (appContext == null) {
            appContext = context.applicationContext
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }

        try {
            if (!Shizuku.pingBinder()) {
                return "Shizuku 服务未运行，请确认 Shizuku 已启动"
            }
            val status = Shizuku.checkSelfPermission()
            if (status != PackageManager.PERMISSION_GRANTED) {
                try { Shizuku.requestPermission(1001) } catch (_: Exception) {}
                return "Shizuku 权限未授权，请在弹出的授权对话框中点击「允许」"
            }
            Log.d(TAG, "Shizuku status: OK")
            return "OK"
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku status", e)
            return "检查状态失败: ${e.message}"
        }
    }

    fun requestPermission(activity: Activity) {
        Shizuku.requestPermission(1001)
    }

    // ==================== Shell 命令执行 ====================

    /**
     * 通过 Shizuku UserService 执行 shell 命令
     * 替代已设为私有的 Shizuku.newProcess()，统一走 AIDL 高权限进程
     * 必须在后台线程调用！
     */
    private fun runShellCommand(cmd: String): String {
        val service = getServiceBlocking() ?: return "ERROR: ShellService not available"
        return try {
            service.runCommand(cmd)
        } catch (e: Exception) {
            Log.e(TAG, "ShellService call failed: $cmd", e)
            if (e is android.os.DeadObjectException) {
                resetShellService()
                val retryService = getServiceBlocking() ?: return "ERROR: ShellService not available"
                return try {
                    retryService.runCommand(cmd)
                } catch (e2: Exception) {
                    Log.e(TAG, "ShellService retry failed: $cmd", e2)
                    "EXCEPTION: ${e2.message}"
                }
            }
            "EXCEPTION: ${e.message}"
        }
    }

    // ==================== 权限授予 ====================

    /**
     * 通过 Shizuku (shell UID=2000) 授予 ADD_TRUSTED_DISPLAY 权限。
     *
     * 重要：pm grant 成功后权限不会在当前进程内立即生效，需要重启进程。
     * 因此本方法返回两种状态：
     *   - ALREADY_GRANTED：本次启动前就已有权限，可直接使用
     *   - NEWLY_GRANTED：本次刚刚授权，需要重启 App 才能生效
     *   - FAILED：授权失败
     */
    enum class TrustedDisplayGrantResult { ALREADY_GRANTED, NEWLY_GRANTED, FAILED }

    fun grantTrustedDisplayPermission(
        context: Context,
        onResult: (TrustedDisplayGrantResult) -> Unit
    ) {
        val pkg = context.packageName
        runOnBackground(TrustedDisplayGrantResult.FAILED, onResult) {
            // 用 Java API 检查当前进程内的权限状态（最准确）
            val alreadyGranted = context.checkSelfPermission(
                "android.permission.ADD_TRUSTED_DISPLAY"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (alreadyGranted) {
                Log.d(TAG, "ADD_TRUSTED_DISPLAY already granted (in-process check)")
                return@runOnBackground TrustedDisplayGrantResult.ALREADY_GRANTED
            }

            // 当前进程没有权限，通过 Shizuku 授予
            Log.d(TAG, "Granting ADD_TRUSTED_DISPLAY to $pkg via Shizuku")
            val result = runShellCommand("pm grant $pkg android.permission.ADD_TRUSTED_DISPLAY")
            val success = !result.startsWith("ERROR") && !result.startsWith("EXCEPTION")

            if (success) {
                Log.d(TAG, "ADD_TRUSTED_DISPLAY granted — app restart required to take effect")
                TrustedDisplayGrantResult.NEWLY_GRANTED
            } else {
                Log.e(TAG, "Failed to grant ADD_TRUSTED_DISPLAY: $result")
                TrustedDisplayGrantResult.FAILED
            }
        }
    }

    /**
     * 通过 Shizuku 重启 App 进程，使刚授予的权限生效。
     * 先 force-stop 杀掉当前进程，再用 am start 拉起 MainActivity。
     */
    fun restartApp(context: Context) {
        val pkg = context.packageName
        runOnBackground(Unit, null) {
            // 先 force-stop
            runShellCommand("am force-stop $pkg")
            Thread.sleep(600)
            // am start -n <pkg>/<activity>  （不带 --activity-new-task，shell 不支持该 flag）
            runShellCommand("am start -n $pkg/${pkg}.MainActivity")
        }
    }

    // ==================== 在 shell 进程中创建虚拟显示器 ====================

    /**
     * 在 ShellService（UID=2000/shell）进程中创建带 TRUSTED 标志的虚拟显示器。
     *
     * 这是解决权限问题的根本方案：
     *   - shell 进程天然拥有 ADD_TRUSTED_DISPLAY 权限
     *   - 不需要 pm grant，不需要重启 App
     *   - TRUSTED + SHOULD_SHOW_SYSTEM_DECORATIONS 立即生效
     *
     * @param surface  ImageReader.surface，用于捕获虚拟显示器画面
     * @param onResult 回调，返回 displayId（-1 表示失败）
     */
    fun createTrustedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        surface: android.view.Surface,
        onResult: (Int) -> Unit
    ) {
        runOnBackground(-1, onResult) {
            val service = getServiceBlocking()
            if (service == null) {
                Log.e(TAG, "ShellService not available for createTrustedVirtualDisplay")
                return@runOnBackground -1
            }
            try {
                var displayId = -1
                var retries = 3
                while (retries > 0) {
                    val service = getServiceBlocking() ?: break
                    try {
                        displayId = service.createTrustedVirtualDisplay(name, width, height, dpi, flags, surface)
                        Log.d(TAG, "createTrustedVirtualDisplay successful: displayId=$displayId")
                        break
                    } catch (_: android.os.DeadObjectException) {
                        Log.w(TAG, "DeadObjectException during createTrustedVirtualDisplay, retries left: ${retries - 1}")
                        resetShellService()
                        retries--
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during createTrustedVirtualDisplay", e)
                        break
                    }
                }
                displayId
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in createTrustedVirtualDisplay", e)
                -1
            }
        }
    }

    // ==================== 任务 ID 获取 ====================

    fun getForegroundTaskId(onResult: ((Int) -> Unit)? = null): Int {
        if (onResult != null) {
            runOnBackground(-1, onResult) { getForegroundTaskIdSync() }
            return -1
        }
        return getForegroundTaskIdSync()
    }

    private fun getForegroundTaskIdSync(): Int {
        // 统一通过 ShellService (UserService) 执行 dumpsys
        return try {
            // 命令梯队：从精简到完整
            val commandTiers = listOf(
                // 第一梯队：最精简，只输出当前前台任务（Android 12+）
                "dumpsys activity recents | head -30",
                // 第二梯队：输出顶部 Activity 信息（全版本通用）
                "dumpsys activity top | head -30",
                // 第三梯队：完整输出但限制行数（兜底）
                "dumpsys activity activities | head -100"
            )

            // 匹配策略：按优先级精确匹配
            val patterns = listOf(
                // Task{xxx #2228} — 全版本通用
                Regex("Task\\{[^}]*#(\\d+)"),
                // topResumedActivity=ActivityRecord{...t2228}
                Regex("topResumedActivity=.*?t(\\d+)"),
                // TASK xxx id=2228 — recents 格式
                Regex("taskId=(\\d+)"),
                // mFocusedRootTask=Task{xxx #2228}
                Regex("mFocusedRootTask=.*?#(\\d+)"),
                // mResumedActivity=ActivityRecord{...t2228}
                Regex("mResumedActivity=.*?t(\\d+)"),
                // 旧版: mFocusedStack=...taskId=xxx
                Regex("mFocusedStack=.*?taskId=(\\d+)")
            )

            for (cmd in commandTiers) {
                val output = runShellCommand(cmd)

                if (output.startsWith("ERROR") || output.startsWith("EXCEPTION")) {
                    Log.w(TAG, "Command '$cmd' failed: ${output.take(200)}")
                    continue
                }

                // 诊断日志：输出前 500 字符，方便排查格式问题
                Log.d(TAG, "Output from '$cmd' (${output.length} chars): ${output.take(500)}")

                for (pattern in patterns) {
                    val match = pattern.find(output)
                    if (match != null) {
                        val id = match.groupValues[1].toIntOrNull()
                        if (id != null && id > 0) {
                            Log.d(TAG, "Foreground task ID: $id (pattern: ${pattern.pattern}, cmd: $cmd)")
                            return id
                        }
                    }
                }

                // 兜底：找 visible=true 的 Task
                val visibleTaskPattern = Regex("Task\\{[^}]*#(\\d+)[^}]*visible=true")
                val firstVisible = visibleTaskPattern.find(output)
                if (firstVisible != null) {
                    val id = firstVisible.groupValues[1].toIntOrNull()
                    if (id != null && id > 0) {
                        Log.w(TAG, "Using first visible task ID: $id (cmd: $cmd)")
                        return id
                    }
                }

                Log.d(TAG, "No match in output from '$cmd', trying next command...")
            }

            Log.e(TAG, "Could not determine foreground task ID with any command")
            -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get foreground task ID", e)
            -1
        }
    }

    // ==================== 任务迁移 ====================

    fun moveTaskToDisplay(taskId: Int, displayId: Int, onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (onResult != null) {
            runOnBackground(false, onResult) { moveTaskToDisplaySync(taskId, displayId) }
            return false
        }
        return moveTaskToDisplaySync(taskId, displayId)
    }

    private fun findStackIdForDisplay(displayId: Int): Int {
        val output = runShellCommand("cmd activity stack list")
        if (output.startsWith("ERROR") || output.startsWith("EXCEPTION")) {
            Log.e(TAG, "Failed to list stacks for display lookup: $output")
            return -1
        }
        val regex = Regex("RootTask id=(\\d+).*?displayId=$displayId", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(output)
        val stackId = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (stackId == -1) {
            Log.w(TAG, "No root stack found for display $displayId")
        } else {
            Log.d(TAG, "Found target stack $stackId for display $displayId")
        }
        return stackId
    }

    /**
     * 三级命令梯队，兼容 Android 10 ~ 16+
     * 通过 ShellService (UserService) 执行 shell 命令
     */
    private fun moveTaskToDisplaySync(taskId: Int, displayId: Int): Boolean {
        return try {
            Log.d(TAG, "Moving task $taskId to display $displayId")

            val commandTiers = mutableListOf<String>()
            val targetStackId = findStackIdForDisplay(displayId)
            if (targetStackId != -1) {
                commandTiers.add("cmd activity task move-task $taskId $targetStackId true")
            }
            commandTiers.add("am stack movetodisplay $taskId $displayId")
            commandTiers.add("am stack move-task $taskId $displayId")

            for ((index, cmd) in commandTiers.withIndex()) {
                Log.d(TAG, "Tier ${index + 1}: $cmd")
                val result = runShellCommand(cmd)

                if (result.startsWith("ERROR") || result.startsWith("EXCEPTION")) {
                    Log.w(TAG, "Tier ${index + 1} failed: ${result.take(200)}")
                    // 继续尝试下一级
                } else {
                    Log.d(TAG, "Success with tier ${index + 1}! Response: ${result.take(100)}")
                    return true
                }
            }

            Log.e(TAG, "All command tiers exhausted")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move task", e)
            false
        }
    }
}
