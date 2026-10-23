package com.motohud.ble.data.bike

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前选中车型的持久化 + 订阅。
 *
 * 首次连接车机时按蓝牙名自动匹配一次（[autoMatched] 为 false 时生效），
 * 之后以用户手动选择为准，不再被覆盖。
 */
object BikePrefs {

    private const val PREFS = "bike_prefs"
    private const val KEY_ID = "selected_id"
    private const val KEY_AUTO = "auto_matched"

    private val _current = MutableStateFlow(BikeCatalog.DEFAULT)
    val current: StateFlow<BikeModel> = _current.asStateFlow()

    fun init(context: Context) {
        _current.value = BikeCatalog.byId(prefs(context).getString(KEY_ID, null))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun select(context: Context, model: BikeModel) {
        _current.value = model
        prefs(context).edit()
            .putString(KEY_ID, model.id)
            .putBoolean(KEY_AUTO, true)
            .apply()
    }

    /** 已做过自动匹配（或用户已手动选择） */
    fun isAutoMatched(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    /**
     * 连接车机后调用：若还没定过车型，则按设备名猜一个。
     * @return 猜测到的车型；已经定过或猜不出时返回 null
     */
    fun autoMatchOnce(context: Context, deviceName: String?): BikeModel? {
        if (isAutoMatched(context)) return null
        val guess = BikeCatalog.guessByDevice(deviceName) ?: return null
        _current.value = guess
        prefs(context).edit()
            .putString(KEY_ID, guess.id)
            .putBoolean(KEY_AUTO, true)
            .apply()
        return guess
    }
}
