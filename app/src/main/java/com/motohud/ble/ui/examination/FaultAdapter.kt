package com.motohud.ble.ui.examination

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.data.FaultRepository
import com.motohud.ble.databinding.ItemFaultBinding

/** 故障码列表适配器 */
class FaultAdapter : RecyclerView.Adapter<FaultAdapter.VH>() {

    private val items = mutableListOf<FaultRepository.FaultItem>()

    fun submit(list: List<FaultRepository.FaultItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemFaultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFaultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.faultCode.text = item.code
        holder.binding.faultName.text = item.name
        holder.binding.faultCause.text = "可能原因：${item.cause}"
        holder.binding.faultSuggestion.text = "建议处理：${item.suggestion}"
        holder.binding.faultTable.text = item.table
    }

    override fun getItemCount() = items.size
}
