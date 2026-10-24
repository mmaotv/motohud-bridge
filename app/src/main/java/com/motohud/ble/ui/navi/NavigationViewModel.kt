package com.motohud.ble.ui.navi

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motohud.ble.MotoHudApp
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.amap.TipItem
import com.motohud.ble.data.SavedPlaceEntity
import com.motohud.ble.navi.CoordConverter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 导航页 ViewModel：定位 / 常用地址 / POI 搜索 / 路线规划。
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as MotoHudApp).savedPlaceDao
    private val locationManager =
        app.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
    private val amapRepo = AmapRepo(app)

    /** 常用地址列表（Flow 自动更新） */
    val places: StateFlow<List<SavedPlaceEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前定位（GCJ02 纬度, 经度），null = 未获取 */
    var currentLocation: Pair<Double, Double>? = null
        private set

    /**
     * 当前位置流（GCJ02 纬度, 经度 + 精度）。
     * 地图上的定位点要跟着它动，所以必须是可观察的。
     */
    private val _locationFlow = MutableStateFlow<LocationFix?>(null)
    val locationFlow: StateFlow<LocationFix?> = _locationFlow.asStateFlow()

    /** 一次定位结果 */
    data class LocationFix(
        val lat: Double,
        val lng: Double,
        val accuracy: Float
    )

    private val _locationLabel = MutableStateFlow("")
    val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

    /** 正在定位（界面显示进度） */
    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    /** 当前定位精度（米），<=0 表示未知 */
    private val _accuracy = MutableStateFlow(0f)
    val accuracy: StateFlow<Float> = _accuracy.asStateFlow()

    /** POI 搜索结果 */
    private val _tips = MutableStateFlow<List<TipItem>>(emptyList())
    val tips: StateFlow<List<TipItem>> = _tips.asStateFlow()

    private val _tipsState = MutableStateFlow(TipsState.IDLE)
    val tipsState: StateFlow<TipsState> = _tipsState.asStateFlow()

    /** 搜索失败时的服务错误码（10001=Key 无效，10009=未开通服务…） */
    private val _tipsErrorCode = MutableStateFlow(0)
    val tipsErrorCode: StateFlow<Int> = _tipsErrorCode.asStateFlow()

    enum class TipsState { IDLE, LOADING, DONE, EMPTY, ERROR }

    /** 高德功能是否可用（Key + 隐私合规） */
    val amapReady: Boolean
        get() = AmapManager.hasKey(getApplication()) && AmapManager.isPrivacyAgreed(getApplication())

    private var tipsJob: Job? = null

    private val locator = com.motohud.ble.amap.AmapLocator(app)

    private var locatingJob: Job? = null
    private var systemUpdatesOn = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 系统定位只作兜底：高德定位已经拿到值时不再覆盖（两者坐标基准不同）
            if (currentLocation != null && !systemUpdatesOn) return
            val (lat, lng) = CoordConverter.wgs84ToGCJ(location.latitude, location.longitude)
            currentLocation = lat to lng
            _accuracy.value = location.accuracy
            _locationFlow.value = LocationFix(lat, lng, location.accuracy)
            _locationLabel.value = "当前位置 · ${CoordConverter.formatLatLng(lat, lng)}"
        }

        @Deprecated("旧版回调保留兼容")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        refreshLocation()
    }

    /**
     * 刷新当前位置。
     *
     * 旧实现只有"读系统最后已知位置"一条路，而 `getLastKnownLocation` 拿到的是
     * **系统缓存**，可能是几个小时前、甚至别的城市的位置，于是出现
     * "人在重庆，导航页却一直显示北京"。
     *
     * 现在的顺序：
     *  1. 系统缓存只作"占位"，且必须足够新（90 秒内），否则丢弃；
     *  2. 主动向高德定位 SDK 要一次**新鲜**定位（不走缓存），拿到后覆盖；
     *  3. 高德不可用 / 超时，才退回系统持续定位。
     *
     * @param force true = 用户点了刷新按钮，忽略"已在定位中"的拦截
     */
    fun refreshLocation(force: Boolean = false) {
        seedFromSystem()
        if (locatingJob?.isActive == true && !force) return
        locatingJob?.cancel()
        locatingJob = viewModelScope.launch {
            _locating.value = true
            val fix = runCatching { locator.locateOnce() }.getOrNull()
            _locating.value = false
            if (fix != null && !fix.isCache) {
                currentLocation = fix.lat to fix.lng
                _accuracy.value = fix.accuracy
                _locationFlow.value = LocationFix(fix.lat, fix.lng, fix.accuracy)
                _locationLabel.value = buildLabel(fix)
            } else if (currentLocation == null) {
                // 高德拿不到（无 Key / 无网络）→ 系统定位持续更新
                startSystemUpdates()
            }
        }
    }

    private fun buildLabel(fix: com.motohud.ble.amap.AmapLocator.Fix): String {
        val addr = fix.address?.trim().orEmpty()
        return if (addr.isNotEmpty()) {
            "当前位置 · $addr"
        } else {
            "当前位置 · ${CoordConverter.formatLatLng(fix.lat, fix.lng)}"
        }
    }

    /** 用系统最后已知位置占位；超过 90 秒的缓存直接丢弃 */
    @Suppress("MissingPermission")
    private fun seedFromSystem() {
        if (!hasLocationPermission()) return
        try {
            val best = listOfNotNull(
                locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ).maxByOrNull { it.time } ?: return
            val ageMs = System.currentTimeMillis() - best.time
            if (ageMs > 90_000L) return
            val (lat, lng) = CoordConverter.wgs84ToGCJ(best.latitude, best.longitude)
            currentLocation = lat to lng
            _accuracy.value = best.accuracy
            _locationFlow.value = LocationFix(lat, lng, best.accuracy)
            _locationLabel.value = "当前位置 · ${CoordConverter.formatLatLng(lat, lng)}"
        } catch (_: Exception) {
        }
    }

    /** 注册系统定位更新（仅在高德定位不可用时） */
    @Suppress("MissingPermission")
    private fun startSystemUpdates() {
        if (systemUpdatesOn || !hasLocationPermission()) return
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener
            )
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener
            )
            systemUpdatesOn = true
        } catch (_: Exception) {
        }
    }

    /** 是否有定位权限 */
    fun hasLocationPermission(): Boolean {
        val ctx = getApplication<MotoHudApp>()
        return listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    // ---------- POI 搜索 ----------

    /** POI 输入提示（取消上一次未完成的请求） */
    fun searchTips(keyword: String) {
        tipsJob?.cancel()
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            _tips.value = emptyList()
            _tipsState.value = TipsState.IDLE
            return
        }
        if (!amapReady) {
            _tipsState.value = TipsState.ERROR
            return
        }
        _tipsState.value = TipsState.LOADING
        tipsJob = viewModelScope.launch {
            val result = runCatching { amapRepo.searchTips(kw) }
            result.onSuccess { r ->
                when (r) {
                    is com.motohud.ble.amap.TipsResult.Ok -> {
                        _tips.value = r.items
                        _tipsState.value = if (r.items.isEmpty()) TipsState.EMPTY else TipsState.DONE
                    }
                    is com.motohud.ble.amap.TipsResult.Err -> {
                        _tips.value = emptyList()
                        _tipsErrorCode.value = r.code
                        _tipsState.value = TipsState.ERROR
                    }
                }
            }.onFailure {
                _tipsErrorCode.value = -1
                _tipsState.value = TipsState.ERROR
            }
        }
    }

    fun clearTips() {
        tipsJob?.cancel()
        _tips.value = emptyList()
        _tipsState.value = TipsState.IDLE
    }

    // ---------- 路线规划 ----------

    /**
     * 规划路线。起点用当前定位；未获得定位时返回失败。
     * 策略由 [com.motohud.ble.data.NaviPrefs.strategy] 按「路线偏好」组合得出。
     */
    suspend fun planRoute(toLat: Double, toLng: Double): RoutePlanResult {
        val from = currentLocation
            ?: return RoutePlanResult.Failure("尚未获取到当前位置，请检查定位权限后重试")
        if (!amapReady) return RoutePlanResult.Failure("地图服务不可用")
        AmapManager.initSearch(getApplication())
        return amapRepo.planDriveRoute(
            from.first, from.second, toLat, toLng,
            com.motohud.ble.data.NaviPrefs.strategy(),
            com.motohud.ble.data.NaviPrefs.summary()
        )
    }

    // ---------- 常用地址 ----------

    fun addPlace(name: String, lat: Double, lng: Double, type: String, remark: String) {
        viewModelScope.launch {
            dao.insert(
                SavedPlaceEntity(
                    name = name,
                    latitude = lat,
                    longitude = lng,
                    type = type,
                    remark = remark
                )
            )
        }
    }

    fun updatePlace(place: SavedPlaceEntity) {
        viewModelScope.launch { dao.update(place) }
    }

    fun deletePlace(place: SavedPlaceEntity) {
        viewModelScope.launch { dao.delete(place) }
    }

    override fun onCleared() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
