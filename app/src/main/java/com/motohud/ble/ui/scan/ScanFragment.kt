package com.motohud.ble.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.motohud.ble.R
import com.motohud.ble.ui.MainViewModel
import com.motohud.ble.databinding.FragmentScanBinding

/**
 * 连接页 —— 复刻 ScanMainActivity：BLE 扫描（10s 超时、BYQ/SR 过滤、
 * 定位权限与 GPS 引导、点击连接、自动重连提示）。
 */
class ScanFragment : Fragment(R.layout.fragment_scan) {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels { MainViewModel.Factory(requireActivity().application as com.motohud.ble.MotoHudApp) }
    private lateinit var adapter: DeviceAdapter

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startScanWithGpsCheck()
        else Toast.makeText(requireContext(), R.string.perm_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentScanBinding.bind(view)
        adapter = DeviceAdapter { item ->
            vm.ble.connect(item.address)
            Toast.makeText(requireContext(), "正在连接 ${item.name}", Toast.LENGTH_SHORT).show()
        }
        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.deviceList.adapter = adapter

        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }
        binding.btnDisconnect.setOnClickListener { vm.ble.disconnect() }

        vm.scanDevices.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        vm.connectionState.observe(viewLifecycleOwner) { state ->
            val text = when (state.name) {
                "CONNECTED" -> {
                    binding.tvStatus.text = getString(R.string.status_connected) +
                        (vm.ble.connectedDeviceName?.let { "：$it" } ?: "")
                    getString(R.string.status_connected)
                }
                "CONNECTING" -> getString(R.string.status_connecting)
                "RECONNECTING" -> getString(R.string.status_reconnecting)
                else -> getString(R.string.status_disconnected)
            }
            binding.tvStatus.text = text
            binding.btnDisconnect.isEnabled = state.name != "DISCONNECTED"
        }
    }

    private fun checkPermissionsAndScan() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            return
        }
        startScanWithGpsCheck()
    }

    /** 复刻 ScanMainActivity.onPermissionGranted：Android 6+ 需 GPS 打开才能扫描 */
    private fun startScanWithGpsCheck() {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsOn && android.os.Build.VERSION.SDK_INT >= 23) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.notify_title)
                .setMessage(R.string.gps_notify_msg)
                .setNegativeButton(R.string.cancel) { _, _ -> requireActivity().finish() }
                .setPositiveButton(R.string.setting) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setCancelable(false)
                .show()
            return
        }
        vm.ble.startScan()
        binding.btnScan.isEnabled = false
        binding.btnScan.postDelayed({
            vm.ble.stopScan()
            binding.btnScan.isEnabled = true
        }, com.motohud.ble.ble.BleConstants.SCAN_TIMEOUT_MS)
    }

    override fun onDestroyView() {
        vm.ble.stopScan()
        _binding = null
        super.onDestroyView()
    }
}
