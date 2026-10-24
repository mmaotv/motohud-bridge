package com.motohud.ble.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 导航偏好（跨会话持久化）。
 *
 * ## 关于「摩托车专用导航」
 *
 * 高德确实有摩托车路径规划，但**两条路都走不通**：
 *  1. 导航 SDK（AMapNavi）的 `PathPlanningStrategy.MOTOR_MULTIPLE_*` 需要单独申请
 *     导航 SDK 授权，且官方文档明确写着「摩托车路径规划是收费接口，需通过工单
 *     提交商务合作类工单，否则默认无法算路成功」；
 *  2. 搜索 SDK（我们用的这个，免费的）`RouteSearch` 只有驾车/步行/骑行/公交，
 *     **没有任何摩托车策略**。
 *
 * 所以本 App 的做法是「摩托车可达路网近似」：
 *  - 用驾车算路，但策略固定为 **躲避拥堵 + 不走快速路**（`DrivingAvoidCongestionNoExpressways`）。
 *    国内绝大多数城市的禁摩/限行是针对快速路、高架、隧道和城市主干道，
 *    不走快速路能规避掉大部分硬禁区，这也是骑行类 App 的通用做法；
 *  - 同时提供「避开高速」开关（摩托车上高速在多数省份违法）；
 *  - 导航页常驻提示「以现场禁摩标志为准」，不做虚假承诺。
 *
 * 如果将来拿到导航 SDK 的商务授权，只要在这里把 `motorcycle` 映射到
 * `MOTOR_MULTIPLE_DEFAULT` 即可，上层无需改动。
 */
object NaviPrefs {

    private const val PREFS = "navi_prefs"
    private const val KEY_MOTORCYCLE = "motorcycle_mode"
    private const val KEY_AVOID_HIGHWAY = "avoid_highway"
    private const val KEY_VOICE = "voice_on"
    private const val KEY_PHONE_MODE = "phone_mode"

    private lateinit var prefs: SharedPreferences

    private val _motorcycle = MutableStateFlow(true)
    val motorcycle: StateFlow<Boolean> = _motorcycle.asStateFlow()

    private val _avoidHighway = MutableStateFlow(true)
    val avoidHighway: StateFlow<Boolean> = _avoidHighway.asStateFlow()

    private val _voice = MutableStateFlow(true)
    val voice: StateFlow<Boolean> = _voice.asStateFlow()

    /** 手机导航模式（true=竖屏手机导航界面，false=地图+投屏卡片） */
    private val _phoneMode = MutableStateFlow(true)
    val phoneMode: StateFlow<Boolean> = _phoneMode.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _motorcycle.value = prefs.getBoolean(KEY_MOTORCYCLE, true)
        _avoidHighway.value = prefs.getBoolean(KEY_AVOID_HIGHWAY, true)
        _voice.value = prefs.getBoolean(KEY_VOICE, true)
        _phoneMode.value = prefs.getBoolean(KEY_PHONE_MODE, true)
    }

    fun setMotorcycle(on: Boolean) {
        _motorcycle.value = on
        prefs.edit().putBoolean(KEY_MOTORCYCLE, on).apply()
    }

    fun setAvoidHighway(on: Boolean) {
        _avoidHighway.value = on
        prefs.edit().putBoolean(KEY_AVOID_HIGHWAY, on).apply()
    }

    fun setVoice(on: Boolean) {
        _voice.value = on
        prefs.edit().putBoolean(KEY_VOICE, on).apply()
    }

    fun setPhoneMode(on: Boolean) {
        _phoneMode.value = on
        prefs.edit().putBoolean(KEY_PHONE_MODE, on).apply()
    }
}
