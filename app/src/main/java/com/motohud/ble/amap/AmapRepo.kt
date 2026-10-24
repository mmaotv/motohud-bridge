package com.motohud.ble.amap

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.route.DrivePath
import com.amap.api.services.route.DriveStep
import com.amap.api.services.route.RouteSearch
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteStep
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** POI 提示结果（搜索建议） */
data class TipItem(
    val name: String,
    val address: String,
    val district: String,
    val latitude: Double,
    val longitude: Double
)

/** 路线规划结果 */
sealed class RoutePlanResult {
    data class Success(val route: RouteData) : RoutePlanResult()
    data class Failure(val message: String) : RoutePlanResult()
}

/** POI 提示结果（区分"无结果"与"服务报错"，便于提示 Key/权限问题） */
sealed class TipsResult {
    data class Ok(val items: List<TipItem>) : TipsResult()
    data class Err(val code: Int) : TipsResult()
}

/**
 * 高德搜索封装：POI 输入提示 + 驾车路线规划。
 *
 * 调用方需先通过 [AmapManager.initSearch] 完成 Key 与隐私合规。
 * 所有方法均为挂起函数，内部使用 SDK 的异步回调。
 */
class AmapRepo(private val context: Context) {

    /**
     * POI 输入提示（专业导航软件同款搜索体验）。
     * @param city 城市名或 adcode；空串 = 全国范围
     */
    suspend fun searchTips(keyword: String, city: String = ""): TipsResult {
        if (keyword.isBlank()) return TipsResult.Ok(emptyList())
        return suspendCancellableCoroutine { cont ->
            try {
                val query = InputtipsQuery(keyword.trim(), city).apply {
                    setCityLimit(false)
                }
                val inputtips = Inputtips(context, query)
                inputtips.setInputtipsListener { tips, rCode ->
                    if (rCode == 1000) {
                        cont.resume(TipsResult.Ok(tips.orEmpty().mapNotNull { it.toItem() }))
                    } else {
                        // 10001 INVALID_USER_KEY / 10009 未开通服务 等，需要用户检查 Key
                        cont.resume(TipsResult.Err(rCode))
                    }
                }
                inputtips.requestInputtipsAsyn()
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * 驾车路线规划。
     *
     * @param strategy 高德驾车策略（`RouteSearch.Driving*` 常量）
     * @param modeLabel 写进结果的模式描述，供界面显示
     */
    suspend fun planDriveRoute(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        strategy: Int = RouteSearch.DrivingDefault,
        modeLabel: String = ""
    ): RoutePlanResult {
        return suspendCancellableCoroutine { cont ->
            try {
                val routeSearch = RouteSearch(context)
                val fromAndTo = RouteSearch.FromAndTo(
                    LatLonPoint(fromLat, fromLng),
                    LatLonPoint(toLat, toLng)
                )
                val query = RouteSearch.DriveRouteQuery(
                    fromAndTo,
                    strategy,
                    null,
                    null,
                    ""
                )
                routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                    override fun onDriveRouteSearched(result: com.amap.api.services.route.DriveRouteResult?, rCode: Int) {
                        val path: DrivePath? = result?.paths?.firstOrNull()
                        if (rCode == 1000 && path != null) {
                            cont.resume(RoutePlanResult.Success(path.toRouteData(modeLabel)))
                        } else {
                            cont.resume(
                                RoutePlanResult.Failure(
                                    when (rCode) {
                                        1002 -> "网络异常，请检查网络后重试"
                                        1003 -> "搜索服务返回异常，请稍后重试"
                                        1804 -> "起点与终点距离过近"
                                        1806 -> "起点或终点不在可行区域"
                                        else -> "路线规划失败（错误码 $rCode）"
                                    }
                                )
                            )
                        }
                    }

                    override fun onBusRouteSearched(
                        result: com.amap.api.services.route.BusRouteResult?,
                        rCode: Int
                    ) = Unit

                    override fun onWalkRouteSearched(
                        result: com.amap.api.services.route.WalkRouteResult?,
                        rCode: Int
                    ) = Unit

                    override fun onRideRouteSearched(
                        result: com.amap.api.services.route.RideRouteResult?,
                        rCode: Int
                    ) = Unit
                })
                routeSearch.calculateDriveRouteAsyn(query)
            } catch (e: Exception) {
                cont.resume(RoutePlanResult.Failure(e.message ?: "路线规划异常"))
            }
        }
    }

    /**
     * 摩托车可达路网近似算路。
     *
     * 高德搜索 SDK 没有摩托车策略（官方摩托算路只在收费的导航 SDK 里），
     * 这里用「躲避拥堵 + 不走快速路」的驾车策略来近似：国内禁摩/限行主要针对
     * 快速路、高架、隧道与城市主干道，绕开快速路能规避掉大部分硬禁区。
     */
    suspend fun planMotorcycleRoute(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        avoidHighway: Boolean = true
    ): RoutePlanResult {
        val strategy = if (avoidHighway) {
            // 不走高速 + 躲避拥堵 + 避免收费：最贴近摩托车可达路网
            RouteSearch.DrivingNoHighAvoidCongestionSaveMoney
        } else {
            RouteSearch.DrivingAvoidCongestion
        }
        val label = if (avoidHighway) "摩托车 · 避开高速/快速路" else "摩托车 · 避开拥堵"
        return planDriveRoute(fromLat, fromLng, toLat, toLng, strategy, label)
    }

    // ---------- 转换 ----------
    private fun Tip.toItem(): TipItem? {
        val p = point ?: return null
        return TipItem(
            name = name.orEmpty(),
            address = address.orEmpty(),
            district = district.orEmpty(),
            latitude = p.latitude,
            longitude = p.longitude
        )
    }

    private fun DrivePath.toRouteData(modeLabel: String = ""): RouteData {
        val steps = this.steps.orEmpty().map { it.toRouteStep() }
        return RouteData(
            distanceM = distance,
            durationS = duration.toFloat(),
            steps = steps,
            modeLabel = modeLabel
        )
    }

    private fun DriveStep.toRouteStep(): RouteStep {
        return RouteStep(
            instruction = instruction.orEmpty(),
            action = action.orEmpty(),
            orientation = orientation.orEmpty(),
            road = road.orEmpty(),
            distanceM = distance,
            polyline = polyline.orEmpty().map { it.latitude to it.longitude }
        )
    }
}
