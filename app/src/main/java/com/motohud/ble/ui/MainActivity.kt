package com.motohud.ble.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.motohud.ble.ui.common.MotoDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.motohud.ble.BuildConfig
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.AppCache
import com.motohud.ble.service.NotificationForwardService
import com.motohud.ble.ui.navi.KeySetupDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 主界面：6 个页签 + 顶部连接状态条。
 *
 * 状态呈现规则（连接 / 未连接全链路）：
 *  - DISCONNECTED：灰点 + "未连接车机"，右侧"去连接" → 跳到连接页；
 *  - CONNECTING / RECONNECTING：橙点 + 进行中文案，右侧动作隐藏（不可重复操作）；
 *  - CONNECTED：绿点 + "已连接：设备名"，右侧"设置"；
 *  - 各业务页在未连接时显示空态遮罩并给出"去连接"入口（见各 Fragment）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vm: MainViewModel
    private lateinit var pager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var banner: View
    private lateinit var bannerDot: ImageView
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果在各页按需处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动：先走新手引导（含隐私政策同意），完成后再进入主界面
        if (savedInstanceState == null && !OnboardingActivity.isDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        vm = ViewModelProvider(this, MainViewModel.Factory(application as MotoHudApp))[MainViewModel::class.java]

        banner = findViewById(R.id.banner_connection)
        bannerDot = findViewById(R.id.banner_dot)
        bannerText = findViewById(R.id.banner_text)
        bannerAction = findViewById(R.id.banner_action)

        pager = findViewById(R.id.view_pager)
        pager.adapter = MainPagerAdapter(this)
        pager.isUserInputEnabled = false
        pager.offscreenPageLimit = 1
        pager.setPageTransformer { page, position ->
            // 纯淡入淡出 + 轻微缩放（不横向位移，避免相邻页从边缘露出）
            val p = abs(position).coerceIn(0f, 1f)
            page.alpha = 1f - p
            val scale = 0.94f + (1f - p) * 0.06f
            page.scaleX = scale
            page.scaleY = scale
        }

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            pager.currentItem = when (item.itemId) {
                R.id.nav_garage -> TAB_GARAGE
                R.id.nav_navigation -> TAB_NAVIGATION
                R.id.nav_vehicle -> TAB_VEHICLE
                R.id.nav_scan -> TAB_CONNECT
                else -> TAB_GARAGE
            }
            true
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.selectedItemId = when (position) {
                    TAB_GARAGE -> R.id.nav_garage
                    TAB_NAVIGATION -> R.id.nav_navigation
                    TAB_VEHICLE -> R.id.nav_vehicle
                    else -> R.id.nav_scan
                }
                onTabShown(position)
            }
        })

        applyEdgeToEdge()
        setupConnectionBanner()
        requestBlePermissions()

        vm.toast.observe(this) { msg ->
            msg?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                vm.consumeToast()
            }
        }
    }

    /** 顶部连接状态条：连接状态 → 文案 / 圆点 / 动作 */
    private fun setupConnectionBanner() {
        val ble = (application as MotoHudApp).bleManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st -> renderBanner(st, ble.connectedDeviceName) }
            }
        }
        banner.setOnClickListener {
            if (ble.state.value == BleConnectionManager.State.CONNECTED) showSettings() else goToTab(TAB_CONNECT)
        }
        banner.setOnLongClickListener {
            showSettings()
            true
        }
        bannerAction.setOnClickListener {
            if (ble.state.value == BleConnectionManager.State.CONNECTED) showSettings() else goToTab(TAB_CONNECT)
        }
    }

    private fun renderBanner(state: BleConnectionManager.State, deviceName: String?) {
        banner.visibility = View.VISIBLE
        when (state) {
            BleConnectionManager.State.CONNECTED -> {
                bannerDot.setImageResource(R.drawable.dot_green)
                bannerText.text = getString(R.string.banner_connected, deviceName ?: "车机")
                bannerAction.setText(R.string.settings_title)
                bannerAction.visibility = View.VISIBLE
            }
            BleConnectionManager.State.CONNECTING -> {
                bannerDot.setImageResource(R.drawable.dot_orange)
                bannerText.setText(R.string.status_connecting)
                bannerAction.visibility = View.GONE
            }
            BleConnectionManager.State.RECONNECTING -> {
                bannerDot.setImageResource(R.drawable.dot_orange)
                bannerText.setText(R.string.status_reconnecting)
                bannerAction.visibility = View.GONE
            }
            BleConnectionManager.State.DISCONNECTED -> {
                bannerDot.setImageResource(R.drawable.dot_gray)
                bannerText.setText(R.string.banner_disconnected)
                bannerAction.setText(R.string.empty_action)
                bannerAction.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 边到边适配。
     *
     * targetSdk 35+ 在 Android 15 上强制 edge-to-edge，内容会画到状态栏下面，
     * 表现就是"顶部界面被状态栏压住 / 时间电量叠在标题上"。
     * 这里主动关掉系统自动避让，再自己按 insets 给状态条与底部导航留白，
     * 这样刘海屏、挖孔屏、手势条的数值都能自适应。
     */
    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val density = resources.displayMetrics.density
        val baseTop = (6 * density).toInt()
        val baseBottom = (6 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(banner) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + baseTop)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom + baseBottom)
            insets
        }
    }

    /** 切页（供各页空态"去连接"与状态条复用） */
    fun goToTab(index: Int) {
        if (!::pager.isInitialized) return
        pager.setCurrentItem(index.coerceIn(0, TAB_COUNT - 1), true)
    }

    /** 跳到车况页的指定子页：0=仪表 1=体检 2=指令 */
    fun goToVehicle(subPage: Int) {
        goToTab(TAB_VEHICLE)
        pager.post {
            supportFragmentManager.fragments
                .filterIsInstance<com.motohud.ble.ui.vehicle.VehicleFragment>()
                .firstOrNull()
                ?.selectPage(subPage)
        }
    }

    /**
     * 页面可见时通知对应页做一次刷新。
     * 导航页最需要：切过去时定位可能已经过期，必须主动重新取一次，
     * 否则会一直显示很久以前的位置。
     */
    private fun onTabShown(position: Int) {
        if (position != TAB_NAVIGATION) return
        supportFragmentManager.fragments.forEach { f ->
            (f as? com.motohud.ble.ui.navi.NavigationFragment)?.onTabShown()
        }
    }

    /** 高德 Key 变更后通知导航页重建地图（页面可能已创建但处于降级态） */
    fun refreshNaviKeyState() {
        supportFragmentManager.fragments.forEach { f ->
            if (f is com.motohud.ble.ui.navi.NavigationFragment) f.onAmapKeyChanged()
        }
    }

    // ---------- 设置 ----------

    /** 设置入口：顶部状态条、爱车页「设置」宫格共用同一个对话框 */
    fun showSettings() {
        val items = arrayOf(
            getString(R.string.settings_onboarding),
            getString(R.string.settings_amap_key),
            getString(R.string.settings_privacy),
            getString(R.string.settings_clear_cache),
            getString(R.string.settings_about)
        )
        MotoDialog.builder(this)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> OnboardingActivity.start(this)
                    1 -> KeySetupDialog().show(supportFragmentManager, "key_setup")
                    2 -> showPrivacy()
                    3 -> showClearCache()
                    4 -> com.motohud.ble.ui.about.AboutActivity::class.java.let {
                        startActivity(Intent(this, it))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPrivacy() {
        MotoDialog.builder(this)
            .setTitle(R.string.onboarding_privacy_title)
            .setMessage(R.string.onboarding_privacy_body)
            .setPositiveButton(R.string.setting) { _, _ -> /* 已同意，无需操作 */ }
            .show()
    }

    /**
     * 清除缓存：先异步算出当前大小展示给用户，确认后清空 cacheDir / externalCacheDir。
     * 只清标准缓存目录（地图瓦片、搜索结果等），filesDir 里的用户数据不动。
     */
    private fun showClearCache() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { AppCache.size(this@MainActivity) }
            val msg = if (size > 0) getString(R.string.cache_dialog_msg, AppCache.human(size))
            else getString(R.string.cache_empty)
            MotoDialog.builder(this@MainActivity)
                .setTitle(R.string.cache_dialog_title)
                .setMessage(msg)
                .setPositiveButton(R.string.cache_clear_btn) { _, _ ->
                    lifecycleScope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            AppCache.clear(this@MainActivity)
                        }
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.cache_cleared, AppCache.human(freed)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** BLE 权限（复刻原 App：定位权限 + Android 12+ 蓝牙权限） */
    private fun requestBlePermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** 引导用户授予通知访问权限（复刻 BaseActivity.openNotifyController 引导） */
    fun promptNotificationAccess() {
        MotoDialog.builder(this)
            .setTitle(R.string.notify_title)
            .setMessage(R.string.notify_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.setting) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }

    fun startForwardService() {
        ContextCompat.startForegroundService(this, Intent(this, NotificationForwardService::class.java))
    }

    override fun onDestroy() {
        // 首启跳转引导时会提前 finish（ViewModel 尚未初始化）
        if (::vm.isInitialized) vm.ble.stopScan()
        super.onDestroy()
    }

    companion object {
        const val TAB_GARAGE = 0
        const val TAB_NAVIGATION = 1
        const val TAB_VEHICLE = 2
        const val TAB_CONNECT = 3
        const val TAB_COUNT = 4

        /** 车况页子页 */
        const val VEHICLE_DASHBOARD = 0
        const val VEHICLE_EXAMINATION = 1
        const val VEHICLE_COMMAND = 2
    }
}
