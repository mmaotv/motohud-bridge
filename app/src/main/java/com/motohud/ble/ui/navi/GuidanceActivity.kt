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
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
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
import com.motohud.ble.ble.BleConnectionManager
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

/**
 * 专业导航页（复刻原厂 App 的 GPSNaviActivity 体验）。
 *
 * 数据流与原 App 保持一致，只是把"高德导航 SDK 的 NaviInfo"换成了自研的
 * [RouteNaviEngine]（路线来自高德搜索 SDK 的驾车路径规划）：
 *
 *   POI/收藏地址 → 路线规划 → 导航引擎每秒推演
 *     → 手机界面（转向横幅 / 剩余 / 预计到达 / 地图跟随）
 *     → [com.motohud.ble.navi.NaviForwarder] → BLE → 车机屏幕
 *
 * 特性：
 *  - 进入即开始导航（无需二次确认），与专业导航软件一致；
 *  - 顶部转向横幅 + 底部信息卡，随定位实时刷新；
 *  - 地图自动跟随、路线高亮、目的地标记；
 *  - 右上角"投屏"切换到横屏 HUD（共用同一引擎，不中断导航）；
 *  - 未配置高德 Key 时自动降级为演示模式（直线推算），蓝牙投屏仍可用。
 */
class GuidanceActivity : AppCompatActivity() {

    private lateinit var app: MotoHudApp
    private lateinit var engine: RouteNaviEngine
    private lateinit var ble: BleConnectionManager

    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var routeLine: Polyline? = null
    private var destMarker: Marker? = null

    private lateinit var tvDestName: TextView
    private lateinit var tvDestSub: TextView
    private lateinit var turnBanner: View
    private lateinit var ivTurnIcon: ImageView
    private lateinit var tvTurnInstruction: TextView
    private lateinit var tvTurnRoad: TextView
    private lateinit var tvTurnDistance: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvRemain: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGpsMode: TextView
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
    private var stopped = false
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

        bindViews()
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
        observeState()

        planAndStart()
    }

    private fun bindViews() {
        tvDestName = findViewById(R.id.tv_dest_name)
        tvDestSub = findViewById(R.id.tv_dest_sub)
        turnBanner = findViewById(R.id.turn_banner)
        ivTurnIcon = findViewById(R.id.iv_turn_icon)
        tvTurnInstruction = findViewById(R.id.tv_turn_instruction)
        tvTurnRoad = findViewById(R.id.tv_turn_road)
        tvTurnDistance = findViewById(R.id.tv_turn_distance)
        tvEta = findViewById(R.id.tv_eta)
        tvRemain = findViewById(R.id.tv_remain)
        tvSpeed = findViewById(R.id.tv_speed_live)
        tvGpsMode = findViewById(R.id.tv_gps_mode)
        tvBleHint = findViewById(R.id.tv_guidance_ble_hint)
        planningOverlay = findViewById(R.id.planning_overlay)
        arrivedOverlay = findViewById(R.id.arrived_overlay)
    }

    private fun bindActions() {
        findViewById<View>(R.id.btn_back).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btn_cast).setOnClickListener { openHud() }
        findViewById<View>(R.id.btn_cast_navi).setOnClickListener { openHud() }
        findViewById<View>(R.id.btn_stop_navi).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btn_finish_navi).setOnClickListener {
            stopNavi(sendFinish = true)
            finish()
        }
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@GuidanceActivity)
                        .planDriveRoute(from.first, from.second, destLat, destLng)
                }
            }
            planningOverlay.visibility = View.GONE
            val r = result.getOrNull()
            when {
                r is RoutePlanResult.Success -> {
                    route = r.route
                    drawRoute(r.route)
                    tvDestSub.text = String.format(
                        "%s · %s · %s",
                        CoordConverter.formatDistance(r.route.distanceM.toInt()),
                        CoordConverter.formatEta(r.route.durationS.toInt()),
                        CoordConverter.formatLatLng(destLat, destLng)
                    )
                    begin(start, r.route)
                }
                r is RoutePlanResult.Failure -> {
                    if (r.message == "KEY_MISSING") {
                        Toast.makeText(this@GuidanceActivity, R.string.guidance_need_key, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@GuidanceActivity,
                            getString(R.string.guidance_plan_failed, r.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    begin(start, null)
                }
                else -> begin(start, null)
            }
        }
    }

    private fun begin(start: Pair<Double, Double>?, r: RouteData?) {
        turnBanner.visibility = View.VISIBLE
        engine.onStepChanged = { instruction -> speak(instruction) }
        engine.start(destName, destLat, destLng, start, r)
        if (start == null) {
            Toast.makeText(this, R.string.guidance_no_location, Toast.LENGTH_LONG).show()
        }
    }

    /** 起点：系统最后已知位置（WGS84→GCJ02），无权限/无缓存时为 null */
    @Suppress("MissingPermission")
    private fun currentStart(): Pair<Double, Double>? {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.let { CoordConverter.wgs84ToGCJ(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 地图 ----------

    private fun setupMap(savedInstanceState: Bundle?) {
        if (!AmapManager.initMap(this)) {
            // 无 Key：隐藏地图，仅保留导航信息（演示模式）
            return
        }
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
                    runCatching { setTrafficEnabled(true) }
                    runCatching { isMyLocationEnabled = true }
                }
            }
        }
    }

    /** 画出规划路线并缩放到全线 */
    private fun drawRoute(r: RouteData) {
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
                    .width(16f)
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
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            builder.include(LatLng(destLat, destLng))
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(builder.build(), 120), 600, null
            )
        } catch (_: Exception) {
        }
    }

    /** 地图跟随当前位置（节流 1.5s，避免每 tick 抖动） */
    private fun followOnMap(s: NaviEngine.NaviState) {
        val map = aMap ?: return
        val now = System.currentTimeMillis()
        if (now - lastCameraMs < 1500L) return
        lastCameraMs = now
        try {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(s.latitude, s.longitude), 17.2f), 900, null
            )
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
        if (!s.running && !s.arrived) return

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
        tvGpsMode.text = if (s.gpsActive) "GPS" else getString(R.string.guidance_simulated)
        tvEta.text = if (s.arrived) clock.format(Date()) else
            clock.format(Date(System.currentTimeMillis() + s.etaSeconds * 1000L))

        if (s.arrived) {
            if (!stopped) {
                stopped = true
                app.naviForwarder.forwardFinish()
                speak(getString(R.string.hud_arrived))
            }
            arrivedOverlay.visibility = View.VISIBLE
            turnBanner.visibility = View.GONE
        } else {
            followOnMap(s)
        }
    }

    // ---------- 交互 ----------

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
        if (!ttsReady || text.isBlank()) return
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
