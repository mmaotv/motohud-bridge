package com.motohud.ble.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.motohud.ble.ui.garage.GarageFragment
import com.motohud.ble.ui.navi.NavigationFragment
import com.motohud.ble.ui.scan.ScanFragment
import com.motohud.ble.ui.vehicle.VehicleFragment

/**
 * 主界面 4 个页签：爱车 / 导航 / 车况 / 连接。
 *
 * 车况页内部再分「仪表 / 体检 / 指令」三个子页（见 [VehicleFragment]）。
 * 本地检索等低频页面走 HostActivity 二级入口。
 */
class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> GarageFragment()
        1 -> NavigationFragment()
        2 -> VehicleFragment()
        else -> ScanFragment()
    }
}
