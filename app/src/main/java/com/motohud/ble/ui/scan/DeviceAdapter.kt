package com.motohud.ble.ui.scan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.databinding.ItemDeviceBinding

/** 扫描结果列表适配器 */
class DeviceAdapter(
    private val onConnect: (BleConnectionManager.ScanItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<BleConnectionManager.ScanItem>()

    fun submit(list: List<BleConnectionManager.ScanItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.deviceName.text = item.name
        holder.binding.deviceAddress.text = item.address
        holder.binding.deviceRssi.text = "${item.rssi} dBm"
        holder.binding.deviceType.text = when (item.deviceType.name) {
            "SR" -> "SR 设备"
            "BYQ" -> "BYQ 设备"
            else -> "未知"
        }
        holder.binding.root.setOnClickListener { onConnect(item) }
    }

    override fun getItemCount() = items.size
}
