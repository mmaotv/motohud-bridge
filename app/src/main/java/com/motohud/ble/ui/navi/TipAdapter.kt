package com.motohud.ble.ui.navi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.amap.TipItem

/**
 * POI 搜索结果适配器（高德输入提示）。
 * 点击 → 直接开始导航（由 NavigationFragment 跳转 GuidanceActivity）。
 */
class TipAdapter(
    private val onClick: (TipItem) -> Unit
) : ListAdapter<TipItem, TipAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tip, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tip = getItem(position)
        holder.name.text = tip.name
        holder.address.text = listOf(tip.district, tip.address)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifEmpty { tip.latitude.toString() + ", " + tip.longitude }
        holder.itemView.setOnClickListener { onClick(tip) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_tip_name)
        val address: TextView = view.findViewById(R.id.tv_tip_address)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TipItem>() {
            override fun areItemsTheSame(a: TipItem, b: TipItem) =
                a.name == b.name && a.latitude == b.latitude && a.longitude == b.longitude

            override fun areContentsTheSame(a: TipItem, b: TipItem) = a == b
        }
    }
}
