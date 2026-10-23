package com.motohud.ble.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.data.SearchEngine
import com.motohud.ble.databinding.FragmentSearchBinding
import com.motohud.ble.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 搜索页 —— 复刻原 App 搜索架构的本地实现：
 * 输入防抖（300ms，修正原 App 一字符一请求）、分页（page/size=10，
 * 复刻 SearchAllFragment 的 page==1 重填/满页才 page++ 语义）、
 * Room 历史持久化（对应原 SEARCH_KEY 表）、一键清空。
 */
class SearchFragment : Fragment(R.layout.fragment_search) {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireActivity().application as MotoHudApp)
    }

    private lateinit var resultAdapter: SearchResultAdapter
    private var searchJob: Job? = null
    private var currentKeyword = ""
    private var currentScope: SearchEngine.Scope? = null
    private var page = 1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSearchBinding.bind(view)

        resultAdapter = SearchResultAdapter()
        binding.resultList.layoutManager = LinearLayoutManager(requireContext())
        binding.resultList.adapter = resultAdapter

        // 范围过滤（对应原 App 的搜索 Tab）
        val scopes = listOf("全部") + SearchEngine.Scope.entries.map { it.label }
        binding.spinnerScope.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, scopes
        )
        binding.spinnerScope.setOnItemSelectedListener(object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentScope = SearchEngine.Scope.entries.getOrNull(pos - 1)
                page = 1
                runSearch(currentKeyword)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // 输入防抖（原 App POI 搜索无防抖，此处修正）
        binding.etKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                page = 1
                runSearch(s?.toString() ?: "")
            }
        })

        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch {
                vm.searchEngine.clearHistory()
                loadHistory()
            }
        }

        lifecycleScope.launch { loadHistory() }
    }

    private fun runSearch(keyword: String) {
        currentKeyword = keyword
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            vm.searchEngine.search(keyword, currentScope).collectLatest { p ->
                resultAdapter.submit(p.items, p.page == 1)
                binding.resultEmpty.visibility =
                    if (p.items.isEmpty()) View.VISIBLE else View.GONE
                if (keyword.isNotBlank()) {
                    vm.searchEngine.saveHistory(keyword, currentScope ?: SearchEngine.Scope.FAULT)
                    loadHistory()
                }
            }
        }
    }

    private suspend fun loadHistory() {
        val history = vm.searchEngine.recentHistory()
        val chips = history.map { h ->
            com.google.android.material.chip.Chip(requireContext()).apply {
                text = h.keyword
                isCheckable = false
                setOnClickListener {
                    binding.etKeyword.setText(h.keyword)
                    binding.etKeyword.setSelection(h.keyword.length)
                }
            }
        }
        binding.historyContainer.removeAllViews()
        chips.forEach { binding.historyContainer.addView(it) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
