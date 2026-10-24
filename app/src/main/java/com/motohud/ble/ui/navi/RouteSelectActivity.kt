package com.motohud.ble.ui.navi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.amap.AmapLocator
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteHandoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 路线选择页。
 *
 * 以前点一个地点就直接进导航、用第一条路线，用户没有选择权。
 * 现在先算 2~3 套方案（快 / 短 / 省钱），看清楚距离、时间、红绿灯、
 * 过路费，再决定走哪条；右上「路线偏好」可随时改变方案生成规则。
 */
class RouteSelectActivity : AppCompatActivity() {

    private lateinit var adapter: RouteOptionAdapter
    private lateinit var list: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var prefValue: TextView

    private var destName = "目的地"
    private var destLat = 0.0
    private var destLng = 0.0
    private var startLat = 0.0
    private var startLng = 0.0

    private var routes: List<RouteData> = emptyList()
    private var selected = 0

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { plan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_select)

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "目的地" }
        destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        startLat = intent.getDoubleExtra(EXTRA_FROM_LAT, 0.0)
        startLng = intent.getDoubleExtra(EXTRA_FROM_LNG, 0.0)

        val topBar = findViewById<View>(R.id.rs_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            insets
        }

        findViewById<TextView>(R.id.rs_dest_name).text = destName
        findViewById<TextView>(R.id.rs_dest_sub).text = CoordConverter.formatLatLng(destLat, destLng)

        prefValue = findViewById(R.id.rs_pref_value)
        renderPref()

        list = findViewById(R.id.rs_list)
        loading = findViewById(R.id.rs_loading)
        empty = findViewById(R.id.rs_empty)
        adapter = RouteOptionAdapter(
            onClick = { index ->
                selected = index
                adapter.setSelected(index)
                updateStartButton()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.rs_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.rs_pref_row).setOnClickListener { showPrefs() }
        findViewById<View>(R.id.rs_start).setOnClickListener { startNavi() }
        findViewById<View>(R.id.rs_other_map).setOnClickListener { showThirdPartyMaps() }

        ensureLocationPermissionThenPlan()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderPref() {
        prefValue.text = NaviPrefs.summary()
    }

    private fun showPrefs() {
        val d = RoutePrefsDialog()
        d.onApplied = {
            renderPref()
            plan()
        }
        d.show(supportFragmentManager, "route_prefs")
    }

    private fun ensureLocationPermissionThenPlan() {
        if (startLat != 0.0 && startLng != 0.0) {
            plan()
            return
        }
        val missing = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            return
        }
        plan()
    }

    private fun plan() {
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        list.visibility = View.INVISIBLE
        lifecycleScope.launch {
            // 起点：调用方给了就用，没给就现取一次实时定位
            if (startLat == 0.0 || startLng == 0.0) {
                val fix = runCatching { AmapLocator(this@RouteSelectActivity).locateOnce() }.getOrNull()
                if (fix != null) {
                    startLat = fix.lat
                    startLng = fix.lng
                }
            }
            if (startLat == 0.0 || startLng == 0.0) {
                loading.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.setText(R.string.navi_locate_failed)
                return@launch
            }
            if (!AmapManager.hasKey(this@RouteSelectActivity) ||
                !AmapManager.isPrivacyAgreed(this@RouteSelectActivity)
            ) {
                loading.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.setText(R.string.route_select_no_map)
                return@launch
            }

            AmapManager.initSearch(this@RouteSelectActivity)
            val fromLat = startLat
            val fromLng = startLng
            val strategy = NaviPrefs.strategy()
            val summary = NaviPrefs.summary()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@RouteSelectActivity)
                        .planCandidates(fromLat, fromLng, destLat, destLng, strategy, summary)
                }
            }.getOrNull()

            loading.visibility = View.GONE
            when (result) {
                is RoutePlanResult.Many -> {
                    // 高德返回的顺序是按策略来的，不保证最快在前。
                    // 统一按耗时升序排，让第一项就是"最快"，默认选中它也符合直觉。
                    routes = result.routes.sortedBy { it.durationS }
                    selected = 0
                    adapter.submit(routes, 0)
                    list.visibility = View.VISIBLE
                    updateStartButton()
                }
                is RoutePlanResult.Success -> {
                    routes = listOf(result.route)
                    selected = 0
                    adapter.submit(routes, 0)
                    list.visibility = View.VISIBLE
                    updateStartButton()
                }
                is RoutePlanResult.Failure -> {
                    empty.visibility = View.VISIBLE
                    empty.text = getString(R.string.guidance_plan_failed, result.message)
                }
                null -> {
                    empty.visibility = View.VISIBLE
                    empty.setText(R.string.guidance_plan_failed_short)
                }
            }
        }
    }

    private fun updateStartButton() {
        val btn = findViewById<TextView>(R.id.rs_start)
        btn.isEnabled = routes.isNotEmpty()
        btn.alpha = if (routes.isNotEmpty()) 1f else 0.5f
    }

    private fun startNavi() {
        val r = routes.getOrNull(selected) ?: return
        RouteHandoff.put(destName, destLat, destLng, r)
        startActivity(
            Intent(this, GuidanceActivity::class.java).apply {
                putExtra(GuidanceActivity.EXTRA_NAME, destName)
                putExtra(GuidanceActivity.EXTRA_LAT, destLat)
                putExtra(GuidanceActivity.EXTRA_LNG, destLng)
                putExtra(GuidanceActivity.EXTRA_FROM_LAT, startLat)
                putExtra(GuidanceActivity.EXTRA_FROM_LNG, startLng)
            }
        )
    }

    /**
     * 把当前目的地交给第三方地图 App 继续导航。
     *
     * 放在这一层而不是导航页底部，是因为这里已经有确定的坐标 ——
     * 导航页那时还什么都没有，只能拿当前位置凑数，交接过去没意义。
     * 同时只列第三方地图，不列「手机导航 / 蓝牙投屏」：
     * 那两个由 GuidanceActivity 的「显示方式」对话框负责，重复列会让人困惑。
     */
    private fun showThirdPartyMaps() {
        val dialog = MapChoiceDialog.newInstance(destName, destLat, destLng)
        dialog.show(supportFragmentManager, "third_party_map")
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_FROM_LAT = "from_lat"
        const val EXTRA_FROM_LNG = "from_lng"

        /** 统一入口：所有"选个地点"的地方都走这里，先出方案再导航 */
        fun start(
            ctx: Context,
            name: String,
            lat: Double,
            lng: Double,
            fromLat: Double = 0.0,
            fromLng: Double = 0.0
        ) {
            ctx.startActivity(
                Intent(ctx, RouteSelectActivity::class.java).apply {
                    putExtra(EXTRA_NAME, name)
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LNG, lng)
                    putExtra(EXTRA_FROM_LAT, fromLat)
                    putExtra(EXTRA_FROM_LNG, fromLng)
                }
            )
        }
    }
}

