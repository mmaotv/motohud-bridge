package com.motohud.ble

import android.app.Application
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.AppDatabase
import com.motohud.ble.data.FaultRepository
import com.motohud.ble.data.SearchEngine
import com.motohud.ble.data.bike.BikePrefs
import com.motohud.ble.navi.NaviForwarder
import com.motohud.ble.navi.RouteNaviEngine

class MotoHudApp : Application() {

    val db by lazy { AppDatabase.get(this) }
    val searchEngine by lazy { SearchEngine(db.searchHistoryDao()) }
    val savedPlaceDao by lazy { db.savedPlaceDao() }
    val bleManager by lazy { BleConnectionManager(this) }
    val faultRepository get() = FaultRepository

    /** 全局单例导航引擎（手机导航页与车机投屏 HUD 共享同一实例） */
    val naviEngine: RouteNaviEngine by lazy {
        RouteNaviEngine(getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager)
    }

    /** BLE 导航指令转发器（保证同一时刻只有一个界面在发指令） */
    val naviForwarder: NaviForwarder by lazy { NaviForwarder(bleManager) }

    override fun onCreate() {
        super.onCreate()
        // 恢复上次选择的车型
        BikePrefs.init(this)
        // 已同意隐私政策时预热高德 SDK（未同意则等引导页授权后再初始化）
        if (AmapManager.isPrivacyAgreed(this) && AmapManager.hasKey(this)) {
            AmapManager.initMap(this)
            AmapManager.initSearch(this)
            AmapManager.initLocation(this)
        }
    }
}
