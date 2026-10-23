package com.motohud.ble.navi

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 导航引擎接口：产生与高德导航 SDK NaviInfo 等价的状态快照，供手机 UI 与
 * 车机投屏（NaviCommand → BLE）共用。
 */
interface NaviEngine {

    /** 导航状态快照（等价 NaviInfo + 位置信息） */
    data class NaviState(
        val running: Boolean = false,
        val arrived: Boolean = false,
        /** 当前位置（GCJ02） */
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        /** 目的地 */
        val destName: String = "",
        val destLatitude: Double = 0.0,
        val destLongitude: Double = 0.0,
        /** 全程剩余（米） */
        val pathRemain: Int = 0,
        /** 下一转弯剩余（米） */
        val stepRemain: Int = 0,
        /** 当前步骤索引（无路线时为 -1） */
        val stepIndex: Int = -1,
        /** 当前步骤引导语（如"沿望京路行驶 500 米后右转"） */
        val stepInstruction: String = "",
        /** 高德 IconType 数值 */
        val iconType: Int = IconType.STRAIGHT,
        /** 当前航向（度，0=正北，沿路线方向） */
        val bearing: Float = 0f,
        /** 实时速度 km/h（GPS 优先，无 GPS 时为模拟速度） */
        val speedKmh: Float = DEFAULT_SPEED_KMH,
        /** 预计剩余秒数 */
        val etaSeconds: Int = 0,
        /** GPS 是否活跃（false=沿路线推算模式） */
        val gpsActive: Boolean = false,
        /** 已行驶距离（米） */
        val traveled: Int = 0,
        /** 是否基于真实规划路线（false=直线演示模式） */
        val onRoute: Boolean = false
    )

    val state: StateFlow<NaviState>

    /**
     * 开始导航。
     * @param route 真实规划路线（高德路线规划结果）；null 时退化为直线演示模式
     */
    fun start(
        destName: String,
        destLat: Double,
        destLng: Double,
        startHint: Pair<Double, Double>? = null,
        route: RouteData? = null
    )

    /** 手动指定转向图标（HUD 演示/测试按钮），立即生效一帧 */
    fun overrideIcon(iconType: Int)

    fun setSpeed(kmh: Float)

    /** 停止导航（不发 FINISH；调用方决定发 FINISH 还是 NO） */
    fun stop()

    fun release()

    /** 步骤切换回调（TTS 播报） */
    var onStepChanged: ((String) -> Unit)?

    companion object {
        const val DEFAULT_SPEED_KMH = 25f
        const val MIN_SPEED_KMH = 5f
        const val MAX_SPEED_KMH = 120f
        const val TICK_MS = 1000L
        const val ARRIVE_THRESHOLD_M = 25.0
        const val GPS_STALE_MS = 3_000L

        /** 默认起点（无定位权限时用于室内演示） */
        val DEFAULT_START = 39.9087 to 116.3975
    }
}

/** 路线单步骤（自高德搜索 SDK DriveStep 转换，GCJ02 坐标） */
data class RouteStep(
    /** 完整引导语，如"沿望京路行驶 500 米后右转" */
    val instruction: String,
    /** 动作文本，如"右转" */
    val action: String,
    /** 方向文本，如"右" */
    val orientation: String,
    /** 道路名 */
    val road: String,
    /** 本步骤距离（米） */
    val distanceM: Float,
    /** 本步骤几何（lat, lng） */
    val polyline: List<Pair<Double, Double>>
)

/** 规划路线（自高德搜索 SDK DrivePath 转换） */
data class RouteData(
    val distanceM: Float,
    val durationS: Float,
    val steps: List<RouteStep>
)

