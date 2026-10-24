package com.motohud.ble.data

import android.content.Context
import com.motohud.ble.MotoHudApp
import com.motohud.ble.ble.BleConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 导航历史记录器。
 *
 * 用法（[com.motohud.ble.ui.navi.GuidanceActivity]）：
 * ```
 * val id = NaviHistory.begin(destName, destLat, destLng, startName, startLat, startLng, distanceM)
 * ...
 * NaviHistory.finish(id, castToDevice)   // 到达或停止时
 * ```
 *
 * 关于"仅用户本人可见"：数据只写本机 Room，不发任何网络请求，
 * 也没有导出 content provider 给别的应用读，所以天然只有用户自己能看到。
 *
 * 关于占用：每次导航只写一行，最多保留 [MAX_KEEP] 条（见 [NaviHistoryDao.trimTo]），
 * 一行十几个字段，量级完全可忽略。
 */
object NaviHistory {

    /** 最多保留的记录条数，超出后自动删最旧的 */
    const val MAX_KEEP = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 记录一次导航的开始。
     *
     * 这里必须同步拿到 id（引导页要在 onDestroy 时用它更新那条记录），
     * 所以用 `runBlocking` 包一层。Room 的 insert 只是一次本地 SQLite 写入，
     * 耗时在毫秒级，可以接受。
     *
     * @return 记录 id；失败返回 null（历史功能不应影响导航本身）
     */
    fun begin(
        app: MotoHudApp,
        destName: String,
        destLat: Double,
        destLng: Double,
        startName: String,
        startLat: Double,
        startLng: Double,
        distanceM: Double
    ): Long? {
        val entity = NaviHistoryEntity(
            destName = destName,
            destLat = destLat,
            destLng = destLng,
            startName = startName,
            startLat = startLat,
            startLng = startLng,
            startedAt = System.currentTimeMillis(),
            distanceM = distanceM
        )
        return try {
            runBlocking {
                withTimeoutOrNull(1500L) { app.naviHistoryDao.insert(entity) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 标记一次导航结束，并补上结束时的车机快照 */
    fun finish(app: MotoHudApp, id: Long?, castToDevice: Boolean) {
        if (id == null || id <= 0) return
        scope.launch {
            runCatching {
                val dao = app.naviHistoryDao
                val row = dao.listAll().firstOrNull { it.id == id } ?: return@runCatching
                val now = System.currentTimeMillis()
                dao.update(
                    row.copy(
                        finishedAt = now,
                        durationMs = now - row.startedAt,
                        castToDevice = castToDevice,
                        deviceName = BikeSnapshot.deviceName,
                        deviceType = deviceTypeOf(BikeSnapshot.deviceName),
                        battery = BikeSnapshot.battery,
                        waterTemp = BikeSnapshot.waterTemp,
                        oil = BikeSnapshot.oil,
                        mileage = BikeSnapshot.mileage
                    )
                )
                dao.trimTo(MAX_KEEP)
            }
        }
    }

    /** 删除一条 */
    fun delete(app: MotoHudApp, id: Long) {
        scope.launch { runCatching { app.naviHistoryDao.delete(id) } }
    }

    /** 清空全部 */
    fun clear(app: MotoHudApp) {
        scope.launch { runCatching { app.naviHistoryDao.clear() } }
    }

    /** 由设备名推断协议族标签 */
    private fun deviceTypeOf(deviceName: String?): String? {
        if (deviceName.isNullOrBlank()) return null
        return when (BleConstants.deviceTypeOf(deviceName)) {
            BleConstants.DeviceType.SR -> "SR"
            BleConstants.DeviceType.BYQ -> "BYQ"
            else -> "通用"
        }
    }
}
