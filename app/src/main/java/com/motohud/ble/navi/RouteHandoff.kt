package com.motohud.ble.navi

/**
 * 路线交接：路线选择页 → 导航页。
 *
 * 为什么不走 Intent：一条路线的几何点是几千个 (lat,lng)，塞进 Intent 会触发
 * TransactionTooLargeException（1MB 上限）。这里用一个进程内暂存，
 * 导航页取出时校验目的地是否一致，避免"选 A 路线却导航到 B"。
 */
object RouteHandoff {

    @Volatile
    private var route: RouteData? = null
    private var destName: String = ""
    private var destLat: Double = 0.0
    private var destLng: Double = 0.0

    fun put(name: String, lat: Double, lng: Double, r: RouteData) {
        destName = name
        destLat = lat
        destLng = lng
        route = r
    }

    /** 取出与目的地匹配的路线；不匹配或已被消费则返回 null */
    fun take(name: String, lat: Double, lng: Double): RouteData? {
        val r = route ?: return null
        if (destName != name) return null
        if (Math.abs(destLat - lat) > 1e-6 || Math.abs(destLng - lng) > 1e-6) return null
        route = null
        return r
    }

    fun clear() {
        route = null
    }
}
