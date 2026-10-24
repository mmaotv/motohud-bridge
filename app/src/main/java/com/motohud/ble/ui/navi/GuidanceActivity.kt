package com.motohud.ble.ui.navi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.amap.accuracyLevel
import com.motohud.ble.amap.formatAccuracy
import com.motohud.ble.amap.locationTypeLabel
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.NaviEngine
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteNaviEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 手机导航页（专业导航软件体验）。
 *
 * 数据流沿用投屏链路，只是把"高德导航 SDK 的 NaviInfo"换成自研 [RouteNaviEngine]：
 *
 *   POI/收藏地址 → 路线规划（摩托车/驾车策略）→ 引擎每秒推演
 *     → 手机界面（转向横幅 / 剩余 / 预计到达 / 地图跟随 / 当前位置点）
 *     → [com.motohud.ble.navi.NaviForwarder] → BLE → 车机屏幕
 *
 * 定位说明（漂移修复）：
 *  - 优先使用高德连续定位（GCJ02，多源融合），回退系统 GPS 时强制做 WGS84→GCJ02 纠偏；
 *  - 所有定位帧经 [com.motohud.ble.navi.LocationFilter] 过滤精度差、跳点与静止抖动；
 *  - 地图上的当前位置点由引擎状态驱动，与路线匹配使用同一份坐标，不再"蓝点与路线各走各的"。
 *
 * 三种使用形态：
 *  - 手机导航（本页）：全屏地图 + 转向横幅 + 底部信息卡；
 *  - 投屏到车机：转 [HudActivity]（横屏大字），共用同一引擎，导航不中断；
 *  - 未连接车机：仅本机显示，连接后自动恢复转发。
 */
class GuidanceActivity : AppCompatActivity() {

    private lateinit var app: MotoHudApp
    private lateinit var engine: RouteNaviEngine
    private lateinit var ble: BleConnectionManager

    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var routeLine: Polyline? = null
    private var destMarker: Marker? = null
    private var meMarker: Marker? = null

    private lateinit var topBar: View
    private lateinit var bottomInfo: View
    private lateinit var tvDestName: TextView
    private lateinit var tvDestSub: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var btnVoice: ImageView
    private lateinit var turnBanner: View
    private lateinit var ivTurnIcon: ImageView
    private lateinit var tvTurnInstruction: TextView
    private lateinit var tvTurnRoad: TextView
    private lateinit var tvTurnDistance: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvRemain: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGpsMode: TextView
    private lateinit var tvGpsQuality: TextView
    private lateinit var tvSignalWarn: View
    private lateinit var tvBleHint: TextView
    private lateinit var planningOverlay: View
    private lateinit var arrivedOverlay: View

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var route: RouteData? = null
    private var destName: String = "目的地"
    private var destLat = 0.0
    private var destLng = 0.0
    private var lastCameraMs = 0L

    /** 上一次相机跟随的目标点（用于拦截异常跳跃） */
    private var lastFollowLat = 0.0
    private var lastFollowLng = 0.0
    private var stopped = false

    /** 本次导航是否已调用过 engine.start（屏蔽引擎里上一次导航的残留状态） */
    private var started = false

    /** 用户手动拖动地图后暂停自动跟随，点「回中」恢复 */
    private var userPanned = false