/** 高德 IconType 常用值（com.amap.api.navi.enums.IconType） */
object IconType {
    const val NONE = 0
    const val LEFT = 2
    const val RIGHT = 3
    const val LEFT_FRONT = 4
    const val RIGHT_FRONT = 5
    const val LEFT_BACK = 6
    const val RIGHT_BACK = 7
    const val LEFT_TURN_AROUND = 8
    const val STRAIGHT = 9
    const val ARRIVED_WAYPOINT = 10
    const val ENTER_ROUNDABOUT = 11
    const val OUT_ROUNDABOUT = 12
    const val ARRIVED_DESTINATION = 15
    const val U_TURN_RIGHT = 19
    const val ENTRY_RING_LEFT = 21
    const val MERGE_LEFT = 65
    const val MERGE_RIGHT = 66
}

/** 路线累计点：位置 + 距起点累计距离 */
private data class CumPoint(val lat: Double, val lng: Double, val dist: Double)

/**
 * 路线引导引擎。
 *
 * 复刻原 App 投屏导航数据流（原 App 由高德导航 SDK onNaviInfoUpdate 驱动）：
 *   1. 每秒 tick，优先采用真实 GPS 位置；GPS 失效（室内/隧道/测试）时沿路线航位推算；
 *   2. 将 GPS 位置投影到路线折线，计算 已行驶 / 全程剩余 / 下一转弯剩余；
 *   3. 转向图标由当前步骤动作经 [IconMapper] 映射为高德 IconType（与车机协议一致）；
 *   4. 到达终点（< 25m）触发 arrived，上层发 FINISH :::；
 *   5. 无规划路线时退化为直线演示模式（测试/HUD 演示用）。
 */
