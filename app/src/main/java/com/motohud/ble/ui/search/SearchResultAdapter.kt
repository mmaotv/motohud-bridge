package com.motohud.ble.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.data.SearchEngine
import com.motohud.ble.databinding.ItemSearchResultBinding

/** 搜索结果适配器（append=true 时为翻页追加，复刻原 dealRepeat/loadMore 语义） */
class SearchResultAdapter : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items = mutableListOf<SearchEngine.Item>()

    fun submit(list: List<SearchEngine.Item>, firstPage: Boolean) {
        if (firstPage) items.clear()
        val existing = items.map { it.id }.toSet()
        list.forEach { if (it.id !in existing) items.add(it) }
        notifyDataSetChanged()
    }

    class VH(val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.resultTitle.text = item.title
        holder.binding.resultSubtitle.text = item.subtitle
        holder.binding.resultDetail.text = item.detail
        holder.binding.resultScope.text = item.scope.label
    }

    override fun getItemCount() = items.size
}
