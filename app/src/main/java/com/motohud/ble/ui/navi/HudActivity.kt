package com.motohud.ble.ui.navi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.IconType
import com.motohud.ble.navi.NaviEngine
import com.motohud.ble.navi.RouteNaviEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 投屏导航 HUD（Head-Up Display）。
 *
 * 复刻原 App 投屏导航链路（BaseNaviActivity.onNaviInfoUpdate）：
 *   NaviEngine 每 tick 产生 NaviState（iconType / 剩余距离）
 *     → NaviCommand.forwardInfo 按设备类型发 REM/NEXT/NAVI 或 NA/RE/NE
 *     → 到达发 FINISH :::，退出发 NO
 *
 * 与专业导航页共用 [MotoHudApp.naviEngine]：从导航页切换过来时延续同一路线，
 * 指令转发通过 [com.motohud.ble.navi.NaviForwarder] 的附着机制互斥，不会重复发送。
 *
 * 界面：横屏大字体（骑行中远距离可读），屏幕常亮。
 * 底部演示按钮可手动发送各转向图标，便于无车机时验证协议链路。
 */
class HudActivity : AppCompatActivity() {

    private lateinit var app: MotoHudApp
    private lateinit var engine: RouteNaviEngine
    private lateinit var ble: BleConnectionManager

    private lateinit var tvDest: TextView
    private lateinit var tvDevice: TextView
    private lateinit var ivIcon: ImageView
    private lateinit var tvStepRemain: TextView
    private lateinit var tvPathRemain: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvTraveled: TextView
    private lateinit var layoutArrived: View

