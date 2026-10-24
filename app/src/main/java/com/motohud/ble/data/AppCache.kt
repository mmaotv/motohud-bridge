package com.motohud.ble.data

import android.content.Context
import java.io.File

/**
 * 应用缓存统计与清理。
 *
 * 背景：导航页每次打开都会加载高德地图瓦片，SDK 会把瓦片/搜索结果写进 cacheDir，
 * 长期使用缓存会一直变大。这里提供统一的「算大小 + 清理」能力，供设置页入口使用。
 *
 * 只清理 cacheDir / externalCacheDir（系统随时可能自己清的标准缓存位置），
 * 绝不碰 filesDir —— 用户自选的车照、数据库都在那里。
 */
object AppCache {

    /** 缓存总大小（字节）：内部缓存 + 外部缓存。 */
    fun size(context: Context): Long = dirSize(context.cacheDir) + dirSize(context.externalCacheDir)

    /** 清空全部标准缓存目录，返回清理的字节数。 */
    fun clear(context: Context): Long {
        var freed = 0L
        listOf(context.cacheDir, context.externalCacheDir).forEach { root ->
            if (root == null || !root.exists()) return@forEach
            freed += dirSize(root)
            root.listFiles()?.forEach { it.deleteRecursively() }
        }
        return freed
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** 人性化大小：KB / MB / GB。 */
    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