/** 候选路线列表 */
private class RouteOptionAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RouteOptionAdapter.VH>() {

    private var data: List<RouteData> = emptyList()
    private var selected = 0

    /** 每条方案的角标文案，见 [buildBadges] */
    private var badges: List<String> = emptyList()

    fun submit(list: List<RouteData>, sel: Int) {
        data = list
        selected = sel
        badges = buildBadges(list)
        notifyDataSetChanged()
    }

    /**
     * 高德用 `DRIVING_MULTI_CHOICE_*` 一次返回多套方案时，三套方案的策略名
     * 往往一模一样（都是「速度最快」），并排除列在列表上毫无区分度。
     * 这种情况改成按维度打角标：最快 / 最短 / 灯少，其余标备选序号。
     * 如果高德给的标签本来就有差异，就照用它的。
     */
    private fun buildBadges(list: List<RouteData>): List<String> {
        if (list.isEmpty()) return emptyList()
        val distinct = list.map { it.label }.distinct().size
        if (distinct > 1) return list.map { it.label.ifBlank { "方案" } }
        val fastest = list.indices.minByOrNull { list[it].durationS } ?: 0
        val shortest = list.indices.minByOrNull { list[it].distanceM } ?: 0
        val fewestLights = list.indices.minByOrNull { list[it].trafficLights } ?: 0
        return list.indices.map { i ->
            when {
                i == fastest -> "最快"
                i == shortest -> "最短"
                i == fewestLights -> "灯少"
                else -> "备选 ${i + 1}"
            }
        }
    }

    fun setSelected(index: Int) {
        val old = selected
        selected = index
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(index)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.tv_route_label)
        val eta: TextView = v.findViewById(R.id.tv_route_eta)
        val distance: TextView = v.findViewById(R.id.tv_route_distance)
        val detail: TextView = v.findViewById(R.id.tv_route_detail)
        val root: View = v
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_option, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = data[position]
        holder.label.text = badges.getOrElse(position) { "方案 ${position + 1}" }
        holder.eta.text = CoordConverter.formatEta(r.durationS.toInt())
        holder.distance.text = CoordConverter.formatDistance(r.distanceM.toInt())
        holder.detail.text = buildString {
            append("红绿灯 ${r.trafficLights} 个")
            if (r.tolls > 0f) {
                append(" · 过路费 ")
                append(String.format(Locale.US, "¥%.0f", r.tolls))
            } else {
                append(" · 无过路费")
            }
            if (r.modeLabel.isNotBlank()) {
                append(" · ")
                append(r.modeLabel)
            }
        }
        val ctx = holder.itemView.context
        holder.root.setBackgroundResource(
            if (position == selected) R.drawable.bg_card_selected else R.drawable.bg_card
        )
        holder.root.setOnClickListener { onClick(position) }
        // 间距：卡片之间留 8dp
        (holder.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin = (8 * ctx.resources.displayMetrics.density).toInt()
            holder.root.layoutParams = lp
        }
    }
}