    private var stopped = false
    private var startedHere = false

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 无论结果都启动：无 GPS 时引擎自动走航位推算 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud)

        app = application as MotoHudApp
        ble = app.bleManager
        engine = app.naviEngine

        tvDest = findViewById(R.id.tv_hud_dest)
        tvDevice = findViewById(R.id.tv_hud_device)
        ivIcon = findViewById(R.id.iv_hud_icon)
        tvStepRemain = findViewById(R.id.tv_hud_step_remain)
        tvPathRemain = findViewById(R.id.tv_hud_path_remain)
        tvEta = findViewById(R.id.tv_hud_eta)
        tvSpeed = findViewById(R.id.tv_hud_speed)
        tvGps = findViewById(R.id.tv_hud_gps)
        tvTraveled = findViewById(R.id.tv_hud_traveled)
        layoutArrived = findViewById(R.id.layout_hud_arrived)

        val destName = intent.getStringExtra(EXTRA_NAME) ?: "目的地"
        val destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)

        tvDest.text = destName

        // 屏幕常亮（复刻原 App setScreenAlwaysBright(true)）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ensureLocationPermission()

        // 观察导航状态 → 刷新 UI + BLE 转发
        lifecycleScope.launch {
            engine.state.collectLatest { s -> render(s) }
        }

        // 已在导航中（从专业导航页切过来）则延续，不重启；否则独立启动演示导航
        val current = engine.state.value
        if (!current.running && !current.arrived) {
            if (!hasLocationFix()) {
                Toast.makeText(
                    this,
                    R.string.guidance_no_location,
                    Toast.LENGTH_LONG
                ).show()
            }
            startedHere = true
            engine.start(destName, destLat, destLng)
            com.motohud.ble.data.NaviPrefs.init(this)
            if (com.motohud.ble.data.NaviPrefs.screenOffNavi.value) {
                com.motohud.ble.service.NaviForegroundService.start(this)
            }
        }
        updateDeviceLabel()

        // ---------- 控制按钮 ----------
        // 原先这里有一排"手动发某个转向图标"与"速度 ±10"的按钮，属于联调面板，
        // 已从用户界面移除。改为「息屏导航」开关。
        val swScreenOff = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.sw_hud_screen_off
        )
        com.motohud.ble.data.NaviPrefs.init(this)
        swScreenOff.isChecked = com.motohud.ble.data.NaviPrefs.screenOffNavi.value
        swScreenOff.setOnCheckedChangeListener { _, checked ->
            com.motohud.ble.data.NaviPrefs.setScreenOffNavi(checked)
            if (checked && !stopped) {
                com.motohud.ble.service.NaviForegroundService.start(this)
            } else {
                com.motohud.ble.service.NaviForegroundService.stop(this)
            }
            Toast.makeText(
                this,
                if (checked) R.string.hud_screen_off_on else R.string.hud_screen_off_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<View>(R.id.btn_hud_stop).setOnClickListener {
            stopNavi(sendFinish = true)
            finish()
        }
    }

    private fun render(s: NaviEngine.NaviState) {
        ivIcon.setImageResource(iconRes(s.iconType))
        tvStepRemain.text = CoordConverter.formatDistance(s.stepRemain)
        tvPathRemain.text = CoordConverter.formatDistance(s.pathRemain)
        tvEta.text = CoordConverter.formatEta(s.etaSeconds)
        tvSpeed.text = String.format(java.util.Locale.US, "%.0f km/h", s.speedKmh)
        tvGps.text = if (s.gpsActive) "GPS 定位" else "模拟推算"
        tvTraveled.text = CoordConverter.formatDistance(s.traveled)
        layoutArrived.visibility = if (s.arrived) View.VISIBLE else View.GONE

        // 每 tick 转发一帧（复刻原 App onNaviInfoUpdate 每次都写）
        if ((s.running || s.arrived) && !stopped) {
            app.naviForwarder.forward(s)
        }
        if (s.arrived && !stopped) {
            stopped = true
            app.naviForwarder.forwardFinish()
        }
    }

    /** 停止导航：向车机发 FINISH（可选）后写 NO 退出 */
    private fun stopNavi(sendFinish: Boolean) {
        if (stopped) {
            engine.stop()
            return
        }
        stopped = true
        engine.stop()
        com.motohud.ble.service.NaviForegroundService.stop(this)
        if (sendFinish) app.naviForwarder.forwardFinish()
        app.naviForwarder.forwardExit()
        Toast.makeText(this, R.string.hud_navi_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun updateDeviceLabel() {
        val connected = ble.state.value == BleConnectionManager.State.CONNECTED
        tvDevice.text = if (connected) {
            "已连接：${ble.connectedDeviceName ?: "未知设备"}"
        } else {
            "未连接车机（指令将在连接后发送）"
        }
    }

    private fun ensureLocationPermission() {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermLauncher.launch(missing.toTypedArray())
    }

    /** 是否已取得定位（权限 + 最后已知位置） */
    private fun hasLocationFix(): Boolean {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return false
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return false
        return try {
            @Suppress("MissingPermission")
            lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) != null ||
                lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun onStart() {
        super.onStart()
        app.naviForwarder.attach(TAG_FORWARD)
    }

    override fun onStop() {
        super.onStop()
        // 息屏导航：熄屏后本页会 stop，但导航仍在跑，不能断转发
        if (stopped || isFinishing) app.naviForwarder.detach(TAG_FORWARD)
    }

    override fun onResume() {
        super.onResume()
        updateDeviceLabel()
    }

    override fun onDestroy() {
        // 独立启动的 HUD：退出即结束导航（复刻 BaseNaviActivity.onDestroy writeData("NO")）
        // 从专业导航页切过来的：只解除转发，导航在导航页继续
        if (startedHere && !stopped) {
            stopped = true
            engine.stop()
            com.motohud.ble.service.NaviForegroundService.stop(this)
            app.naviForwarder.forwardExit()
        }
        app.naviForwarder.detach(TAG_FORWARD)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private const val TAG_FORWARD = "hud"

        fun iconRes(iconType: Int): Int = when (iconType) {
            IconType.LEFT -> R.drawable.ic_turn_left
            IconType.RIGHT -> R.drawable.ic_turn_right
            IconType.LEFT_FRONT -> R.drawable.ic_turn_slight_left
            IconType.RIGHT_FRONT -> R.drawable.ic_turn_slight_right
            IconType.LEFT_BACK -> R.drawable.ic_turn_slight_left
            IconType.RIGHT_BACK -> R.drawable.ic_turn_slight_right
            IconType.LEFT_TURN_AROUND, IconType.U_TURN_RIGHT -> R.drawable.ic_turn_uturn
            IconType.ENTER_ROUNDABOUT, IconType.ENTRY_RING_LEFT -> R.drawable.ic_turn_roundabout
            IconType.ARRIVED_DESTINATION, IconType.ARRIVED_WAYPOINT -> R.drawable.ic_turn_arrive
            else -> R.drawable.ic_turn_straight
        }
    }
}
