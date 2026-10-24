package com.motohud.ble.ui.vehicle

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.motohud.ble.R
import com.motohud.ble.ui.command.CommandFragment
import com.motohud.ble.ui.dashboard.DashboardFragment
import com.motohud.ble.ui.examination.ExaminationFragment

/**
 * 车况页：把「仪表 / 体检 / 指令」三个原本各占一个底部 Tab 的页面合成一页。
 *
 * 合并理由：三者本质都是"看车 + 对车下发指令"，原先在爱车页的功能宫格里
 * 又各有一个入口，同一个功能出现两次。现在底部只保留一个「车况」，
 * 爱车页不再重复放入口。
 */
class VehicleFragment : Fragment(R.layout.fragment_vehicle) {

    private var tabs: TabLayout? = null
    private var pager: ViewPager2? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabs = view.findViewById(R.id.vehicle_tabs)
        pager = view.findViewById(R.id.vehicle_pager)

        pager?.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> DashboardFragment()
                1 -> ExaminationFragment()
                else -> CommandFragment()
            }
        }
        pager?.offscreenPageLimit = 1

        val t = tabs ?: return
        val p = pager ?: return
        TabLayoutMediator(t, p) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.nav_dashboard
                    1 -> R.string.nav_examination
                    else -> R.string.nav_command
                }
            )
        }.attach()
    }

    /** 从别处（如爱车页）跳到指定子页 */
    fun selectPage(index: Int) {
        pager?.setCurrentItem(index.coerceIn(0, 2), false)
    }

    override fun onDestroyView() {
        tabs = null
        pager = null
        super.onDestroyView()
    }
}