class RouteNaviEngine(
    private val locationManager: LocationManager?
) : NaviEngine {

    private val _state = MutableStateFlow(NaviEngine.NaviState())
    override val state: StateFlow<NaviEngine.NaviState> = _state.asStateFlow()

    override var onStepChanged: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastGpsTime = 0L // 0 = 尚无 GPS 更新，首帧走推算
    private var manualIcon: Int? = null
    private var curLat = 0.0
    private var curLng = 0.0
    private var curBearing = 0f

    // ---------- 路线数据 ----------
    private var cumulative: List<CumPoint> = emptyList()
    private var stepEndDist: List<Double> = emptyList()   // 每步结束时的累计距离
    private var steps: List<RouteStep> = emptyList()
    private var totalDist = 0.0
    private var lastIndex = 0

    /** 最近一次使用的规划路线（HUD/导航页共享引擎时用于重绘） */
    var lastRoute: RouteData? = null
        private set

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running) return
            lastGpsTime = System.currentTimeMillis()
            curLat = location.latitude
            curLng = location.longitude
            if (location.hasBearing() && location.speed > 0.5f) curBearing = location.bearing
            val gpsSpeed = if (location.hasSpeed()) location.speed * 3.6f else null
            _state.value = _state.value.copy(
                latitude = curLat,
                longitude = curLng,
                bearing = curBearing,
                gpsActive = true,
                speedKmh = gpsSpeed ?: _state.value.speedKmh
            )
        }

        @Deprecated("旧版回调保留兼容")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            stepOnce()
            handler.postDelayed(this, NaviEngine.TICK_MS)
        }
    }

    // ---------- 启动 / 停止 ----------

    override fun start(
        destName: String,
        destLat: Double,
        destLng: Double,
        startHint: Pair<Double, Double>?,
        route: RouteData?
    ) {
        stopLocationUpdates()
        manualIcon = null
        lastIndex = 0

        val (sLat, sLng) = startHint ?: lastKnownLocation() ?: NaviEngine.DEFAULT_START
        curLat = sLat
        curLng = sLng

        if (route != null && route.steps.isNotEmpty() &&
            route.steps.any { it.polyline.size >= 2 }
        ) {
            lastRoute = route
            buildRoute(route)
        } else {
            lastRoute = null
            cumulative = emptyList()
            stepEndDist = emptyList()
            steps = emptyList()
            totalDist = 0.0
        }

        val (brg, dist0) = bearingAndDistance(curLat, curLng, destLat, destLng)
        curBearing = brg
        // lastGpsTime 保持 0：首帧即进入推算，直到收到真实 GPS 更新
        running = true
        _state.value = NaviEngine.NaviState(
            running = true,
            latitude = curLat,
            longitude = curLng,
            destName = destName,
            destLatitude = destLat,
            destLongitude = destLng,
            bearing = curBearing,
            pathRemain = dist0.toInt(),
            stepRemain = dist0.toInt(),
            etaSeconds = etaSeconds(dist0),
            onRoute = cumulative.isNotEmpty()
        )
        requestLocationUpdates()
        handler.post(tick)
    }

    override fun overrideIcon(iconType: Int) {
        manualIcon = iconType
        _state.value = _state.value.copy(iconType = iconType)
    }

    override fun setSpeed(kmh: Float) {
        val v = kmh.coerceIn(NaviEngine.MIN_SPEED_KMH, NaviEngine.MAX_SPEED_KMH)
        _state.value = _state.value.copy(speedKmh = v)
    }

    override fun stop() {
        running = false
        manualIcon = null
        handler.removeCallbacks(tick)
        stopLocationUpdates()
        _state.value = _state.value.copy(running = false)
    }

    override fun release() {
        stop()
        onStepChanged = null
    }

    // ---------- 路线构建 ----------

    /** 展平所有步骤折线为累计点列，并记录每步结束的累计距离 */
    private fun buildRoute(route: RouteData) {
        steps = route.steps
        val points = ArrayList<CumPoint>(1024)
        var acc = 0.0
        var prevLat = Double.NaN
        var prevLng = Double.NaN
        val ends = ArrayList<Double>(route.steps.size)
        for (step in route.steps) {
            for ((lat, lng) in step.polyline) {
                if (lat == prevLat && lng == prevLng) continue
                if (!prevLat.isNaN()) {
                    val (_, d) = bearingAndDistance(prevLat, prevLng, lat, lng)
                    acc += d
                }
                points.add(CumPoint(lat, lng, acc))
                prevLat = lat
                prevLng = lng
            }
            ends.add(acc)
        }
        cumulative = points
        stepEndDist = ends
        // 以路线几何距离校正总里程（折线累加通常略大于官方口径）
        totalDist = if (points.isNotEmpty()) points.last().dist else route.distanceM.toDouble()
    }

    // ---------- 每 tick 推演 ----------

    private fun stepOnce() {
        val s = _state.value
        if (!s.running) return

        val gpsFresh = System.currentTimeMillis() - lastGpsTime < NaviEngine.GPS_STALE_MS
        val stepM = s.speedKmh * 1000f / 3600f * (NaviEngine.TICK_MS / 1000f)

        if (cumulative.isNotEmpty()) {
            // ---------- 路线模式 ----------
            if (gpsFresh) {
                lastIndex = nearestIndex(curLat, curLng, lastIndex)
            } else {
                // GPS 失效：沿路线从当前投影点前进 stepM
                lastIndex = advanceAlongRoute(lastIndex, stepM.toDouble())
                val p = cumulative[lastIndex]
                curLat = p.lat
                curLng = p.lng
            }
            val traveled = cumulative[lastIndex].dist
            val remaining = (totalDist - traveled).coerceAtLeast(0.0)
            val arrived = remaining <= NaviEngine.ARRIVE_THRESHOLD_M

            val (curStep, stepRemain) = currentStep(traveled)
            curBearing = bearingAt(lastIndex)

            val icon = when {
                arrived -> IconType.ARRIVED_DESTINATION
                manualIcon != null -> manualIcon!!
                curStep >= 0 -> IconMapper.of(
                    steps[curStep].action,
                    steps[curStep].orientation,
                    steps[curStep].instruction
                )
                else -> IconType.STRAIGHT
            }

            // 步骤切换 → TTS 播报
            if (!arrived && curStep >= 0 && curStep != s.stepIndex) {
                val ins = steps[curStep].instruction.ifBlank { steps[curStep].action }
                if (ins.isNotBlank()) onStepChanged?.invoke(ins)
            }

            _state.value = s.copy(
                running = !arrived,
                arrived = arrived,
                latitude = curLat,
                longitude = curLng,
                pathRemain = remaining.toInt(),
                stepRemain = stepRemain.toInt(),
                stepIndex = curStep,
                stepInstruction = if (curStep >= 0) steps[curStep].instruction else "",
                iconType = icon,
                bearing = curBearing,
                etaSeconds = if (arrived) 0 else etaSeconds(remaining),
                gpsActive = gpsFresh,
                traveled = traveled.toInt(),
                speedKmh = s.speedKmh,
                onRoute = true
            )

            if (arrived) {
                running = false
                handler.removeCallbacks(tick)
                stopLocationUpdates()
            }
        } else {
            // ---------- 直线演示模式（无规划路线） ----------
            val (brg, dist) = bearingAndDistance(curLat, curLng, s.destLatitude, s.destLongitude)
            if (!gpsFresh && dist > stepM) {
                val (nLat, nLng) = destinationPoint(curLat, curLng, brg, stepM.toDouble())
                curLat = nLat
                curLng = nLng
                curBearing = brg
            }
            val (_, dist2) = bearingAndDistance(curLat, curLng, s.destLatitude, s.destLongitude)
            val arrived = dist2 <= NaviEngine.ARRIVE_THRESHOLD_M
            val icon = when {
                arrived -> IconType.ARRIVED_DESTINATION
                manualIcon != null -> manualIcon!!
                dist2 < 100 -> autoTurnIcon(brg, curBearing)
                else -> IconType.STRAIGHT
            }
            val newTraveled = s.traveled + if (gpsFresh) 0 else stepM.toInt()

            _state.value = s.copy(
                running = !arrived,
                arrived = arrived,
                latitude = curLat,
                longitude = curLng,
                pathRemain = dist2.toInt(),
                stepRemain = dist2.toInt(),
                iconType = icon,
                bearing = curBearing,
                etaSeconds = if (arrived) 0 else etaSeconds(dist2),
                gpsActive = gpsFresh,
                traveled = newTraveled
            )
            if (arrived) {
                running = false
                handler.removeCallbacks(tick)
                stopLocationUpdates()
            }
        }
    }

    // ---------- 路线几何 ----------

    /** 最近点索引：优先在 lastIndex 附近窗口查找，偏离过远时全量扫描 */
    private fun nearestIndex(lat: Double, lng: Double, from: Int): Int {
        if (cumulative.isEmpty()) return 0
        val window = 200
        val lo = (from - window).coerceAtLeast(0)
        val hi = (from + window).coerceAtMost(cumulative.size - 1)
        var bestIdx = from.coerceIn(cumulative.indices)
        var bestD = Double.MAX_VALUE
        var i = lo
        while (i <= hi) {
            val p = cumulative[i]
            val (_, d) = bearingAndDistance(lat, lng, p.lat, p.lng)
            if (d < bestD) {
                bestD = d
                bestIdx = i
            }
            i++
        }
        // 窗口内最近点距离 > 80m：GPS 跳变，全量重定位
        if (bestD > 80.0) {
            bestD = Double.MAX_VALUE
            for (k in cumulative.indices) {
                val p = cumulative[k]
                val (_, d) = bearingAndDistance(lat, lng, p.lat, p.lng)
                if (d < bestD) {
                    bestD = d
                    bestIdx = k
                }
            }
        }
        return bestIdx
    }

    /** 沿路线前进 dist 米，返回新索引（线性插值更新位置） */
    private fun advanceAlongRoute(from: Int, dist: Double): Int {
        if (cumulative.isEmpty()) return 0
        val target = cumulative[from].dist + dist
        var i = from
        while (i < cumulative.size - 1 && cumulative[i + 1].dist < target) i++
        if (i < cumulative.size - 1) {
            val a = cumulative[i]
            val b = cumulative[i + 1]
            val seg = b.dist - a.dist
            if (seg > 0.01) {
                val t = ((target - a.dist) / seg).coerceIn(0.0, 1.0)
                curLat = a.lat + (b.lat - a.lat) * t
                curLng = a.lng + (b.lng - a.lng) * t
            }
        }
        return i
    }

    /** 当前所在步骤索引与距下一转弯剩余距离 */
    private fun currentStep(traveled: Double): Pair<Int, Double> {
        if (stepEndDist.isEmpty()) return -1 to 0.0
        for (i in stepEndDist.indices) {
            if (traveled < stepEndDist[i]) {
                return i to (stepEndDist[i] - traveled).coerceAtLeast(0.0)
            }
        }
        val last = stepEndDist.size - 1
        return last to 0.0
    }

    /** 路线在 idx 处的航向（朝向下一点） */
    private fun bearingAt(idx: Int): Float {
        if (cumulative.isEmpty()) return curBearing
        val a = cumulative[idx.coerceIn(cumulative.indices)]
        val b = cumulative[(idx + 1).coerceAtMost(cumulative.size - 1)]
        if (a.lat == b.lat && a.lng == b.lng) return curBearing
        val (brg, _) = bearingAndDistance(a.lat, a.lng, b.lat, b.lng)
        return brg
    }

    // ---------- 定位 ----------

    private fun requestLocationUpdates() {
        val lm = locationManager ?: return
        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, NaviEngine.TICK_MS, 1f, locationListener)
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NaviEngine.TICK_MS, 1f, locationListener)
        } catch (_: Exception) {
            // 无位置提供者时静默降级为航位推算
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val lm = locationManager ?: return null
        return try {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 直线模式辅助 ----------

    /** 距终点 <100m：按"目标方位 vs 当前航向"偏差推转向（演示模式） */
    private fun autoTurnIcon(targetBearing: Float, currentBearing: Float): Int {
        var diff = (targetBearing - currentBearing + 360f) % 360f
        if (diff > 180f) diff -= 360f
        return when {
            abs(diff) < 25f -> IconType.STRAIGHT
            diff >= 25f && diff < 60f -> IconType.RIGHT_FRONT
            diff > 120f -> IconType.LEFT_TURN_AROUND
            diff > 60f -> IconType.RIGHT
            diff <= -25f && diff > -60f -> IconType.LEFT_FRONT
            diff < -60f -> IconType.LEFT
            else -> IconType.STRAIGHT
        }
    }

    private fun etaSeconds(distM: Double): Int {
        val v = _state.value.speedKmh.coerceAtLeast(5f)
        return (distM / (v * 1000 / 3600)).toInt()
    }

    // ---------- 球面几何 ----------

    /** 返回 (方位角度, 距离米) */
    private fun bearingAndDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Pair<Float, Double> {
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        var brg = Math.toDegrees(atan2(y, x))
        if (brg < 0) brg += 360.0
        val dLat = rLat2 - rLat1
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        val dist = 6371000.0 * 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return brg.toFloat() to dist
    }

    /** 从 (lat,lng) 沿 bearing 前进 dist 米后的坐标（直线演示模式用） */
    private fun destinationPoint(lat: Double, lng: Double, bearingDeg: Float, distM: Double): Pair<Double, Double> {
        val rLat = Math.toRadians(lat)
        val rLng = Math.toRadians(lng)
        val brg = Math.toRadians(bearingDeg.toDouble())
        val d = distM / 6371000.0
        val nLat = Math.asin(sin(rLat) * cos(d) + cos(rLat) * sin(d) * cos(brg))
        val nLng = rLng + atan2(sin(brg) * sin(d) * cos(rLat), cos(d) - sin(rLat) * sin(nLat))
        return Math.toDegrees(nLat) to Math.toDegrees(nLng)
    }
}
