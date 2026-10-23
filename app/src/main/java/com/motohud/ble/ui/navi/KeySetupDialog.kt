package com.motohud.ble.ui.navi

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager

/**
 * 高德地图 Key 配置对话框。
 *
 * 用户在高德开放平台（lbs.amap.com）申请 Android Key（绑定包名 com.motohud.ble），
 * 粘贴保存后立即生效（三个 SDK 均支持运行时 setApiKey）。
 */
class KeySetupDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val tvStatus = TextView(ctx).apply {
            text = currentStatusText()
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        root.addView(tvStatus)

        val tvHint = TextView(ctx).apply {
            text = getString(R.string.amap_key_hint_settings)
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_tertiary, null))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(tvHint)

        val et = TextInputEditText(ctx).apply {
            hint = getString(R.string.amap_key_input_hint)
            setText(AmapManager.apiKey(ctx))
            setSingleLine()
        }
        root.addView(et)

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.amap_key_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton("清除") { _, _ ->
                AmapManager.clearApiKey(ctx)
                Toast.makeText(ctx, R.string.amap_key_cleared, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val key = et.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    Toast.makeText(ctx, R.string.amap_key_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AmapManager.setApiKey(ctx, key)
                // 预热各 SDK
                AmapManager.initMap(ctx)
                AmapManager.initSearch(ctx)
                AmapManager.initLocation(ctx)
                Toast.makeText(ctx, R.string.amap_key_applied, Toast.LENGTH_SHORT).show()
                // 刷新导航页的降级状态
                (activity as? com.motohud.ble.ui.MainActivity)?.refreshNaviKeyState()
            }
            .create()
    }

    private fun currentStatusText(): String {
        val ctx = requireContext()
        val key = AmapManager.apiKey(ctx)
        return when {
            key.isEmpty() -> getString(R.string.amap_key_none)
            AmapManager.isKeyFromBuild(ctx) -> getString(R.string.amap_key_from_build)
            else -> getString(R.string.amap_key_current, maskKey(key))
        }
    }

    private fun maskKey(key: String): String =
        if (key.length <= 8) key else key.take(6) + "****" + key.takeLast(4)
}
