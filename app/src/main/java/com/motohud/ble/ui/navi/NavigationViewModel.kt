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

    private val _locationLabel = MutableStateFlow("")
    val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

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

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val (lat, lng) = CoordConverter.wgs84ToGCJ(location.latitude, location.longitude)
            currentLocation = lat to lng
            _locationLabel.value = "当前位置：${CoordConverter.formatLatLng(lat, lng)}"
        }

        @Deprecated("旧版回调保留兼容")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        refreshLocation()
    }

    /** 请求定位更新（已有权限时注册） */
    @Suppress("MissingPermission")
    fun refreshLocation() {
        try {
            val last = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last?.let {
                val (lat, lng) = CoordConverter.wgs84ToGCJ(it.latitude, it.longitude)
                currentLocation = lat to lng
                _locationLabel.value = "当前位置：${CoordConverter.formatLatLng(lat, lng)}"
            }
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener
            )
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener
            )
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
     * 规划驾车路线。起点用当前定位；未获得定位时返回失败。
     */
    suspend fun planRoute(toLat: Double, toLng: Double): RoutePlanResult {
        val from = currentLocation
            ?: return RoutePlanResult.Failure("尚未获取到当前位置，请检查定位权限后重试")
        if (!amapReady) return RoutePlanResult.Failure("KEY_MISSING")
        AmapManager.initSearch(getApplication())
        return amapRepo.planDriveRoute(from.first, from.second, toLat, toLng)
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
