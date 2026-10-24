package com.motohud.ble.ui.navi

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.motohud.ble.R
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.ui.common.MotoDialog

/**
 * 第三方地图交接对话框。
 *
 * 用途：在「路线选择」页把已经确定的目的地交给高德 / 百度 / 腾讯 / 系统地图继续导航。
 *
 * 为什么只列第三方地图：
 *  - 「手机导航」= 本 App 的 [GuidanceActivity]，由路线页的「开始导航」直接进；
 *  - 「蓝牙投屏导航」= [HudActivity]，由 GuidanceActivity 的「显示方式」对话框切换；
 *  这两个已经在链路里了，在这里重复列出只会让人分不清区别。
 *
 * 为什么放在「路线选择」页而不是导航页：
 *  交接给第三方地图必须带明确坐标，导航页此时还没选目的地，
 *  只能拿当前位置凑数，交接过去没有意义。
 */
class MapChoiceDialog : DialogFragment() {

    private var lat = 0.0
    private var lng = 0.0
    private var name = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        lat = arguments?.getDouble("lat", 0.0) ?: 0.0
        lng = arguments?.getDouble("lng", 0.0) ?: 0.0
        name = arguments?.getString("name").orEmpty()

        data class Choice(val label: String, val action: () -> Unit)

        val choices = mutableListOf<Choice>()

        if (isInstalled("com.autonavi.minimap")) {
            choices += Choice(getString(R.string.map_amap)) {
                val uri = Uri.parse(
                    "androidamap://navi?sourceApplication=${getString(R.string.app_name)}" +
                        "&lat=$lat&lon=$lng&keywords=${Uri.encode(name)}&dev=0&style=2"
                )
                startSafe(Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.autonavi.minimap")
                }, getString(R.string.map_amap))
            }
        }

        if (isInstalled("com.baidu.BaiduMap")) {
            choices += Choice(getString(R.string.map_baidu)) {
                val (bdLat, bdLng) = CoordConverter.gcj2BD(lat, lng)
                val uri = Uri.parse(
                    "baidumap://map/direction?destination=latlng:$bdLat,$bdLng|name:" +
                        "${Uri.encode(name)}&mode=driving&src=${getString(R.string.app_name)}"
                )
                startSafe(Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.baidu.BaiduMap")
                }, getString(R.string.map_baidu))
            }
        }

        if (isInstalled("com.tencent.map")) {
            choices += Choice(getString(R.string.map_tencent)) {
                val uri = Uri.parse(
                    "qqmap://map/routeplan?type=drive&tocoord=$lat,$lng&to=${Uri.encode(name)}"
                )
                startSafe(Intent(Intent.ACTION_VIEW, uri), getString(R.string.map_tencent))
            }
        }

        // 系统地图兜底（geo:）
        choices += Choice(getString(R.string.map_system)) {
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
            startSafe(Intent(Intent.ACTION_VIEW, uri), getString(R.string.map_system))
        }

        val labels = choices.map { it.label }.toTypedArray()
        return MotoDialog.builder(requireContext())
            .setTitle(R.string.route_select_other_map)
            .setItems(labels) { _, which ->
                choices.getOrNull(which)?.action?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    private fun isInstalled(pkg: String): Boolean =
        requireContext().packageManager.getLaunchIntentForPackage(pkg) != null

    private fun startSafe(intent: Intent, label: String) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "无法打开$label", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun newInstance(name: String, lat: Double, lng: Double): MapChoiceDialog =
            MapChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString("name", name)
                    putDouble("lat", lat)
                    putDouble("lng", lng)
                }
            }
    }
}
