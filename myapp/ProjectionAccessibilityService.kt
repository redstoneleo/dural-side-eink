package com.example.myapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ProjectionAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TOGGLE_PROJECTION = "com.example.myapp.ACTION_TOGGLE_PROJECTION"
        const val ACTION_DOWN = 1
        const val ACTION_UP = 0
        private var instance: ProjectionAccessibilityService? = null
        var lastActivePackage: String? = null

        fun dispatchTouch(x: Float, y: Float, action: Int, displayId: Int) {
            val service = instance ?: return

            val path = Path()
            path.moveTo(x, y)

            val builder = GestureDescription.Builder()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setDisplayId(displayId)
            }

            // 按下：保持 100ms；抬起：极短 1ms 模拟抬起
            val duration = if (action == ACTION_DOWN) 100L else 1L
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))

            service.dispatchGesture(builder.build(), null, null)
        }

        /**
         * 执行系统全局动作（导航键）。
         * 不需要任何额外权限，AccessibilityService 内置支持。
         *
         * @param action AccessibilityService.GLOBAL_ACTION_* 常量：
         *   GLOBAL_ACTION_BACK          = 1  ← 返回键 ◀
         *   GLOBAL_ACTION_HOME          = 2  ← Home 键 ⚫
         *   GLOBAL_ACTION_RECENTS       = 3  ← 最近任务 ▢
         */
        fun performNav(action: Int): Boolean {
            val service = instance
            if (service == null) {
                Log.w("ProjectionService", "performNav: service not connected")
                return false
            }
            return service.performGlobalAction(action)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null &&
                packageName != "com.example.myapp" &&
                packageName != "com.android.systemui" &&
                !packageName.contains("launcher")) {
                lastActivePackage = packageName
                Log.d("ProjectionService", "Target app captured: $lastActivePackage")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("ProjectionService", "Accessibility Service Connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
