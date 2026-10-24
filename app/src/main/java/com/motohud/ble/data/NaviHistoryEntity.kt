package com.motohud.ble.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导航历史记录。
 *
 * 一次导航 = 一行：出发 → 到达 → 用时。
 * 全部存在本机 Room 里，不上传任何服务器，只有用户自己能看。
 *
 * 车机情况（[deviceName] / [deviceType] / [battery] / [waterTemp] / [oil]）
 * 记录的是**本次导航结束时**车机的实时读数，方便回头看"这趟路车况如何"。
 * 没连车机时这些字段为空，界面显示 "--"。
 */
@Entity(
    tableName = "navi_history",
    indices = [Index("startedAt"), Index("finishedAt")]
)
data class NaviHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 目的地名称 */
    val destName: String,
    val destLat: Double,
    val destLng: Double,

    /** 出发地（导航开始时的定位） */
    val startName: String,
    val startLat: Double,
    val startLng: Double,

    /** 出发时间戳（毫秒） */
    val startedAt: Long,

    /** 到达时间戳（毫秒）；0 = 还没结束 */
    val finishedAt: Long = 0L,

    /** 实际用时（毫秒）；0 = 还没结束 */
    val durationMs: Long = 0L,

    /** 规划总距离（米），0 = 未知 */
    val distanceM: Double = 0.0,

    /** 本次导航是否投屏到车机 */
    val castToDevice: Boolean = false,

    /** 结束时车机名（未连接为空） */
    val deviceName: String? = null,

    /** 结束时车机协议族：SR / BYQ / 通用 / 空 */
    val deviceType: String? = null,

    /** 结束时电瓶电压（V），null = 未知 */
    val battery: Float? = null,

    /** 结束时水温（℃），null = 未知；0 表示 <45 */
    val waterTemp: Int? = null,

    /** 结束时油量（%），null = 未知 */
    val oil: Int? = null,

    /** 结束时总里程（km），null = 未知 */
    val mileage: Int? = null
) {
    /** 是否已结束 */
    val finished: Boolean get() = finishedAt > 0L
}
