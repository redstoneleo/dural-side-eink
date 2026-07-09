package com.example.myapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * BlackHoleActivity — "黑洞模式" 全屏黑色遮罩
 *
 * 职责：
 * 1. 显示全黑屏幕 + 零亮度，模拟"息屏"效果（OLED 几乎不耗电）
 * 2. FLAG_KEEP_SCREEN_ON 保持渲染管线不断开
 * 3. 沉浸式全屏：系统栏深度隐藏，需要连续滑动两次才能临时唤出
 * 4. 拦截一切触摸事件，物理主屏完全不可操作
 */
class BlackHoleActivity : Activity() {

    companion object {
        private const val TAG = "BlackHoleActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "BlackHoleActivity created")

        if (handleIntent(intent)) return

        // Window flags
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Zero brightness
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 0.0f
        window.attributes = layoutParams

        // Pure black background
        window.decorView.setBackgroundColor(Color.BLACK)

        // Immersive mode
        setupImmersiveMode()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.getStringExtra("action") == "stop") {
            Log.d(TAG, "Stop action received, finishing")
            finish()
            return true
        }
        return false
    }

    private fun setupImmersiveMode() {
        val decorView = window.decorView

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(
                    android.view.WindowInsets.Type.statusBars()
                            or android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }

        // Re-hide system bars when they become visible
        @Suppress("DEPRECATION")
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decorView.postDelayed({ setupImmersiveMode() }, 3000)
            }
        }
    }

    /**
     * Intercept all touch events — main screen becomes completely unresponsive
     */
    @Deprecated("Deprecated in Java")
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * Intercept back button
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Log.d(TAG, "Back press intercepted and consumed")
    }

    override fun onDestroy() {
        Log.d(TAG, "BlackHoleActivity destroyed")
        super.onDestroy()
    }
}
