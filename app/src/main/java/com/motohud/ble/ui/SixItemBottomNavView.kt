package com.motohud.ble.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 放开 5 项限制的底部导航（本项目有 6 个页面）。
 * Material 的 NavigationBarView 默认最多 5 项，此处按项目需要放宽到 6 项。
 */
class SixItemBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle
) : BottomNavigationView(context, attrs, defStyleAttr) {

    override fun getMaxItemCount(): Int = 6
}
