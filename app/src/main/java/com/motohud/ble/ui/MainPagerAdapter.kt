package com.motohud.ble.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.motohud.ble.ui.command.CommandFragment
import com.motohud.ble.ui.dashboard.DashboardFragment
import com.motohud.ble.ui.examination.ExaminationFragment
import com.motohud.ble.ui.garage.GarageFragment
import com.motohud.ble.ui.navi.NavigationFragment
import com.motohud.ble.ui.scan.ScanFragment

/**
 * 主界面 6 个页签：爱车 / 仪表 / 体检 / 导航 / 指令 / 连接。
 * 本地检索等低频页面走 HostActivity 二级入口。
 */
class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 6
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> GarageFragment()
        1 -> DashboardFragment()
        2 -> ExaminationFragment()
        3 -> NavigationFragment()
        4 -> CommandFragment()
        else -> ScanFragment()
    }
}
