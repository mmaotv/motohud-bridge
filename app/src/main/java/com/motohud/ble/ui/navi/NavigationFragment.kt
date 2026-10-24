package com.motohud.ble.ui.navi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.data.SavedPlaceEntity
import com.motohud.ble.ble.BleConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 导航主界面（复刻原车机 App 的高德导航入口）：
 *
 *  1. 高德夜间地图 + 定位蓝点 + 实时路况；
 *  2. 顶部搜索栏：POI 输入提示（300ms 防抖），点击结果 → 直接规划路线开始导航；
 *  3. 常用地址 chips（家/公司/其他）：点击 → 直接进入导航流程；
 *  4. 底部操作卡：管理地址 / 输入坐标 / 选择地图 / 高德 Key 配置；
 *  5. 未连接车机时给出明确提示；未配置高德 Key 时地图功能优雅降级。
 */
class NavigationFragment : Fragment(R.layout.fragment_navigation) {

    private val vm: NavigationViewModel by viewModels {
        NavigationViewModelFactory(requireActivity().application as MotoHudApp)
    }

    private var mapView: com.amap.api.maps.MapView? = null
    private var aMap: AMap? = null

    /** 屏幕中间的当前位置点 */
    private var meMarker: Marker? = null
    private lateinit var tipAdapter: TipAdapter

    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var rvTips: RecyclerView
    private lateinit var chipsContainer: LinearLayout
    private lateinit var cardKeyMissing: View
    private lateinit var tvBleHint: TextView
    private lateinit var tvLocation: TextView
    private lateinit var fabLocation: View
    private lateinit var chipsScroll: View
    private lateinit var cardBottom: View
    private lateinit var searchArea: View

    /** 搜索建议列表的高度上限（屏高 62%） */
    private var tipsMaxHeight = 0

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refreshLocation()
        enableMyLocation()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etSearch = view.findViewById(R.id.et_search)
        btnClear = view.findViewById(R.id.btn_search_clear)
        rvTips = view.findViewById(R.id.rv_tips)
        chipsContainer = view.findViewById(R.id.chips_container)
        cardKeyMissing = view.findViewById(R.id.card_key_missing)
        tvBleHint = view.findViewById(R.id.tv_ble_hint)
        tvLocation = view.findViewById(R.id.tv_current_location)
        fabLocation = view.findViewById(R.id.fab_location)
        chipsScroll = view.findViewById(R.id.chips_scroll)
        cardBottom = view.findViewById(R.id.card_bottom)
        searchArea = view.findViewById(R.id.search_area)
        mapView = view.findViewById(R.id.map_view)

