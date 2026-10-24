package com.motohud.ble.ui.garage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.bike.BikeModel
import com.motohud.ble.data.bike.BikePhotoStore
import com.motohud.ble.data.bike.BikePrefs
import com.motohud.ble.data.bike.BikeSpec
import com.motohud.ble.databinding.FragmentGarageBinding
import com.motohud.ble.ui.HostActivity
import com.motohud.ble.ui.MainActivity
import com.motohud.ble.ui.MainViewModel
import com.motohud.ble.ui.common.MotoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 爱车页 —— 首页。
 *
 *  - 上半屏：车型大图 + 场景光晕 + 品牌/车型名（参考九号、小牛等车辆 App 的首页构图）
 *  - 中部：油量 / 电压 / 水温 / 总里程 四个关键数据 + 功能宫格
 *  - 下部：车辆参数与亮点标签，可「换车」切换车型
 *
 * 原来分散在多个页签的信息在这里聚合：仪表、体检、指令、导航、检索、连接都能一键直达。
 */
class GarageFragment : Fragment(R.layout.fragment_garage) {

    private var _binding: FragmentGarageBinding? = null
    private val binding get() = _binding!!

    private val vm: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireActivity().application as MotoHudApp)
    }

    private val app get() = requireActivity().application as MotoHudApp
    private var currentBike: BikeModel? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGarageBinding.bind(view)

        binding.btnChangeBike.setOnClickListener { showPicker() }
        binding.hero.setOnClickListener { showPhotoMenu() }

        // 只保留底部 Tab 里没有的入口；仪表/体检/指令/导航/连接 都在底部，不再重复放这里
        binding.tileSearch.setOnClickListener {
            // 标题留空：检索页自身有标题，避免顶部重复
            HostActivity.start(requireContext(), "", HostActivity.PAGE_SEARCH)
        }
        binding.tileSettings.setOnClickListener {
            (requireActivity() as? MainActivity)?.showSettings()
        }

        observeBike()
        observeConnection()
        observeTelemetry()
    }

    // ---------- 车型 ----------

    private fun observeBike() {
        renderBike(BikePrefs.current.value)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BikePrefs.current.collect { renderBike(it) }
            }
        }
    }

    private fun renderBike(bike: BikeModel) {
        currentBike = bike
        binding.tvBrand.text = bike.brand.display
        binding.tvBrand.setTextColor(bike.accent.toInt())
        binding.tvModel.text = bike.name
        binding.tvSubtitle.text = bike.subtitle
        loadBikeImage(bike)

        // 亮点标签
        binding.highlightRow.removeAllViews()
        bike.highlights.forEachIndexed { index, text ->
            val tv = TextView(requireContext(), null, 0, R.style.Garage_Highlight).apply {
                this.text = text
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26)
            ).apply { if (index > 0) marginStart = dp(6) }
            binding.highlightRow.addView(tv, lp)
        }

        renderSpecs(bike.specs)
    }

    private fun renderSpecs(specs: List<BikeSpec>) {
        binding.specContainer.removeAllViews()
        specs.forEach { spec ->
            val row = LinearLayout(requireContext(), null, 0, R.style.Garage_Spec_Row)
            val label = TextView(requireContext(), null, 0, R.style.Garage_Spec_Label).apply {
                text = spec.label
            }
            val value = TextView(requireContext(), null, 0, R.style.Garage_Spec_Value).apply {
                text = spec.value
            }
            // style 里的 layout_* 不会被解析，布局参数必须显式给
            row.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                value,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            binding.specContainer.addView(row)
        }
    }

    private fun showPicker() {
        BikePickerDialog().show(childFragmentManager, "bike_picker")
    }

    // ---------- 自定义车照 ----------

    /**
     * 相册选图回调：拿到 URI 后做降采样 + 居中裁切再落盘，
     * 显示侧配合 CENTER_CROP，任何比例的原图都不会被拉伸。
     */
    private val pickBikePhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val bike = currentBike ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = BikePhotoStore.save(requireContext(), bike.id, uri)
                if (ok) {
                    Toast.makeText(
                        requireContext(), R.string.garage_photo_saved, Toast.LENGTH_SHORT
                    ).show()
                    renderBike(BikePrefs.current.value)
                } else {
                    Toast.makeText(
                        requireContext(), R.string.garage_photo_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    /** 点击车型大图：选车照 / 恢复默认 / 换车（换车按钮仍单独保留） */
    private fun showPhotoMenu() {
        val bike = currentBike ?: return
        val hasCustom = BikePhotoStore.hasPhoto(requireContext(), bike.id)
        val items = buildList {
            add(getString(R.string.garage_photo_pick))
            if (hasCustom) add(getString(R.string.garage_photo_reset))
            add(getString(R.string.garage_photo_change_bike))
        }
        MotoDialog.builder(requireContext())
            .setTitle(R.string.garage_photo_menu_title)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickBikePhoto.launch("image/*")
                    1 -> if (hasCustom) resetBikePhoto() else showPicker()
                    else -> showPicker()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetBikePhoto() {
        val bike = currentBike ?: return
        BikePhotoStore.delete(requireContext(), bike.id)
        Toast.makeText(requireContext(), R.string.garage_photo_reset_done, Toast.LENGTH_SHORT).show()
        renderBike(BikePrefs.current.value)
    }

    /** 优先显示用户自定义车照（CENTER_CROP 裁切填充），否则回退到内置车型图。 */
    private fun loadBikeImage(bike: BikeModel) {
        val iv = binding.ivBike
        viewLifecycleOwner.lifecycleScope.launch {
            val w = iv.width.takeIf { it > 0 } ?: iv.rootView.width
            val bmp = BikePhotoStore.load(requireContext(), bike.id, w)
            _binding ?: return@launch
            if (bmp != null) {
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(bmp)
            } else {
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                val asset = withContext(Dispatchers.IO) { decodeAsset(bike.assetPath, 2) }
                _binding ?: return@launch
                if (asset != null) iv.setImageBitmap(asset)
            }
        }
    }

    private fun decodeAsset(path: String, sample: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        requireContext().assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    // ---------- 连接状态 ----------

    private fun observeConnection() {
        val ble = app.bleManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    renderStatus(st)
                    if (st == BleConnectionManager.State.CONNECTED) tryAutoMatch(ble.connectedDeviceName)
                }
            }
        }
        renderStatus(ble.state.value)
    }

    private fun renderStatus(state: BleConnectionManager.State) {
        val connected = state == BleConnectionManager.State.CONNECTED
        binding.statusDot.setBackgroundResource(
            when (state) {
                BleConnectionManager.State.CONNECTED -> R.drawable.dot_green
                BleConnectionManager.State.CONNECTING,
                BleConnectionManager.State.RECONNECTING -> R.drawable.dot_orange
                else -> R.drawable.dot_gray
            }
        )
        binding.tvStatus.setText(
            if (connected) R.string.garage_status_connected else R.string.status_disconnected
        )
    }

    /** 首次连接车机时按设备名猜一次车型 */
    private fun tryAutoMatch(deviceName: String?) {
        val matched = BikePrefs.autoMatchOnce(requireContext(), deviceName) ?: return
        Toast.makeText(
            requireContext(),
            getString(R.string.garage_auto_matched, matched.name),
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------- 实时数据 ----------

    private fun observeTelemetry() {
        vm.telemetry.observe(viewLifecycleOwner) { t ->
            binding.tvOil.text = t.oil?.let { "$it%" } ?: getString(R.string.garage_value_empty)
            binding.tvBattery.text = t.battery?.let { String.format(Locale.US, "%.1fV", it) }
                ?: getString(R.string.garage_value_empty)
            binding.tvWater.text = when (t.waterTemp) {
                null -> getString(R.string.garage_value_empty)
                0 -> "<45℃"
                else -> "${t.waterTemp}℃"
            }
            binding.tvMileage.text = t.totalMileage?.let { "$it" }
                ?: getString(R.string.garage_value_empty)
        }
    }

    // ---------- 工具 ----------

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
