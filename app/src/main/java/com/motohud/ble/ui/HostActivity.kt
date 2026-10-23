package com.motohud.ble.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.motohud.ble.R
import com.motohud.ble.databinding.ActivityHostBinding
import com.motohud.ble.ui.search.SearchFragment

/**
 * 二级页面容器。
 *
 * 主界面只保留 6 个高频页签，低频功能（如本地检索）通过这里打开，
 * 避免底部导航越堆越多。
 */
class HostActivity : AppCompatActivity() {

    companion object {
        const val PAGE_SEARCH = "search"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PAGE = "page"

        fun start(context: Context, title: String, page: String) {
            context.startActivity(
                Intent(context, HostActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_PAGE, page)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.btnBack.setOnClickListener { finish() }

        if (savedInstanceState == null) {
            val page: Fragment? = when (intent.getStringExtra(EXTRA_PAGE)) {
                PAGE_SEARCH -> SearchFragment()
                else -> null
            }
            page?.let {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, it)
                    .commit()
            }
        }
    }
}
