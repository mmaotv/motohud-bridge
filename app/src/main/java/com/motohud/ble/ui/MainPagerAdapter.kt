package com.motohud.ble.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.motohud.ble.ui.scan.ScanFragment
import com.motohud.ble.ui.dashboard.DashboardFragment
import com.motohud.ble.ui.navi.NavigationFragment
import com.motohud.ble.ui.examination.ExaminationFragment
import com.motohud.ble.ui.command.CommandFragment
import com.motohud.ble.ui.search.SearchFragment

class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 6
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ScanFragment()
        1 -> DashboardFragment()
        2 -> NavigationFragment()
        3 -> ExaminationFragment()
        4 -> CommandFragment()
        else -> SearchFragment()
    }
}
