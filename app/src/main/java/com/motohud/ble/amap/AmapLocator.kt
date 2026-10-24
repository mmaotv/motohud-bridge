package com.motohud.ble.amap

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlin.math.roundToInt

/**
 * 高德连续定位封装。
 *
 * 为什么不用系统的 LocationManager 直接导航：
 *  1. 系统 GPS 给 WGS84，国内地图/路线是 GCJ02，差 300~700 米；
 *  2. 网络定位点精度差、抖动大，且不带速度/航向；
 *  3. 高德定位 SDK 内部做了 GCJ02 纠偏、多源融合、缓存与传感器辅助，
 *     是地图蓝点与路线匹配的同一套坐标基准。
 *
 * 三个开关的取舍：
 *  - Hight_Accuracy + GPS 优先：骑行导航要有真实速度与航向，必须优先卫星；
 *  - sensorEnable：隧道/高楼间用陀螺仪补航向，避免红点乱转；
 *  - mockEnable：正式包固定 false（拒绝模拟位置，防止被第三方定位软件污染）；
 *    debug 包放开，便于用 `cmd location set-test-provider-location` 做路线/漂移联调。
 */
class AmapLocator(private val context: Context) {

    data class Fix(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val bearing: Float,
        val speedMps: Float,
        /** 定位来源：1=GPS 2=前次 4=缓存 5=离线 6=网络 8=高精度融合 */
        val locationType: Int
    ) {
        val fromSatellite: Boolean get() = locationType == 1 || locationType == 8
    }

    private var client: AMapLocationClient? = null
    private var running = false

    /** 是否可用（已授权隐私 + 已配置 Key） */
    fun isAvailable(): Boolean = AmapManager.initLocation(context)

    /**
     * 开始连续定位。
     * @param onFix 每帧回调（主线程），仅 errCode == 0 时触发
     */
    fun start(onFix: (Fix) -> Unit, onError: ((Int) -> Unit)? = null) {
        if (running) stop()
        if (!isAvailable()) return
        val c = runCatching { AMapLocationClient(context.applicationContext) }.getOrNull() ?: return
        runCatching {
            c.setLocationOption(
                AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    // 1 秒一次，与导航引擎 tick 对齐
                    interval = 1000L
                    isNeedAddress = false
                    isOnceLocation = false
                    isOnceLocationLatest = false
                    isMockEnable = com.motohud.ble.BuildConfig.DEBUG
                    isLocationCacheEnable = true
                    isGpsFirst = true
                    gpsFirstTimeout = 15_000L
                    isSensorEnable = true
                    httpTimeOut = 10_000L
                }
            )
            c.setLocationListener { loc: AMapLocation? ->
                if (loc == null) return@setLocationListener
                if (loc.errorCode != 0) {
                    onError?.invoke(loc.errorCode)
                    return@setLocationListener
                }
                // coordType 正常为 GCJ02；万一是 WGS84（境外/特殊机型）就地纠偏
                val isWgs = loc.coordType.equals("WGS84", ignoreCase = true)
                val (lat, lng) = if (isWgs) {
                    com.motohud.ble.navi.CoordConverter.wgs84ToGCJ(loc.latitude, loc.longitude)
                } else {
                    loc.latitude to loc.longitude
                }
                onFix(
                    Fix(
                        lat = lat,
                        lng = lng,
                        accuracy = loc.accuracy,
                        bearing = loc.bearing,
                        speedMps = loc.speed,
                        locationType = loc.locationType
                    )
                )
            }
            c.startLocation()
            client = c
            running = true
        }.onFailure {
            runCatching { c.onDestroy() }
        }
    }

    fun stop() {
        running = false
        runCatching {
            client?.stopLocation()
            client?.onDestroy()
        }
        client = null
    }
}

/** 定位类型的人类可读标签（导航页状态位展示） */
fun locationTypeLabel(type: Int): String = when (type) {
    1 -> "GPS"
    2 -> "GPS"
    4 -> "缓存"
    5 -> "离线"
    6 -> "网络"
    8 -> "融合"
    else -> "定位"
}

/** 精度分档：用于界面上"信号弱"提示 */
fun accuracyLevel(accuracy: Float): Int = when {
    accuracy <= 0f -> 0
    accuracy <= 15f -> 3   // 好
    accuracy <= 40f -> 2   // 一般
    else -> 1              // 弱
}

/** 精度显示文案 */
fun formatAccuracy(accuracy: Float): String =
    if (accuracy <= 0f) "--" else "±${accuracy.roundToInt()}m"
