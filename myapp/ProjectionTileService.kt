package com.example.myapp

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * 快捷设置磁贴 — "移至墨水屏"
 *
 * 切换逻辑：
 * - 第一次点击：获取前台 TaskID → 创建虚拟屏 → 迁移任务 → 启动 BlackHole
 * - 第二次点击：移回主屏 → 关闭 BlackHole → 释放虚拟屏
 */
class ProjectionTileService : TileService() {

    companion object {
        private const val TAG = "TileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        Log.d(TAG, "Tile clicked, isProjectionActive=${MainActivity.isProjectionActiveStatic}")

        val prefs = getSharedPreferences("screen_prefs", android.content.Context.MODE_PRIVATE)
        val mode = prefs.getInt("projection_mode", 0)

        // Require Shizuku only for Task Migration and Extended Mode
        if (mode == 1 || mode == 2) {
            val status = ShizukuHelper.checkShizukuStatus(this)
            if (status != "OK") {
                Toast.makeText(this, "该模式需要 Shizuku: $status，请检查设置", Toast.LENGTH_LONG).show()
                return
            }
        }

        sendToggleBroadcast()
        updateTile()
    }

    private fun sendToggleBroadcast() {
        // Tile just sends broadcast, MainActivity handles all mode logic.
        val intent = Intent(ProjectionAccessibilityService.ACTION_TOGGLE_PROJECTION)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isActive = MainActivity.isProjectionActiveStatic
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "停止投屏" else "移至墨水屏"
        tile.updateTile()
    }
}