    /** 是否已提示过定位精度差（避免反复弹） */
    private var weakGpsToastShown = false

    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 无定位时引擎自动走推算模式 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guidance)

        app = application as MotoHudApp
        engine = app.naviEngine
        ble = app.bleManager

        NaviPrefs.init(this)
        bindViews()
        applyWindowInsets()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "目的地" }
        destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)

        tvDestName.text = destName
        tvDestSub.text = CoordConverter.formatLatLng(destLat, destLng)

        ensureLocationPermission()
        setupMap(savedInstanceState)
        initTts()
        bindActions()
        updateModeBadge()
        updateVoiceButton()
        observeState()

        planAndStart()
    }

    private fun bindViews() {
        topBar = findViewById(R.id.top_bar)
        bottomInfo = findViewById(R.id.bottom_info)
        tvDestName = findViewById(R.id.tv_dest_name)
        tvDestSub = findViewById(R.id.tv_dest_sub)
        tvModeBadge = findViewById(R.id.tv_mode_badge)
        btnVoice = findViewById(R.id.btn_voice)
        turnBanner = findViewById(R.id.turn_banner)
        ivTurnIcon = findViewById(R.id.iv_turn_icon)
        tvTurnInstruction = findViewById(R.id.tv_turn_instruction)
        tvTurnRoad = findViewById(R.id.tv_turn_road)
        tvTurnDistance = findViewById(R.id.tv_turn_distance)
        tvEta = findViewById(R.id.tv_eta)
        tvRemain = findViewById(R.id.tv_remain)
        tvSpeed = findViewById(R.id.tv_speed_live)
        tvGpsMode = findViewById(R.id.tv_gps_mode)
        tvGpsQuality = findViewById(R.id.tv_gps_quality)
        tvSignalWarn = findViewById(R.id.tv_signal_warn)
        tvBleHint = findViewById(R.id.tv_guidance_ble_hint)
        planningOverlay = findViewById(R.id.planning_overlay)
        arrivedOverlay = findViewById(R.id.arrived_overlay)
    }

    /**
     * 按 WindowInsets 给顶栏/底栏留出状态栏与导航栏高度。
     * 这比写死 44dp 可靠：刘海屏、挖孔屏、手势导航条的数值都不一样。
     */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val baseTop = (4 * resources.displayMetrics.density).roundToInt()
        val baseBottom = (12 * resources.displayMetrics.density).roundToInt()
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + baseTop)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomInfo) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom + baseBottom)
            insets
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.btn_back).setOnClickListener { confirmExit() }
        // 顶栏投屏钮 = 显示方式切换（手机导航 / 车机投屏）
        findViewById<View>(R.id.btn_cast).setOnClickListener { showDisplayModeDialog() }
        findViewById<View>(R.id.btn_cast_navi).setOnClickListener { showDisplayModeDialog() }
        findViewById<View>(R.id.btn_stop_navi).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btn_finish_navi).setOnClickListener {
            stopNavi(sendFinish = true)
            finish()
        }
        btnVoice.setOnClickListener {
            val on = !NaviPrefs.voice.value
            NaviPrefs.setVoice(on)
            updateVoiceButton()
            if (!on) runCatching { tts?.stop() }
            Toast.makeText(
                this,
                if (on) R.string.navi_voice_on else R.string.navi_voice_off,
                Toast.LENGTH_SHORT
            ).show()
        }
        tvModeBadge.setOnClickListener { showRouteModeDialog() }
        findViewById<View>(R.id.btn_overview).setOnClickListener {
            route?.let { drawRoute(it, fitAll = true) }
        }
        findViewById<View>(R.id.btn_recenter).setOnClickListener {
            userPanned = false
            lastCameraMs = 0L
            followOnMap(engine.state.value, force = true)
        }
    }

    private fun updateVoiceButton() {
        btnVoice.setImageResource(
            if (NaviPrefs.voice.value) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
        btnVoice.alpha = if (NaviPrefs.voice.value) 1f else 0.55f
    }

    private fun updateModeBadge() {
        tvModeBadge.setText(
            if (NaviPrefs.motorcycle.value) R.string.navi_mode_motorcycle else R.string.navi_mode_car
        )
    }

    // ---------- 路线规划 + 启动 ----------

    private fun planAndStart() {
        val start = currentStart()
        if (!AmapManager.hasKey(this) || !AmapManager.isPrivacyAgreed(this)) {
            Toast.makeText(this, R.string.guidance_demo_mode, Toast.LENGTH_LONG).show()
            begin(start, null)
            return
        }
        planningOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            AmapManager.initSearch(this@GuidanceActivity)
            val from = start ?: NaviEngine.DEFAULT_START
            val motorcycle = NaviPrefs.motorcycle.value
            val avoidHighway = NaviPrefs.avoidHighway.value
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = AmapRepo(this@GuidanceActivity)
                    if (motorcycle) {
                        repo.planMotorcycleRoute(from.first, from.second, destLat, destLng, avoidHighway)
                    } else {
                        repo.planDriveRoute(from.first, from.second, destLat, destLng)
                    }
                }
            }
            planningOverlay.visibility = View.GONE
            val r = result.getOrNull()
            when {
                r is RoutePlanResult.Success -> {
                    route = r.route
                    drawRoute(r.route, fitAll = true)
                    tvDestSub.text = routeSummary(r.route)
                    begin(start, r.route)
                }
                r is RoutePlanResult.Failure -> {
                    Toast.makeText(
                        this@GuidanceActivity,
                        getString(R.string.guidance_plan_failed, r.message),
                        Toast.LENGTH_LONG
                    ).show()
                    // 摩托车策略算不出时回退默认驾车，避免直接掉进演示模式
                    if (NaviPrefs.motorcycle.value) {
                        fallbackToDrive(from)
                    } else {
                        begin(start, null)
                    }
                }
                else -> begin(start, null)
            }
        }
    }

    private fun routeSummary(r: RouteData): String = buildString {
        append(CoordConverter.formatDistance(r.distanceM.toInt()))
        append(" · ")
        append(CoordConverter.formatEta(r.durationS.toInt()))
        if (r.modeLabel.isNotBlank()) {
            append(" · ")
            append(r.modeLabel)
        }
    }

    /** 摩托车策略算路失败时，退回默认驾车策略再试一次 */
    private fun fallbackToDrive(from: Pair<Double, Double>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@GuidanceActivity)
                        .planDriveRoute(from.first, from.second, destLat, destLng)
                }
            }
            val r = result.getOrNull()
            if (r is RoutePlanResult.Success) {
                route = r.route
                drawRoute(r.route, fitAll = true)
                tvDestSub.text = routeSummary(r.route)
                begin(from, r.route)
            } else {
                begin(from, null)
            }
        }
    }

    private fun begin(start: Pair<Double, Double>?, r: RouteData?) {
        turnBanner.visibility = View.VISIBLE
        arrivedOverlay.visibility = View.GONE
        engine.onStepChanged = { instruction -> speak(instruction) }
        // 引擎为全局共享实例，上一次导航的 arrived=true 仍留在 StateFlow 里，
        // 必须先置位再 start，避免首帧按旧状态误判「已到达」。
        started = true
        engine.start(destName, destLat, destLng, start, r)
        if (start == null) {
            Toast.makeText(this, R.string.guidance_no_location, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 起点：优先用导航引擎的实时位置（导航中重新算路必须从这里出发，
     * 否则会用系统缓存里的旧位置，算出几百公里的离谱路线）；
     * 其次用系统最后已知位置（WGS84→GCJ02，超过 5 分钟视为过期）。
     */
    @Suppress("MissingPermission")
    private fun currentStart(): Pair<Double, Double>? {
        val st = engine.state.value
        // 只有「本次导航已经开跑」时才用引擎位置，否则可能是上一次导航残留
        if (started && st.running && st.latitude != 0.0 && st.longitude != 0.0) {
            return st.latitude to st.longitude
        }
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val best = listOfNotNull(
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ).maxByOrNull { it.time }
            if (best == null) return null
            val fresh = System.currentTimeMillis() - best.time < 5 * 60_000L
            if (!fresh) return null
            CoordConverter.wgs84ToGCJ(best.latitude, best.longitude)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 路线模式切换 ----------

    private fun showRouteModeDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_route_mode, null)
        val optMotor = v.findViewById<View>(R.id.opt_motorcycle)
        val optCar = v.findViewById<View>(R.id.opt_car)
        val checkMotor = v.findViewById<View>(R.id.check_motorcycle)
        val checkCar = v.findViewById<View>(R.id.check_car)
        val rowAvoid = v.findViewById<View>(R.id.row_avoid_highway)
        val switchAvoid = v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switch_avoid_highway
        )

        fun renderSelection() {
            val motor = NaviPrefs.motorcycle.value
            checkMotor.visibility = if (motor) View.VISIBLE else View.INVISIBLE
            checkCar.visibility = if (motor) View.INVISIBLE else View.VISIBLE
            checkMotor.alpha = 1f
            checkCar.alpha = 1f
            rowAvoid.visibility = if (motor) View.VISIBLE else View.GONE
            switchAvoid.isChecked = NaviPrefs.avoidHighway.value
        }
        renderSelection()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.navi_route_mode)
            .setView(v)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun applyMode(toMotor: Boolean) {
            if (toMotor == NaviPrefs.motorcycle.value) {
                dialog.dismiss()
                return
            }
            NaviPrefs.setMotorcycle(toMotor)
            updateModeBadge()
            dialog.dismiss()
            Toast.makeText(
                this,
                if (toMotor) R.string.navi_mode_switched_motor else R.string.navi_mode_switched_car,
                Toast.LENGTH_SHORT
            ).show()
            replan()
        }

        optMotor.setOnClickListener { applyMode(true) }
        optCar.setOnClickListener { applyMode(false) }
        switchAvoid.setOnCheckedChangeListener { _, checked ->
            NaviPrefs.setAvoidHighway(checked)
            if (NaviPrefs.motorcycle.value) replan()
        }
        switchAvoid.isChecked = NaviPrefs.avoidHighway.value

        dialog.show()
    }

    /** 按当前偏好重新算路（不退出页面） */
    private fun replan() {
        planningOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            AmapManager.initSearch(this@GuidanceActivity)
            val from = currentStart() ?: NaviEngine.DEFAULT_START
            val motorcycle = NaviPrefs.motorcycle.value
            val avoidHighway = NaviPrefs.avoidHighway.value
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = AmapRepo(this@GuidanceActivity)
                    if (motorcycle) {
                        repo.planMotorcycleRoute(from.first, from.second, destLat, destLng, avoidHighway)
                    } else {
                        repo.planDriveRoute(from.first, from.second, destLat, destLng)
                    }
                }
            }
            planningOverlay.visibility = View.GONE
            val r = result.getOrNull()
            if (r is RoutePlanResult.Success) {
                route = r.route
                drawRoute(r.route, fitAll = true)
                tvDestSub.text = routeSummary(r.route)
                started = true
                engine.start(destName, destLat, destLng, from, r.route)
                userPanned = false
                lastCameraMs = 0L
            } else if (r is RoutePlanResult.Failure) {
                Toast.makeText(
                    this@GuidanceActivity,
                    getString(R.string.guidance_plan_failed, r.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- 地图 ----------

    private fun setupMap(savedInstanceState: Bundle?) {
        if (!AmapManager.initMap(this)) return
        mapView = findViewById<MapView?>(R.id.guidance_map).also { mv ->
            mv ?: return
            runCatching {
                mv.onCreate(savedInstanceState)
                aMap = mv.map?.apply {
                    mapType = AMap.MAP_TYPE_NIGHT
                    uiSettings.isZoomControlsEnabled = false
                    uiSettings.isMyLocationButtonEnabled = false
                    uiSettings.isRotateGesturesEnabled = false
                    uiSettings.isTiltGesturesEnabled = false
                    // 关掉 SDK 自带定位：位置统一由导航引擎提供，避免两套定位打架
                    runCatching { isMyLocationEnabled = false }
                    runCatching { setTrafficEnabled(true) }
                    // 用户拖动地图 → 暂停自动跟随（专业导航软件的通行做法）
                    setOnMapTouchListener { userPanned = true }
                    // 首次布局偶尔只出灰底不出瓦片：底图加载完成后重设一次类型强制刷新
                    setOnMapLoadedListener {
                        mapView?.postDelayed({
                            runCatching { mapType = AMap.MAP_TYPE_NIGHT }
                        }, 350L)
                    }
                }
            }
        }
    }

    /** 画出规划路线；fitAll=true 时缩放到全线 */
    private fun drawRoute(r: RouteData, fitAll: Boolean = true) {
        val map = aMap ?: return
        val points = ArrayList<LatLng>(512)
        r.steps.forEach { step ->
            step.polyline.forEach { (lat, lng) -> points.add(LatLng(lat, lng)) }
        }
        if (points.size < 2) return
        try {
            routeLine?.remove()
            routeLine = map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(18f)
                    .color(Color.parseColor("#FF9F0A"))
                    .setDottedLine(false)
            )
            destMarker?.remove()
            destMarker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(destLat, destLng))
                    .title(destName)
                    .draggable(false)
            )
            if (!fitAll) return
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            builder.include(LatLng(destLat, destLng))
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(builder.build(), 160), 700, null
            )
        } catch (_: Exception) {
        }
    }

    /** 当前位置点（与导航数据同源，替代 SDK 自带蓝点） */
    private fun updateMeMarker(s: NaviEngine.NaviState) {
        val map = aMap ?: return
        if (s.latitude == 0.0 && s.longitude == 0.0) return
        try {
            val pos = LatLng(s.latitude, s.longitude)
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
            meMarker?.alpha = when {
                !s.gpsActive -> 0.45f
                accuracyLevel(s.accuracy) >= 2 -> 1f
                else -> 0.8f
            }
        } catch (_: Exception) {
        }
    }

    /** 地图跟随当前位置（节流 1.5s；用户拖动后停止跟随） */
    private fun followOnMap(s: NaviEngine.NaviState, force: Boolean = false) {
        val map = aMap ?: return
        if (s.latitude == 0.0 && s.longitude == 0.0) return
        if (userPanned && !force) return
        // 兜底：单帧位移超过 5km 时不跟随，避免定位错点时地图"飞走"变白屏
        if (!force && lastFollowLat != 0.0 &&
            com.motohud.ble.navi.LocationFilter.distanceM(
                lastFollowLat, lastFollowLng, s.latitude, s.longitude
            ) > 5000f
        ) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCameraMs < 1500L) return
        lastCameraMs = now
        lastFollowLat = s.latitude
        lastFollowLng = s.longitude
        try {
            val target = LatLng(s.latitude, s.longitude)
            if (s.bearing.isFinite() && s.speedKmh > 8f) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        com.amap.api.maps.model.CameraPosition.builder()
                            .target(target)
                            .zoom(17.2f)
                            .bearing(s.bearing)
                            .tilt(45f)
                            .build()
                    ), 800, null
                )
            } else {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(target, 17.2f), 800, null
                )
            }
        } catch (_: Exception) {
        }
    }

    // ---------- 状态渲染 ----------

    private fun observeState() {
        lifecycleScope.launch {
            engine.state.collectLatest { s -> render(s) }
        }
        lifecycleScope.launch {
            ble.state.collectLatest { st ->
                val connected = st == BleConnectionManager.State.CONNECTED
                tvBleHint.visibility = if (connected) View.GONE else View.VISIBLE
            }
        }
    }

    private fun render(s: NaviEngine.NaviState) {
        // 路线规划尚未落地时不消费引擎里的历史状态（可能属于上一次导航）
        if (!started) return
        if (!s.running && !s.arrived) return

        updateMeMarker(s)

        // 转向横幅
        tvTurnInstruction.text = s.stepInstruction.ifBlank {
            if (s.arrived) getString(R.string.hud_arrived) else getString(R.string.hud_step_remain_label)
        }
        val roadText = route?.steps?.getOrNull(s.stepIndex)?.road.orEmpty()
        tvTurnRoad.text = roadText.ifBlank {
            if (s.onRoute) getString(R.string.hud_step_remain_label) else getString(R.string.guidance_demo_short)
        }
        tvTurnDistance.text = CoordConverter.formatDistance(s.stepRemain)
        ivTurnIcon.setImageResource(HudActivity.iconRes(s.iconType))

        // 底部信息
        tvRemain.text = CoordConverter.formatDistance(s.pathRemain)
        tvSpeed.text = String.format(Locale.US, "%.0f", s.speedKmh)
        tvEta.text = if (s.arrived) clock.format(Date()) else
            clock.format(Date(System.currentTimeMillis() + s.etaSeconds * 1000L))

        // 定位质量
        tvGpsMode.text = when {
            !s.gpsActive -> getString(R.string.guidance_simulated)
            s.locType != 0 -> locationTypeLabel(s.locType)
            else -> "GPS"
        }
        tvGpsQuality.text = if (s.gpsActive && s.accuracy > 0f) formatAccuracy(s.accuracy)
        else getString(R.string.guidance_gps)

        // 信号弱：只在导航中真的弱时提示，且只弹一次
        val weak = s.running && (!s.gpsActive || accuracyLevel(s.accuracy) == 1)
        tvSignalWarn.visibility = if (weak) View.VISIBLE else View.GONE
        if (weak && !weakGpsToastShown && s.accuracy > 40f) {
            weakGpsToastShown = true
            Toast.makeText(
                this,
                getString(R.string.navi_gps_weak_message, s.accuracy.roundToInt()),
                Toast.LENGTH_LONG
            ).show()
        }

        if (s.arrived) {
            if (!stopped) {
                stopped = true
                app.naviForwarder.forwardFinish()
                speak(getString(R.string.hud_arrived))
            }
            arrivedOverlay.visibility = View.VISIBLE
            turnBanner.visibility = View.GONE
        } else {
            arrivedOverlay.visibility = View.GONE
            turnBanner.visibility = View.VISIBLE
            followOnMap(s)
        }
    }

    // ---------- 交互 ----------

    /**
     * 显示方式：手机导航（本页竖屏全屏） / 投屏车机（[HudActivity] 横屏大字）。
     *
     * 两种形态共用同一个 [RouteNaviEngine] 与 [com.motohud.ble.navi.NaviForwarder]，
     * 切到车机后本页仍在后台继续跑，返回即回到手机导航，导航过程不中断。
     */
    private fun showDisplayModeDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_display_mode, null)
        val optPhone = v.findViewById<View>(R.id.opt_phone)
        val optCast = v.findViewById<View>(R.id.opt_cast)
        val checkPhone = v.findViewById<View>(R.id.check_phone)
        val checkCast = v.findViewById<View>(R.id.check_cast)

        fun render() {
            val phone = NaviPrefs.phoneMode.value
            checkPhone.visibility = if (phone) View.VISIBLE else View.INVISIBLE
            checkCast.visibility = if (phone) View.INVISIBLE else View.VISIBLE
        }
        render()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.navi_display_mode)
            .setView(v)
            .setNegativeButton(R.string.cancel, null)
            .create()

        optPhone.setOnClickListener {
            NaviPrefs.setPhoneMode(true)
            dialog.dismiss()
            Toast.makeText(this, R.string.navi_mode_phone_toast, Toast.LENGTH_SHORT).show()
        }
        optCast.setOnClickListener {
            NaviPrefs.setPhoneMode(false)
            dialog.dismiss()
            Toast.makeText(this, R.string.navi_mode_cast_toast, Toast.LENGTH_SHORT).show()
            openHud()
        }

        dialog.show()
    }

    private fun openHud() {
        startActivity(
            Intent(this, HudActivity::class.java).apply {
                putExtra(HudActivity.EXTRA_NAME, destName)
                putExtra(HudActivity.EXTRA_LAT, destLat)
                putExtra(HudActivity.EXTRA_LNG, destLng)
            }
        )
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guidance_exit_title)
            .setMessage(R.string.guidance_exit_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.guidance_stop) { _, _ ->
                stopNavi(sendFinish = false)
                finish()
            }
            .show()
    }

    /** 停止导航并向车机发 NO（复刻原 App 退出写 "NO"） */
    private fun stopNavi(sendFinish: Boolean) {
        if (stopped) return
        stopped = true
        engine.onStepChanged = null
        engine.stop()
        if (sendFinish) {
            app.naviForwarder.forwardFinish()
        } else {
            app.naviForwarder.forwardExit()
        }
        turnBanner.visibility = View.GONE
    }

    // ---------- TTS ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = runCatching { tts?.setLanguage(Locale.CHINA) }
                ttsReady = r.isSuccess && r.getOrNull() != TextToSpeech.LANG_MISSING_DATA &&
                    r.getOrNull() != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank() || !NaviPrefs.voice.value) return
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guidance")
        }
    }

    // ---------- 权限 ----------

    private fun ensureLocationPermission() {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermLauncher.launch(missing.toTypedArray())
    }

    // ---------- 生命周期 ----------

    override fun onStart() {
        super.onStart()
        app.naviForwarder.attach(TAG_FORWARD)
    }

    override fun onStop() {
        app.naviForwarder.detach(TAG_FORWARD)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        // 从车机 HUD 返回本页：显示方式实际已回到「手机导航」，
        // 同步一次偏好，避免对话框里的勾选和当前页面不一致。
        NaviPrefs.setPhoneMode(true)
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        // 用户未主动停止（直接返回键/进程回收）时，同样向车机发 NO 退出
        if (!stopped) {
            stopped = true
            engine.onStepChanged = null
            engine.stop()
            app.naviForwarder.forwardExit()
        }
        mapView?.onDestroy()
        mapView = null
        aMap = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private const val TAG_FORWARD = "guidance"
    }
}
