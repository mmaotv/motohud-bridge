package com.motohud.ble.ui.navi

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.SavedPlaceEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 常用地址管理对话框（列表：点击导航 / 编辑 / 删除）。
 */
class PlaceManageDialog : DialogFragment() {

    /** 点击地址 → 直接进入导航 */
    var onNavi: ((SavedPlaceEntity) -> Unit)? = null
    var onEdit: ((SavedPlaceEntity) -> Unit)? = null
    var onDelete: ((SavedPlaceEntity) -> Unit)? = null

    private val vm: NavigationViewModel by activityViewModels {
        NavigationViewModelFactory(requireActivity().application as MotoHudApp)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val rv = RecyclerView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(ctx)
        }
        val adapter = PlaceAdapter(
            onClick = { place -> onNavi?.invoke(place); dismiss() },
            onEdit = { place -> onEdit?.invoke(place); dismiss() },
            onDelete = { place -> onDelete?.invoke(place) }
        )
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.places.collect { list -> adapter.submitList(list) }
            }
        }

        val container = FrameLayout(ctx).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(rv)
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.navi_manage_places)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
