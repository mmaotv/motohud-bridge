package com.motohud.ble.ui.garage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.data.bike.BikeModel
import com.motohud.ble.databinding.ItemBikeOptionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 车型选择列表：缩略图 + 名称 + 排量副标题 + 选中勾 */
class BikeOptionAdapter(
    private val scope: CoroutineScope,
    private val selectedId: String,
    private val onClick: (BikeModel) -> Unit
) : RecyclerView.Adapter<BikeOptionAdapter.VH>() {

    private val items = ArrayList<BikeModel>()
    private val thumbCache = HashMap<String, Bitmap?>()
    private val attempted = HashSet<String>()

    fun submit(list: List<BikeModel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBikeOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val model = items[position]
        holder.binding.tvName.text = model.name
        holder.binding.tvSub.text = "${model.brand.display} · ${model.subtitle}"
        holder.binding.ivCheck.visibility =
            if (model.id == selectedId) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.binding.root.setOnClickListener { onClick(model) }
        holder.binding.ivThumb.setImageBitmap(null)

        val cached = thumbCache[model.image]
        if (cached != null || !attempted.add(model.image)) {
            holder.binding.ivThumb.setImageBitmap(cached)
            return
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    holder.binding.root.context.assets.open(model.assetPath)
                        .use { BitmapFactory.decodeStream(it, null, opts) }
                }.getOrNull()
            }
            thumbCache[model.image] = bmp
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.binding.ivThumb.setImageBitmap(bmp)
            }
            // 同一张图被多个车型复用（如 SR Max 250/300），加载完成后统一刷新
            notifyDataSetChanged()
        }
    }

    class VH(val binding: ItemBikeOptionBinding) : RecyclerView.ViewHolder(binding.root)
}
