package com.example.myapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.example.myapp.databinding.ActivityMainBinding
import net.jpountz.lz4.LZ4Factory
import kotlin.math.sqrt

/**
 * MainActivity — 任务迁移模式
 *
 * 核心流程：
 * 1. 创建带系统 UI 的公开虚拟显示器
 * 2. 通过 Shizuku 将前台应用任务迁移到虚拟显示器
 * 3. ImageReader 捕获虚拟显示器画面 → 处理 → USB 发送到墨水屏
 * 4. 墨水屏触控事件注入回虚拟显示器
 *
 * 磁贴切换逻辑：
 * - 第一次点击：移至墨水屏 + 主屏 BlackHole 省电
 * - 第二次点击：移回主屏 + 关闭 BlackHole
 */
class MainActivity : AppCompatActivity() {

    companion object {
        var isProjectionActiveStatic = false
        var currentMirrorDisplayId = -1
        var activeTaskIdStatic = -1
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.example.myapp.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL_ID = "projection_toggle_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var binding: ActivityMainBinding

    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionDisplay: VirtualDisplay? = null
    private var isMediaProjectionCaptureActive = false
    private var lastCaptureSavedAt = 0L
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDpi = 0

    private val mediaProjectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                MediaProjectionService.start(this)
                mediaProjection = mediaProjectionManager?.getMediaProjection(result.resultCode, result.data!!)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        runOnUiThread { stopMediaProjectionCapture() }
                    }
                }, Handler(mainLooper))
                createMediaProjectionVirtualDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}")
                Toast.makeText(this, "初始化屏幕捕获失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "屏幕捕获权限未授权", Toast.LENGTH_SHORT).show()
        }
    }

    private var usbCdcManager: UsbCdcManager? = null

    private var isProjectionActive = false
    private var usbBuffer = StringBuilder()

    private var vdWidth = 0
    private var vdHeight = 0
    private var frameCount = 0

    private var isUsbConnected = false
    private var isVirtualDisplayReady = false
    private var isTaskReparented = false
    private var activeTaskId = -1
    private var inkScreenInfo = "未连接设备"
    private var hasShownAccessibilityPrompt = false

    // USB broadcast receiver (system broadcasts — needs RECEIVER_EXPORTED)
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB Device Attached (Dynamic)")
                    updateToggleNotification()
                    if (usbCdcManager == null || usbCdcManager?.isOpen != true) {
                        @Suppress("DEPRECATION")
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (device != null) requestUsbPermissionAndOpen(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB Device Detached")
                    usbCdcManager?.close()
                    usbCdcManager = null
                    onUsbDisconnected()
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        @Suppress("DEPRECATION")
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            Log.d(TAG, "USB Permission Granted for ${device?.deviceName}")
                            if (device != null) openUsbDevice(device)
                        } else {
                            Log.d(TAG, "USB Permission Denied for ${device?.deviceName}")
                        }
                    }
                }
            }
        }
    }

    // Toggle broadcast receiver (app-internal — RECEIVER_NOT_EXPORTED)
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ProjectionAccessibilityService.ACTION_TOGGLE_PROJECTION) {
                Log.d(TAG, "Received toggle broadcast, toggling projection")
                toggleProjection()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannel()
        setupUI()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDpi = metrics.densityDpi
        handleIntent(intent)

        // Register USB broadcast receiver (system broadcasts — RECEIVER_EXPORTED)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, usbFilter)
        }

        // Register toggle broadcast receiver (app-internal — RECEIVER_NOT_EXPORTED)
        val toggleFilter = IntentFilter(ProjectionAccessibilityService.ACTION_TOGGLE_PROJECTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, toggleFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, toggleFilter)
        }

        // Check permissions and start foreground service
        checkAndRequestPermissions()
        ShizukuHelper.checkShizukuStatus(this)
        ProjectionForegroundService.start(this)

        // Load saved config
        loadLastConfig()

        // Auto-scan USB devices
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.deviceList.isNotEmpty()) {
            Log.d(TAG, "USB Device detected on startup")
            updateToggleNotification()
            requestUsbPermissionAndOpen(usbManager.deviceList.values.first())
        }
    }

    private fun setupUI() {
        val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("projection_mode", 0)
        when (savedMode) {
            0 -> binding.modeRadioGroup.check(R.id.radioMirror)
            1 -> binding.modeRadioGroup.check(R.id.radioTaskMigration)
            2 -> binding.modeRadioGroup.check(R.id.radioExtended)
        }

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val modeIndex = when (checkedId) {
                R.id.radioMirror -> 0
                R.id.radioTaskMigration -> 1
                R.id.radioExtended -> 2
                else -> 0
            }
            prefs.edit().putInt("projection_mode", modeIndex).apply()
            
            // If already active, restart projection in new mode
            if (isProjectionActive || isVirtualDisplayReady) {
                toggleProjection() // Stop
                toggleProjection() // Start in new mode
            }
        }

        binding.toggleButton.setOnClickListener { toggleProjection() }
    }

    private fun updateUI() {
        runOnUiThread {
            binding.usbStatusText.text = if (isUsbConnected) "USB 已连接" else "USB 未连接"
            binding.usbStatusText.setTextColor(
                if (isUsbConnected) getColor(R.color.status_green) else getColor(R.color.status_red)
            )
            binding.vdStatusText.text = when {
                isTaskReparented -> "任务投屏运行中 (Task#$activeTaskId)"
                isMediaProjectionCaptureActive -> "屏幕捕获进行中"
                isVirtualDisplayReady -> "虚拟显示器就绪 (ID:${VirtualDisplayManager.currentDisplayId})"
                else -> "等待连接..."
            }

            // 无障碍服务状态提示（触控注入需要）
            val a11yEnabled = isAccessibilityServiceEnabled()
            binding.inkScreenInfoText.text = when {
                !isUsbConnected -> "请通过 USB 连接墨水屏"
                !a11yEnabled -> "⚠️ 请开启无障碍服务以支持触控回传（点击此处）"
                else -> inkScreenInfo
            }
            binding.inkScreenInfoText.isClickable = true
            binding.inkScreenInfoText.setOnClickListener {
                if (!isAccessibilityServiceEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            binding.noPreviewText.visibility = if (binding.previewImage.drawable != null) View.GONE else View.VISIBLE
            binding.toggleButton.text = if (isProjectionActive) "停止截屏" else "开始截屏"
            binding.toggleButton.setIconResource(
                if (isProjectionActive) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
    }

    // ========== USB Management ==========

    private fun requestUsbPermissionAndOpen(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            openUsbDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun openUsbDevice(device: UsbDevice): Boolean {
        usbCdcManager?.close()
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbCdcManager = UsbCdcManager(
            usbManager,
            onDataReceived = { data ->
                val text = String(data)
                usbBuffer.append(text)

                while (usbBuffer.contains("\n")) {
                    val index = usbBuffer.indexOf("\n")
                    val line = usbBuffer.substring(0, index).trim()
                    usbBuffer.delete(0, index + 1)

                    // 修复：统一由 handleUsbData 处理所有数据
                    handleUsbData(line)
                }
            },
            onError = { error ->
                Log.e(TAG, "USB CDC error: $error")
                onUsbDisconnected()
            }
        )
        val success = usbCdcManager!!.open(device)
        if (success) {
            Log.d(TAG, "USB opened, sending initial CMD:QUERY")
            usbWrite("CMD:QUERY\r\n".toByteArray())
        } else {
            usbCdcManager?.close()
            usbCdcManager = null
        }
        return success
    }

    private fun handleUsbData(line: String) {
        Log.d(TAG, "USB RX: $line")

        if (line.contains("READY")) {
            if (!isUsbConnected) {
                Log.d(TAG, "捕获 READY 信号，发送 CMD:QUERY...")
                isUsbConnected = true
                updateUI()
            }
            usbWrite("CMD:QUERY\r\n".toByteArray())
        }

        if (line.startsWith("INFO:")) {
            parseInkScreenInfo(line)
        }

        // 修复：统一处理触控信号 TOUCH:x,y,pressed
        if (line.startsWith("TOUCH:")) {
            parseTouchEvent(line)
        }
    }

    /**
     * 解析触控事件：TOUCH:x,y,pressed
     * 
     * @param line 格式：TOUCH:270,480,1
     *             x, y: 像素坐标 (0-540, 0-960)
     *             pressed: 1=按下, 0=抬起
     */
    private fun parseTouchEvent(line: String) {
        try {
            val parts = line.substring(6).trim().split(",")
            if (parts.size >= 3) {
                val x = parts[0].toFloat()
                val y = parts[1].toFloat()
                val pressed = parts[2].toInt()

                val displayId = VirtualDisplayManager.currentDisplayId
                if (displayId == -1) {
                    Log.w(TAG, "Virtual display not ready, ignoring touch")
                    return
                }

                // 直接使用像素坐标注入到虚拟显示器
                ProjectionAccessibilityService.dispatchTouch(x, y, pressed, displayId)
                
                // 定期输出调试信息
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "Touch injected: ($x, $y) pressed=$pressed to display $displayId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Touch parsing error: ${e.message}", e)
        }
    }

    private fun parseInkScreenInfo(info: String) {
        inkScreenInfo = info
        isUsbConnected = true
        updateUI()

        val resMatch = Regex("RES=(\\d+)x(\\d+)").find(info)
        val physMatch = Regex("PHYS=(\\d+)x(\\d+)mm").find(info)
        if (resMatch != null) {
            val w = resMatch.groupValues[1].toInt()
            val h = resMatch.groupValues[2].toInt()
            var dpi = 234
            if (physMatch != null) {
                val physWmm = physMatch.groupValues[1].toDouble()
                val physHmm = physMatch.groupValues[2].toDouble()
                val diagPx = sqrt((w * w + h * h).toDouble())
                val diagMm = sqrt((physWmm * physWmm + physHmm * physHmm).toDouble())
                dpi = (diagPx / (diagMm / 25.4)).toInt()
            }
            Log.d(TAG, "自动同步分辨率: ${w}x${h}, DPI=$dpi")

            // 保存配置
            saveConfig(w, h, dpi)
            vdWidth = w
            vdHeight = h

            // 自动创建虚拟显示器并开始帧捕获
            if (!isVirtualDisplayReady) {
                Log.d(TAG, "自动创建虚拟显示器: ${w}x${h} @ ${dpi}dpi")
                setupVirtualDisplay(w, h, dpi) { displayId ->
                    if (displayId == -1) {
                        Log.w(TAG, "自动创建虚拟显示器失败")
                        Toast.makeText(this, "自动创建虚拟显示器失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onUsbDisconnected() {
        // 如果有任务在虚拟屏上，先移回主屏
        if (isTaskReparented && activeTaskId != -1) {
            Log.w(TAG, "USB disconnected, moving task $activeTaskId back to main display")
            ShizukuHelper.moveTaskToDisplay(activeTaskId, 0) { success ->
                Log.d(TAG, "USB disconnect: move task back result=$success")
            }
            activeTaskId = -1
            activeTaskIdStatic = -1
            isTaskReparented = false
        }

        isUsbConnected = false
        usbBuffer.clear()
        inkScreenInfo = "未连接设备"

        if (isProjectionActive) {
            isProjectionActive = false
            isProjectionActiveStatic = false
        }

        tearDownVirtualDisplay()

        runOnUiThread {
            binding.previewImage.setImageBitmap(null)
        }
        updateUI()
        updateToggleNotification()
    }

    private fun usbWrite(data: ByteArray): Boolean {
        return usbCdcManager?.write(data) ?: false
    }

    // ========== Virtual Display Management ==========

    /**
     * 创建 ImageReader + 虚拟显示器。
     *
     * 优先通过 ShellService（UID=2000）创建带系统装饰的完整虚拟显示器；
     * ShellService 不可用时降级为 App 进程创建（无系统装饰）。
     *
     * @param onReady 创建成功后的回调（主线程），参数为 displayId
     */
    private fun setupVirtualDisplay(width: Int, height: Int, dpi: Int, onReady: ((Int) -> Unit)? = null) {
        if (width <= 0 || height <= 0) { onReady?.invoke(-1); return }

        if (isVirtualDisplayReady && vdWidth == width && vdHeight == height) {
            Log.d(TAG, "Virtual display already exists with same spec")
            onReady?.invoke(VirtualDisplayManager.currentDisplayId)
            return
        }

        tearDownVirtualDisplay()
        Log.d(TAG, "Setting up virtual display: ${width}x${height} @ $dpi DPI")

        try {
            handlerThread = HandlerThread("ImageReaderThread")
            handlerThread?.start()
            backgroundHandler = Handler(handlerThread!!.looper)

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ImageReader callback error: ${e.message}")
                }
            }, backgroundHandler)

            val surface = imageReader!!.surface
            
            val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getInt("projection_mode", 0)
            val flags = if (mode == 0) {
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            } else {
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or (1 shl 3) or (1 shl 9) or (1 shl 10)
            }

            // 优先通过 ShellService（shell UID=2000）创建，天然有 TRUSTED 权限
            ShizukuHelper.createTrustedVirtualDisplay(
                name = "InkScreenDisplay",
                width = width,
                height = height,
                dpi = dpi,
                flags = flags,
                surface = surface
            ) { displayId ->
                if (displayId != -1) {
                    // ShellService 创建成功：有系统装饰
                    VirtualDisplayManager.registerExternalDisplay(displayId)
                    vdWidth = width
                    vdHeight = height
                    currentMirrorDisplayId = displayId
                    isVirtualDisplayReady = true
                    Log.d(TAG, "Virtual display ready via ShellService (with system decorations), id=$displayId")
                    updateUI()
                    onReady?.invoke(displayId)
                } else {
                    // ShellService 失败
                    Log.w(TAG, "ShellService createTrustedVirtualDisplay failed")
                    
                    if (mode == 0) {
                        Log.w(TAG, "Mirror mode failing fast to MediaProjection")
                        onReady?.invoke(-1)
                    } else {
                        // 降级到 App 进程创建（无系统装饰）
                        val fallbackId = VirtualDisplayManager.createPublicVirtualDisplay(
                            context = this,
                            surface = surface,
                            width = width,
                            height = height,
                            dpi = dpi
                        )
                        if (fallbackId != -1) {
                            vdWidth = width
                            vdHeight = height
                            currentMirrorDisplayId = fallbackId
                            isVirtualDisplayReady = true
                            Log.d(TAG, "Virtual display ready via fallback (no system decorations), id=$fallbackId")
                            updateUI()
                            onReady?.invoke(fallbackId)
                        } else {
                            Log.e(TAG, "Both ShellService and fallback failed to create virtual display")
                            onReady?.invoke(-1)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup virtual display: ${e.message}")
            onReady?.invoke(-1)
        }
    }

    private fun tearDownVirtualDisplay() {
        VirtualDisplayManager.release()
        mediaProjectionDisplay?.release()
        mediaProjectionDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        MediaProjectionService.stop(this)
        imageReader?.close()
        imageReader = null
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
        isVirtualDisplayReady = false
        currentMirrorDisplayId = -1
    }

    private fun startMediaProjectionCapture() {
        if (mediaProjection != null) {
            createMediaProjectionVirtualDisplay()
            return
        }

        if (mediaProjectionManager == null) {
            Toast.makeText(this, "无法获取屏幕投影服务", Toast.LENGTH_SHORT).show()
            return
        }

        val captureIntent = mediaProjectionManager!!.createScreenCaptureIntent()
        mediaProjectionPermissionLauncher.launch(captureIntent)
    }

    private fun createMediaProjectionVirtualDisplay() {
        if (isMediaProjectionCaptureActive) return
        if (mediaProjection == null) {
            startMediaProjectionCapture()
            return
        }

        try {
            handlerThread = HandlerThread("MediaProjectionCapture")
            handlerThread?.start()
            backgroundHandler = Handler(handlerThread!!.looper)

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ImageReader callback error: ${e.message}")
                }
            }, backgroundHandler)

            val surface = imageReader!!.surface
            mediaProjectionDisplay = mediaProjection!!.createVirtualDisplay(
                "MediaProjectionCapture",
                captureWidth,
                captureHeight,
                captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                backgroundHandler
            )

            if (mediaProjectionDisplay == null) {
                Toast.makeText(this, "创建屏幕捕获虚拟显示失败", Toast.LENGTH_SHORT).show()
                return
            }

            isMediaProjectionCaptureActive = true
            isProjectionActive = true
            lastCaptureSavedAt = 0L
            updateUI()
            Toast.makeText(this, "屏幕捕获已开始", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "MediaProjection capture started: ${captureWidth}x${captureHeight}@${captureDpi}")
        } catch (e: Exception) {
            Log.e(TAG, "createMediaProjectionVirtualDisplay failed: ${e.message}")
            Toast.makeText(this, "屏幕捕获启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMediaProjectionCapture() {
        if (!isMediaProjectionCaptureActive) return

        isMediaProjectionCaptureActive = false
        isProjectionActive = false
        tearDownVirtualDisplay()
        updateUI()
        Toast.makeText(this, "屏幕捕获已停止", Toast.LENGTH_SHORT).show()
    }

    private fun saveCaptureIfDue(gray8bit: ByteArray, width: Int, height: Int) {
        // Allow saving regardless of mode if projection/display is active
        if (!isMediaProjectionCaptureActive && !isVirtualDisplayReady) return

        val now = System.currentTimeMillis()
        if (now - lastCaptureSavedAt < 2000) return
        lastCaptureSavedAt = now

        val pixels = IntArray(width * height)
        for (i in gray8bit.indices) {
            val gray = gray8bit[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        backgroundHandler?.post {
            try {
                val root = getExternalFilesDir(null) ?: filesDir
                val folder = File(root, "captures")
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, "capture_${now}.png")
                FileOutputStream(file).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
                }
                Log.d(TAG, "Saved capture to ${file.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save capture: ${e.message}")
            }
        }
    }

    // ========== Image Processing ==========

    private fun processImage(image: android.media.Image) {
        val width = image.width
        val height = image.height
        if (frameCount % 30 == 0) Log.d(TAG, "ImageReader: frame $frameCount ($width x $height)")
        frameCount++

        val grayData4Bit = ByteArray((width * height + 1) / 2)
        val grayData8BitForPreview = ByteArray(width * height)

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val cap = buffer.capacity()

            val gray8 = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = y * rowStride + x * pixelStride
                    if (offset + 2 < cap) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        gray8[y * width + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    } else {
                        gray8[y * width + x] = 255
                    }
                }
            }

            // Floyd-Steinberg dithering
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val oldPixel = gray8[y * width + x]
                    val newPixel = (oldPixel shr 4 shl 4).coerceIn(0, 255)
                    gray8[y * width + x] = newPixel
                    val error = oldPixel - newPixel

                    if (x + 1 < width) gray8[y * width + (x + 1)] += (error * 7 / 16)
                    if (y + 1 < height) {
                        if (x > 0) gray8[(y + 1) * width + (x - 1)] += (error * 3 / 16)
                        gray8[(y + 1) * width + x] += (error * 5 / 16)
                        if (x + 1 < width) gray8[(y + 1) * width + (x + 1)] += (error * 1 / 16)
                    }
                }
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val g4 = (gray8[y * width + x] shr 4) and 0x0F
                    grayData8BitForPreview[y * width + x] = (g4 shl 4).toByte()

                    val idx4 = (y * width + x) / 2
                    if ((y * width + x) % 2 == 0) {
                        grayData4Bit[idx4] = (g4 shl 4).toByte()
                    } else {
                        grayData4Bit[idx4] = (grayData4Bit[idx4].toInt() or g4).toByte()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dithering/Buffer error: ${e.message}")
            return
        }

        try {
            val factory = LZ4Factory.fastestInstance()
            val compressor = factory.fastCompressor()
            val maxLen = compressor.maxCompressedLength(grayData4Bit.size)
            val compressed = ByteArray(maxLen)
            val compLen = compressor.compress(grayData4Bit, 0, grayData4Bit.size, compressed, 0, maxLen)

            val fullData = ByteArray(12 + compLen)
            System.arraycopy("EIMG".toByteArray(), 0, fullData, 0, 4)
            fullData[4] = (width and 0xFF).toByte()
            fullData[5] = ((width shr 8) and 0xFF).toByte()
            fullData[6] = (height and 0xFF).toByte()
            fullData[7] = ((height shr 8) and 0xFF).toByte()
            fullData[8] = (compLen and 0xFF).toByte()
            fullData[9] = ((compLen shr 8) and 0xFF).toByte()
            fullData[10] = ((compLen shr 16) and 0xFF).toByte()
            fullData[11] = ((compLen shr 24) and 0xFF).toByte()
            System.arraycopy(compressed, 0, fullData, 12, compLen)

            if (usbCdcManager?.isOpen == true) {
                Log.d(TAG, "Sending frame to USB (${fullData.size} bytes)")
                val success = usbCdcManager!!.writeWithAck(fullData)
                if (success) {
                    if (frameCount % 30 == 0) Log.d(TAG, "Frame sent successfully")
                } else {
                    Log.e(TAG, "Frame send failed - cooling down")
                    Thread.sleep(100)
                }
            } else {
                if (frameCount % 30 == 0) Log.w(TAG, "USB not open, skipping frame send")
            }

            updatePreview(grayData8BitForPreview, width, height)
            saveCaptureIfDue(grayData8BitForPreview, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}")
        }
    }

    private fun updatePreview(gray8bit: ByteArray, width: Int, height: Int) {
        val pixels = IntArray(width * height)
        for (i in gray8bit.indices) {
            val gray = gray8bit[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        runOnUiThread {
            binding.previewImage.setImageBitmap(bitmap)
            binding.noPreviewText.visibility = View.GONE
        }
    }

    // ========== Projection Toggle (Task Reparenting) ==========

    private fun toggleProjection() {
        val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("projection_mode", 0)
        
        if (isProjectionActive || isVirtualDisplayReady) {
            stopMediaProjectionCapture()
            stopProjection()
            stopExtendedDisplay()
            
            isProjectionActive = false
            isProjectionActiveStatic = false
            updateUI()
        } else {
            when (mode) {
                0 -> startExtendedDisplay() // Mirror mode (try Shizuku first, fallback to MediaProjection)
                1 -> startProjection()      // Task Migration mode
                2 -> startExtendedDisplay() // Extended Display mode
            }
        }
        updateTileService()
        updateToggleNotification()
    }

    private fun startExtendedDisplay() {
        if (!isVirtualDisplayReady) {
            val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
            val w = prefs.getInt("last_w", 540)
            val h = prefs.getInt("last_h", 960)
            val d = prefs.getInt("last_dpi", 234)
            setupVirtualDisplay(w, h, d) { displayId ->
                if (displayId == -1) {
                    val mode = prefs.getInt("projection_mode", 0)
                    if (mode == 0) {
                        Log.d(TAG, "Shell mirror failed, falling back to MediaProjection")
                        startMediaProjectionCapture()
                    } else {
                        Toast.makeText(this, "创建虚拟显示器失败", Toast.LENGTH_SHORT).show()
                    }
                    return@setupVirtualDisplay
                }
                isProjectionActive = true
                isProjectionActiveStatic = true
                updateUI()
                
                val mode = prefs.getInt("projection_mode", 0)
                val modeName = if (mode == 0) "镜像模式" else "扩展屏"
                Toast.makeText(this, "$modeName 已启动 (Display#$displayId)", Toast.LENGTH_SHORT).show()
            }
            return
        }
        isProjectionActive = true
        isProjectionActiveStatic = true
        updateUI()
    }

    private fun stopExtendedDisplay() {
        tearDownVirtualDisplay()
    }

    /**
     * 开始投屏 - 启用 Shizuku 任务迁移
     */
    private fun startProjection() {
        // 确保虚拟显示器已创建
        if (!isVirtualDisplayReady) {
            val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
            val w = prefs.getInt("last_w", 540)
            val h = prefs.getInt("last_h", 960)
            val d = prefs.getInt("last_dpi", 234)
            setupVirtualDisplay(w, h, d) { displayId ->
                if (displayId == -1) {
                    Toast.makeText(this, "创建虚拟显示器失败", Toast.LENGTH_SHORT).show()
                    return@setupVirtualDisplay
                }
                doStartProjection()
            }
            return
        }
        doStartProjection()
    }

    /** startProjection 的实际执行体，在虚拟显示器就绪后调用 */
    private fun doStartProjection() {
        val shizukuStatus = ShizukuHelper.checkShizukuStatus(this)
        if (shizukuStatus != "OK") {
            Toast.makeText(this, "Shizuku 未就绪: $shizukuStatus\n\n请先安装并启动 Shizuku 应用", Toast.LENGTH_LONG).show()
            return
        }
        
        ShizukuHelper.getForegroundTaskId { taskId ->
            if (taskId == -1) {
                Toast.makeText(this, "无法获取前台任务 ID\n请确保有应用在前台运行", Toast.LENGTH_SHORT).show()
                return@getForegroundTaskId
            }
            
            val displayId = VirtualDisplayManager.currentDisplayId
            Log.d(TAG, "Moving task $taskId to display $displayId")
            
            ShizukuHelper.moveTaskToDisplay(taskId, displayId) { success ->
                if (!success) {
                    Toast.makeText(this, "任务转移失败\n该应用可能不支持多显示器", Toast.LENGTH_SHORT).show()
                    return@moveTaskToDisplay
                }
                
                activeTaskId = taskId
                activeTaskIdStatic = taskId
                isTaskReparented = true
                isProjectionActive = true
                isProjectionActiveStatic = true
                updateUI()
                
                Toast.makeText(this, "投屏已开启\nTask#$taskId → Display#$displayId", Toast.LENGTH_SHORT).show()
                
                // 启动 BlackHole 省电
                try {
                    startActivity(Intent(this, BlackHoleActivity::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start BlackHole: ${e.message}")
                }
            }
        }
    }

    /**
     * 停止投屏：将任务移回主屏
     */
    private fun stopProjection() {
        if (activeTaskId != -1) {
            ShizukuHelper.moveTaskToDisplay(activeTaskId, 0) { success ->
                Log.d(TAG, "Move task $activeTaskId back to main display: $success")
            }
        }

        // 关闭 BlackHole
        try {
            val intent = Intent(this, BlackHoleActivity::class.java)
            intent.putExtra("action", "stop")
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BlackHole: ${e.message}")
        }

        activeTaskId = -1
        activeTaskIdStatic = -1
        isTaskReparented = false
        isProjectionActive = false
        isProjectionActiveStatic = false
        updateUI()
        Toast.makeText(this, "投屏已停止", Toast.LENGTH_SHORT).show()
    }

    // ========== Touch Injection ==========

    // ========== Notifications ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "投屏切换通知"
            val desc = "点击切换主屏与墨水屏"
            val importance = android.app.NotificationManager.IMPORTANCE_LOW
            val channel = android.app.NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateToggleNotification() {
        val intent = Intent(ProjectionAccessibilityService.ACTION_TOGGLE_PROJECTION).apply {
            setPackage(packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags)

        val title = if (isProjectionActive) "正在墨水屏显示" else "准备就绪"
        val text = if (isProjectionActive) "点击此处：停止投屏" else "点击此处：移至墨水屏"

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        try {
            val nm = NotificationManagerCompat.from(this)
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }

    private fun removeToggleNotification() {
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIFICATION_ID)
    }

    private fun updateTileService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                android.service.quicksettings.TileService.requestListeningState(
                    this,
                    ComponentName(this, ProjectionTileService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Tile: ${e.message}")
            }
        }
    }

    // ========== Permissions ==========

    private fun checkAndRequestPermissions() {
        updateToggleNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission")
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // 无障碍服务用于触控注入（墨水屏 → 虚拟显示器）
        // if (!isAccessibilityServiceEnabled()) {
        //     Log.d(TAG, "Accessibility service not enabled, showing prompt")
        //     showAccessibilitySettingsPrompt()
        // }
    }

    private fun showAccessibilitySettingsPrompt() {
        if (hasShownAccessibilityPrompt) return
        hasShownAccessibilityPrompt = true

        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage("触控回传需要启用无障碍服务。是否现在前往设置页开启？")
            .setPositiveButton("前往设置") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
                    Toast.makeText(this, "无法打开无障碍设置，请手动进入系统设置", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        val service = "$packageName/${ProjectionAccessibilityService::class.java.canonicalName}"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            Log.e(TAG, "Error finding setting: ${e.message}")
        }
        val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                applicationContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    val accessibilityService = mStringColonSplitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted")
                updateToggleNotification()
            }
        }
    }

    // ========== Config Persistence ==========

    private fun saveConfig(w: Int, h: Int, dpi: Int) {
        val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("last_w", w).putInt("last_h", h).putInt("last_dpi", dpi).apply()
    }

    private fun loadLastConfig() {
        val prefs = getSharedPreferences("screen_prefs", Context.MODE_PRIVATE)
        vdWidth = prefs.getInt("last_w", 540)
        vdHeight = prefs.getInt("last_h", 960)
    }

    // ========== Lifecycle ==========

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从无障碍设置、权限页面等返回后，重新检查 USB 连接状态
        if (usbCdcManager == null || usbCdcManager?.isOpen != true) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usbManager.deviceList.values.firstOrNull()
            if (device != null) {
                Log.d(TAG, "onResume: USB device found, attempting reconnect")
                requestUsbPermissionAndOpen(device)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d(TAG, "USB Device Attached")
            updateToggleNotification()
            if (usbCdcManager?.isOpen != true) {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (device != null) requestUsbPermissionAndOpen(device)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 将任务移回主屏
        if (isTaskReparented && activeTaskId != -1) {
            ShizukuHelper.moveTaskToDisplay(activeTaskId, 0) { success ->
                Log.d(TAG, "onDestroy: move task back result=$success")
            }
        }
        tearDownVirtualDisplay()
        usbCdcManager?.close()
        usbCdcManager = null
        removeToggleNotification()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(toggleReceiver) } catch (_: Exception) {}
    }
}