        // 顶部搜索区按状态栏高度留白（原先写死 44dp，刘海屏/手势导航下会压到状态栏）
        ViewCompat.setOnApplyWindowInsetsListener(searchArea) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = top + (8 * resources.displayMetrics.density).toInt()
            }
            insets
        }
        // 搜索建议列表的高度上限：约屏高 62%，保证不越过屏幕底部
        tipsMaxHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()

        // ---------- 地图 ----------
        setupMap(savedInstanceState)

        // ---------- 搜索 ----------
        tipAdapter = TipAdapter { tip -> openGuidance(tip.name.ifBlank { "目的地" }, tip.latitude, tip.longitude) }
        rvTips.layoutManager = LinearLayoutManager(requireContext())
        rvTips.adapter = tipAdapter
        rvTips.layoutAnimation = android.view.animation.LayoutAnimationController(
            android.view.animation.AnimationUtils.loadAnimation(
                requireContext(), R.anim.item_animation_fall_down
            )
        ).apply { delay = 0.08f }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                btnClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (text.isEmpty()) {
                    vm.clearTips()
                    hideTips()
                    return
                }
                searchRunnable = Runnable { vm.searchTips(text) }.also {
                    searchHandler.postDelayed(it, 300L)
                }
            }
        })

        btnClear.setOnClickListener { etSearch.setText("") }

        // ---------- 底部操作 ----------
        // 两个定位入口（文字「更新刷新定位」与浮动圆钮）走同一个方法：
        // 都强制向定位 SDK 要一次新鲜位置，再移动地图。
        view.findViewById<View>(R.id.btn_refresh_location).setOnClickListener { locateNow() }
        view.findViewById<View>(R.id.btn_manage_places).setOnClickListener { showManagePlaces() }
        view.findViewById<View>(R.id.btn_route_prefs).setOnClickListener { showRoutePrefs() }
        view.findViewById<View>(R.id.btn_manual_dest).setOnClickListener { showManualDestDialog() }
        view.findViewById<View>(R.id.btn_setup_key).setOnClickListener { showKeySetup() }
        fabLocation.setOnClickListener { locateNow() }

        // ---------- 状态观察 ----------
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.places.collect { renderChips(it) } }
                launch {
                    vm.tips.collect { list ->
                        tipAdapter.submitList(list)
                        val active = list.isNotEmpty()
                        rvTips.visibility = if (active) View.VISIBLE else View.GONE
                        // 搜索建议展开时收起底部地址卡与浮动按钮，避免列表被遮挡
                        chipsScroll.visibility = if (active) View.GONE else View.VISIBLE
                        cardBottom.visibility = if (active) View.GONE else View.VISIBLE
                        fabLocation.visibility = if (active) View.GONE else View.VISIBLE
                        if (active) {
                            // 按条目数估高，避免结果很少时拉出一大块空卡片
                            val d = resources.displayMetrics.density
                            val h = minOf(tipsMaxHeight, (list.size * 78 * d).toInt())
                            if (h > 0) {
                                rvTips.updateLayoutParams<LinearLayout.LayoutParams> { height = h }
                            }
                            rvTips.scheduleLayoutAnimation()
                        }
                    }
                }
                launch {
                    vm.tipsState.collect { state ->
                        when (state) {
                            NavigationViewModel.TipsState.LOADING ->
                                Toast.makeText(requireContext(), R.string.navi_tips_searching, Toast.LENGTH_SHORT).show()
                            NavigationViewModel.TipsState.EMPTY ->
                                Toast.makeText(requireContext(), R.string.navi_tips_empty, Toast.LENGTH_SHORT).show()
                            NavigationViewModel.TipsState.ERROR -> {
                                val code = vm.tipsErrorCode.value
                                val msg = if (code > 0) {
                                    getString(R.string.navi_tips_error_code, code)
                                } else {
                                    getString(R.string.navi_tips_error)
                                }
                                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                            }
                            else -> Unit
                        }
                    }
                }
                launch {
                    vm.locationLabel.collect { label ->
                        tvLocation.text = label.ifEmpty { getString(R.string.navi_location_unknown) }
                    }
                }
                launch {
                    // 当前位置点：跟着定位流走，屏幕中间那个蓝点
                    vm.locationFlow.collect { fix ->
                        if (fix != null) updateMeMarker(fix.lat, fix.lng, fix.accuracy)
                    }
                }
                launch {
                    vm.locating.collect { busy ->
                        if (busy) tvLocation.setText(R.string.navi_locating)
                    }
                }
                launch {
                    (requireActivity().application as MotoHudApp).bleManager.state.collect { state ->
                        val connected = state == BleConnectionManager.State.CONNECTED
                        tvBleHint.visibility = if (connected) View.GONE else View.VISIBLE
                    }
                }
            }
        }

        refreshKeyCard()
    }

    // ---------- 地图 ----------

    private fun setupMap(savedInstanceState: Bundle?) {
        val ready = vm.amapReady
        cardKeyMissing.visibility = if (ready) View.GONE else View.VISIBLE
        if (!ready) {
            mapView?.visibility = View.GONE
            return
        }
        AmapManager.initMap(requireContext())
        mapView?.visibility = View.VISIBLE
        try {
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                mapType = AMap.MAP_TYPE_NIGHT
                uiSettings.apply {
                    isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false
                    isRotateGesturesEnabled = true
                }
                runCatching { setTrafficEnabled(true) }
            }
            // 权限检查放到地图准备好之后：没权限时 SDK 初始化会抛异常
            enableMyLocation()
            moveToMyLocation()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.navi_tips_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查定位权限。
     *
     * 这里**不再**设 `isMyLocationEnabled = true`：高德原生蓝点必须配
     * `setLocationSource` 才会画，只设这个开关不会有任何点，还会多起一个定位源。
     * 当前位置改由 [updateMeMarker] 自己画（与引导页一致）。
     */
    private fun enableMyLocation() {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            ensureLocationPermission()
        }
    }

    /**
     * 当前位置点。
     *
     * 为什么自己画而不是用高德原生蓝点：
     * 原生蓝点必须同时设置 `setLocationSource` 和 `isMyLocationEnabled = true`，
     * 只设后者 SDK 不会画任何东西（导航页之前就少了这个点）。
     * 而且原生蓝点用的是 SDK 自己的定位源，与本 App 用来算路的高德定位
     * 不是同一帧数据，点和路线会对不上。自己画一个 marker 更可控，
     * 与引导页的做法保持一致。
     */
    private fun updateMeMarker(lat: Double, lng: Double, accuracy: Float) {
        val map = aMap ?: return
        if (lat == 0.0 && lng == 0.0) return
        try {
            val pos = LatLng(lat, lng)
            val m = meMarker
            if (m == null) {
                meMarker = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .anchor(0.5f, 0.5f)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .setFlat(true)
                        .zIndex(9f)
                )
            } else {
                m.position = pos
            }
            // 信号差时点变淡，提示用户（不用额外文案打扰）
            meMarker?.alpha = if (accuracy > 0f && accuracy <= 40f) 1f else 0.7f
        } catch (_: Exception) {
        }
    }

    private fun moveToMyLocation() {
        val loc = vm.currentLocation ?: return
        updateMeMarker(loc.first, loc.second, vm.accuracy.value)
        aMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(loc.first, loc.second), 16f)
        )
    }

    /**
     * 强制取一次实时定位，然后移动地图。
     * 「更新刷新定位」文字按钮与右下角浮动定位钮都调这里 —— 两个按钮行为完全一致，
     * 都是"主动向定位 SDK 要一个新位置"，而不是读系统缓存。
     */
    private fun locateNow() {
        ensureLocationPermission()
        if (!vm.hasLocationPermission()) return
        vm.refreshLocation(force = true)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(350)
            vm.locating.first { !it }
            val loc = vm.currentLocation
            if (loc == null) {
                Toast.makeText(requireContext(), R.string.navi_locate_failed, Toast.LENGTH_SHORT)
                    .show()
            } else {
                updateMeMarker(loc.first, loc.second, vm.accuracy.value)
                aMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.first, loc.second), 16.5f)
                )
            }
        }
    }

    /**
     * 切换到本页时由 MainActivity 调用。
     * 定位是会过期的：上一次进导航页可能是几小时前，直接沿用会显示旧位置，
     * 所以每次页面可见都主动刷新一次。
     */
    fun onTabShown() {
        if (!isAdded || view == null) return
        if (!vm.hasLocationPermission()) {
            ensureLocationPermission()
            return
        }
        vm.refreshLocation(force = true)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(400)
            vm.locating.first { !it }
            moveToMyLocation()
        }
    }

    private fun ensureLocationPermission() {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermLauncher.launch(missing.toTypedArray())
    }

    // ---------- 常用地址 chips ----------

    private fun renderChips(places: List<SavedPlaceEntity>) {
        chipsContainer.removeAllViews()
        places.forEach { place ->
            val chip = layoutInflater.inflate(R.layout.item_place_chip, chipsContainer, false)
            val icon = chip.findViewById<ImageView>(R.id.chip_icon)
            val text = chip.findViewById<TextView>(R.id.chip_text)
            icon.setImageResource(
                when (place.type) {
                    SavedPlaceEntity.TYPE_HOME -> R.drawable.ic_place_home
                    SavedPlaceEntity.TYPE_WORK -> R.drawable.ic_place_work
                    else -> R.drawable.ic_place_other
                }
            )
            text.text = place.name
            // 点击 = 直接进入导航流程（复刻"常用地址一键导航"）
            chip.setOnClickListener { openGuidance(place.name, place.latitude, place.longitude) }
            chip.setOnLongClickListener {
                showEditDialog(place)
                true
            }
            chipsContainer.addView(chip)
        }
        // 末尾"＋ 添加"
        val addChip = layoutInflater.inflate(R.layout.item_place_chip, chipsContainer, false)
        addChip.findViewById<ImageView>(R.id.chip_icon).setImageResource(R.drawable.ic_add)
        addChip.findViewById<TextView>(R.id.chip_text).setText(R.string.navi_add_place)
        addChip.setOnClickListener { showEditDialog(null) }
        chipsContainer.addView(addChip)
    }

    private fun showManagePlaces() {
        val dialog = PlaceManageDialog()
        dialog.onNavi = { place -> openGuidance(place.name, place.latitude, place.longitude) }
        dialog.onEdit = { place -> showEditDialog(place) }
        dialog.onDelete = { place -> confirmDelete(place) }
        dialog.show(childFragmentManager, "place_manage")
    }

    private fun showEditDialog(place: SavedPlaceEntity?) {
        val dialog = PlaceEditDialog.newInstance(place, vm.currentLocation)
        dialog.onSave = { name, lat, lng, type, remark ->
            if (place == null) {
                vm.addPlace(name, lat, lng, type, remark)
                Toast.makeText(requireContext(), R.string.place_added, Toast.LENGTH_SHORT).show()
            } else {
                vm.updatePlace(place.copy(name = name, latitude = lat, longitude = lng, type = type, remark = remark))
                Toast.makeText(requireContext(), R.string.place_updated, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show(childFragmentManager, "place_edit")
    }

    private fun confirmDelete(place: SavedPlaceEntity) {
        MotoDialog.builder(requireContext())
            .setTitle(R.string.place_delete_title)
            .setMessage(getString(R.string.place_delete_message, place.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                vm.deletePlace(place)
                Toast.makeText(requireContext(), R.string.place_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- 导航启动 ----------

    /**
     * 点击搜索结果 / 常用地址 → 先到「选择路线」页看方案，再决定走哪条。
     * 不再像以前那样点一下地点就直接进导航。
     */
    private fun openGuidance(name: String, lat: Double, lng: Double) {
        hideTips()
        etSearch.setText("")
        val loc = vm.currentLocation
        RouteSelectActivity.start(
            requireContext(), name, lat, lng,
            fromLat = loc?.first ?: 0.0,
            fromLng = loc?.second ?: 0.0
        )
    }

    private fun hideTips() {
        rvTips.visibility = View.GONE
    }

    private fun showManualDestDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_dest, null)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_dest_name)
        val etLat = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_dest_lat)
        val etLng = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_dest_lng)
        val tilLat = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_dest_lat)
        val tilLng = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_dest_lng)
        // 坐标框只放提示，不预填。
        // 预填会让用户输入变成"追加"，得到 30.xxxx40.0801 这种拼接值，算出离谱路线。
        // 注意提示要设在 TextInputLayout 的 helperText 上：
        // 直接设 EditText.hint 会和 TIL 自己的 floating hint 抢同一块位置，
        // 叠成「纬度（GCJ02，如 39.9087）42)」这种鬼样子。
        vm.currentLocation?.let { (lat, lng) ->
            tilLat.helperText = getString(R.string.manual_dest_use_current, lat)
            tilLng.helperText = getString(R.string.manual_dest_use_current, lng)
        }
        // 「选择导航方式」不再作为中性按钮：导航页底部已有「选择地图」，
        // 三颗长中文按钮还会被 Material 挤成竖排。这里只留 取消 / 开始导航。
        MotoDialog.builder(requireContext())
            .setTitle(R.string.manual_dest_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.start_navi) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty().ifEmpty { "目的地" }
                // 坐标留空 = 用当前位置（helperText 里已经给出提示）
                val cur = vm.currentLocation
                val lat = etLat.text?.toString()?.trim()?.toDoubleOrNull() ?: cur?.first
                val lng = etLng.text?.toString()?.trim()?.toDoubleOrNull() ?: cur?.second
                if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    Toast.makeText(requireContext(), R.string.place_input_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                openGuidance(name, lat, lng)
            }
            .show()
    }

    // ---------- 高德 Key ----------

    private fun showKeySetup() {
        val dialog = KeySetupDialog()
        dialog.show(childFragmentManager, "key_setup")
    }

    /** 路线偏好：与「选择路线」页共用同一个对话框 */
    private fun showRoutePrefs() {
        val d = RoutePrefsDialog()
        d.show(childFragmentManager, "route_prefs")
    }

    private fun refreshKeyCard() {
        cardKeyMissing.visibility = if (vm.amapReady) View.GONE else View.VISIBLE
    }

    /** Key 在设置中被修改后由 MainActivity 回调：延迟初始化地图并刷新降级卡片 */
    fun onAmapKeyChanged() {
        if (aMap == null) {
            setupMap(null)
        } else {
            mapView?.visibility = View.VISIBLE
        }
        refreshKeyCard()
        enableMyLocation()
        moveToMyLocation()
    }

    // ---------- MapView 生命周期 ----------

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        refreshKeyCard()
        // 从二级页面（管理地址、坐标输入等）返回时同步刷新一次位置
        if (isVisible) vm.refreshLocation()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        meMarker = null
        mapView?.onDestroy()
        mapView = null
        aMap = null
        super.onDestroyView()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }
}

/** NavigationViewModel 工厂（复用 Application 中的 DAO） */
class NavigationViewModelFactory(private val app: MotoHudApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        NavigationViewModel(app) as T
}
