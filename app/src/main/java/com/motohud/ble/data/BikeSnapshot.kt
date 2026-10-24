package com.motohud.ble.data

/**
 * 车机遥测的最新一帧缓存。
 *
 * 导航历史要记录「本次导航结束时车况如何」，但遥测是 BLE 持续推送的，
 * 解析在 [com.motohud.ble.ui.MainViewModel] 里，而引导页是独立 Activity，
 * 拿不到那个 ViewModel。于是用这个极轻量的单例做中转：
 * MainViewModel 每解一帧就更新一下，结束时直接读。
 *
 * 只是几个字段的赋值，不占什么资源。
 */
object BikeSnapshot {

    @Volatile
    var deviceName: String? = null

    /** 电压（V） */
    @Volatile
    var battery: Float? = null

    /** 水温（℃），0 表示 <45 */
    @Volatile
    var waterTemp: Int? = null

    /** 油量（%） */
    @Volatile
    var oil: Int? = null

    /** 总里程（km） */
    @Volatile
    var mileage: Int? = null

    /** 连接断开时清空，避免把上一次连接的旧值记到新记录里 */
    fun clear() {
        deviceName = null
        battery = null
        waterTemp = null
        oil = null
        mileage = null
    }
}
