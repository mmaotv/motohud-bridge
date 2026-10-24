package com.motohud.ble.ui.navi

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.motohud.ble.R
import com.motohud.ble.navi.CoordConverter

/**
 * 地图选择对话框（复刻原 App NaviUtil.mapNavi，并补上"手机导航"）。
 *
 * 选项顺序：
 *  0. 手机导航（本机）→ 本 App 竖屏全屏导航页 GuidanceActivity（同时向车机推流）
 *  1. 蓝牙投屏导航 → 启动 HUD（横屏大字 + BLE 转发）
 *  2. 高德地图   androidamap://navi?...
 *  3. 百度地图   baidumap://map/direction?...（GCJ02 → BD09 转换后跳转）
 *  4. 腾讯地图   qqmap://map/routeplan?...
 *  5. 系统地图   geo: intent 兜底
 */
class MapChoiceDialog : DialogFragment() {

    /** 选择"蓝牙投屏导航"回调 */
    var onBleNavi: (() -> Unit)? = null

    /** 选择"手机导航（本机）"回调 */
    var onPhoneNavi: (() -> Unit)? = null

    private var lat = 0.0
    private var lng = 0.0
    private var name = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        lat = arguments?.getDouble("lat", 0.0) ?: 0.0
        lng = arguments?.getDouble("lng", 0.0) ?: 0.0
        name = arguments?.getString("name").orEmpty()

        data class Choice(val label: String, val pkg: String?, val action: () -> Unit)

        val choices = mutableListOf<Choice>()

        // 手机导航（本机竖屏全屏导航页）—— 原实现缺失，导致只有"投屏"没有"手机导航"
        choices += Choice(getString(R.string.navi_phone_mode), null) {
            onPhoneNavi?.invoke()
        }

        // 蓝牙投屏导航（原 App 中仅连接车载蓝牙时出现，这里始终可用）
        choices += Choice(getString(R.string.map_ble_navi), null) {
            onBleNavi?.invoke()
        }

        if (isInstalled("com.autonavi.minimap")) {
            choices += Choice(getString(R.string.map_amap), "com.autonavi.minimap") {
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
            choices += Choice(getString(R.string.map_baidu), "com.baidu.BaiduMap") {
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
            choices += Choice(getString(R.string.map_tencent), "com.tencent.map") {
                val uri = Uri.parse(
                    "qqmap://map/routeplan?type=drive&tocoord=$lat,$lng&to=${Uri.encode(name)}"
                )
                startSafe(Intent(Intent.ACTION_VIEW, uri), getString(R.string.map_tencent))
            }
        }

        // 系统地图兜底（geo:）
        choices += Choice(getString(R.string.map_system), null) {
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
            startSafe(Intent(Intent.ACTION_VIEW, uri), getString(R.string.map_system))
        }

        val labels = choices.map { it.label }.toTypedArray()
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.map_choice_title)
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
